"""The address, pinning and redirect rules of the saved-catalog fetch."""

from __future__ import annotations

import asyncio
import ipaddress
import logging

import httpx
import pytest

from quire_server.core import catalog_proxy
from quire_server.core.catalog_proxy import (
    FetchSlots,
    LoginBackoff,
    RateWindow,
    UpstreamError,
    address_refused,
)

PUBLIC = "93.184.215.14"
LOGIN = "Basic cmVhZGVyOnNlY3JldA=="  # pragma: allowlist secret
ORIGIN = "https://books.example.org"


def ip(text: str):
    return ipaddress.ip_address(text)


@pytest.mark.parametrize(
    "address",
    [
        "10.0.0.7",
        "172.16.3.4",
        "172.18.0.5",  # a Docker network
        "192.168.1.20",
        "100.64.0.1",
        "100.127.255.254",
        "fc00::5",
        "fd12:3456::1",
    ],
)
def test_private_ranges_are_refused_unless_allowed(address):
    assert address_refused(ip(address), allow_private=False)
    assert not address_refused(ip(address), allow_private=True)


@pytest.mark.parametrize(
    "address",
    [
        "224.0.0.251",
        "239.255.255.250",
        "ff02::1",
        "0.0.0.0",
        "::",
        "240.0.0.1",
        "255.255.255.255",
        "::ffff:127.0.0.1",
        "::ffff:93.184.215.14",
        "169.254.169.254",
        "169.254.170.2",
        "168.63.129.16",
        "100.100.100.200",
        "fd00:ec2::254",
        "fe80::1%eth0",
        "127.0.0.1",
        "127.8.9.10",
        "::1",
        "169.254.10.10",
        "fe80::1",
        "febf::1",
        "fec0::1",
        "feff::1",
        "::102:304",
        "::a00:1",
        "::ffff:0:0",
        "192.0.2.10",
        "2001:db8::1",
        "198.18.0.1",
        "0.1.2.3",
        "64:ff9b::7f00:1",  # NAT64 of 127.0.0.1
    ],
)
def test_never_a_catalog_whatever_the_operator_allows(address):
    assert address_refused(ip(address), allow_private=False)
    assert address_refused(ip(address), allow_private=True)


def test_nat64_is_judged_by_the_ipv4_address_it_carries():
    assert address_refused(ip("64:ff9b::a00:1"), allow_private=False)  # 10.0.0.1
    assert address_refused(ip("64:ff9b::a9fe:a9fe"), allow_private=True)  # metadata
    assert not address_refused(ip("64:ff9b::5db8:d70e"), allow_private=False)


@pytest.mark.parametrize("address", [PUBLIC, "8.8.8.8", "2606:4700:4700::1111"])
def test_public_addresses_pass(address):
    assert not address_refused(ip(address), allow_private=False)


class Recorder:
    """A fake catalog: answers by path and keeps every request it got."""

    def __init__(self, routes=None, refuse=()):
        self.routes = routes or {}
        self.refuse = set(refuse)
        self.requests: list[httpx.Request] = []
        self.attempts: list[str] = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.attempts.append(request.url.host)
        if request.url.host in self.refuse:
            raise httpx.ConnectError("refused", request=request)
        self.requests.append(request)
        return self.routes.get(request.url.path) or httpx.Response(200, text="<feed/>")


def resolver(*answers):
    """Each call returns the next answer; counts calls."""
    calls = []

    async def resolve(host, port):
        calls.append((host, port))
        return [ip(a) for a in answers[min(len(calls), len(answers)) - 1]]

    return resolve, calls


def same_origin(url: str) -> bool:
    return url.startswith(ORIGIN + "/")


async def open_(url, upstream, resolve, allow_private=False, authorization=LOGIN):
    return await catalog_proxy.open_upstream(
        url,
        same_origin=same_origin,
        headers={"accept": "application/atom+xml"},
        authorization=authorization,
        allow_private=allow_private,
        resolver=resolve,
        transport=httpx.MockTransport(upstream),
    )


async def test_connects_to_the_checked_address_with_the_real_name():
    upstream = Recorder()
    # The second answer is the rebinding attempt: it must never be asked for.
    resolve, calls = resolver([PUBLIC], ["127.0.0.1"])
    result = await open_(ORIGIN + "/opds", upstream, resolve)
    await result.aclose()

    assert calls == [("books.example.org", 443)]
    (sent,) = upstream.requests
    assert sent.url.host == PUBLIC and sent.url.path == "/opds"
    assert sent.headers["host"] == "books.example.org"
    assert sent.extensions["sni_hostname"] == "books.example.org"
    assert sent.headers["authorization"] == LOGIN
    assert sent.headers["accept-encoding"] == "identity"
    assert result.url == ORIGIN + "/opds"


async def test_a_non_default_port_stays_in_the_host_header():
    upstream = Recorder()
    resolve, calls = resolver([PUBLIC])
    await (await open_("https://books.example.org:8443/opds", upstream, resolve)).aclose()
    assert calls == [("books.example.org", 8443)]
    assert upstream.requests[0].headers["host"] == "books.example.org:8443"
    assert upstream.requests[0].url.port == 8443


async def test_one_private_address_in_the_answer_refuses_the_name():
    upstream = Recorder()
    resolve, _ = resolver([PUBLIC, "10.0.0.3"])
    with pytest.raises(UpstreamError) as refused:
        await open_(ORIGIN + "/opds", upstream, resolve)
    # The same public answer as a name that does not resolve; the log says why.
    assert (refused.value.status, refused.value.detail) == (502, "catalog_unreachable")
    assert refused.value.reason == "address_refused"
    assert upstream.requests == []


async def test_an_address_literal_is_checked_without_resolving():
    upstream = Recorder()
    resolve, calls = resolver([PUBLIC])
    for literal in ("https://169.254.169.254/latest", "https://[::ffff:10.0.0.1]/opds"):
        with pytest.raises(UpstreamError) as refused:
            await open_(literal, upstream, resolve, allow_private=True)
        assert refused.value.reason == "address_refused"
    assert calls == [] and upstream.requests == []


async def test_private_addresses_with_the_setting():
    upstream = Recorder()
    resolve, _ = resolver(["192.168.1.20"])
    await (await open_(ORIGIN + "/opds", upstream, resolve, allow_private=True)).aclose()
    assert upstream.requests[0].url.host == "192.168.1.20"


async def test_a_same_origin_redirect_is_followed_and_checked_again():
    upstream = Recorder({"/opds": httpx.Response(302, headers={"Location": "/opds/"})})
    resolve, calls = resolver([PUBLIC], ["203.0.113.9"])
    with pytest.raises(UpstreamError) as refused:
        await open_(ORIGIN + "/opds", upstream, resolve)
    assert refused.value.reason == "address_refused"
    # The second hop resolved again and met a documentation (non-public) address.
    assert len(calls) == 2 and len(upstream.requests) == 1

    upstream = Recorder({"/opds": httpx.Response(302, headers={"Location": "/opds/#top"})})
    resolve, _ = resolver([PUBLIC])
    result = await open_(ORIGIN + "/opds", upstream, resolve)
    await result.aclose()
    assert [r.url.path for r in upstream.requests] == ["/opds", "/opds/"]
    assert all(r.headers["authorization"] == LOGIN for r in upstream.requests)
    assert result.url == ORIGIN + "/opds/"


@pytest.mark.parametrize(
    "location",
    [
        "https://elsewhere.example.org/opds",
        "http://books.example.org/opds",
        "https://books.example.org:8443/opds",
        "//elsewhere.example.org/opds",
    ],
)
async def test_a_redirect_off_the_origin_is_refused_and_gets_no_login(location):
    upstream = Recorder({"/opds": httpx.Response(302, headers={"Location": location})})
    resolve, _ = resolver([PUBLIC])
    with pytest.raises(UpstreamError) as refused:
        await open_(ORIGIN + "/opds", upstream, resolve)
    assert (refused.value.status, refused.value.detail) == (502, "catalog_redirect_refused")
    assert [r.url.path for r in upstream.requests] == ["/opds"]


async def test_a_malformed_redirect_is_not_followed():
    upstream = Recorder({"/opds": httpx.Response(302, headers={"Location": "http://[::1"})})
    with pytest.raises(UpstreamError):
        await open_(ORIGIN + "/opds", upstream, resolver([PUBLIC])[0])
    assert len(upstream.requests) == 1


async def test_redirects_stop_after_a_few_hops():
    upstream = Recorder({"/loop": httpx.Response(307, headers={"Location": "/loop"})})
    resolve, _ = resolver([PUBLIC])
    with pytest.raises(UpstreamError, match="catalog_redirect_refused"):
        await open_(ORIGIN + "/loop", upstream, resolve)
    assert len(upstream.requests) == catalog_proxy.MAX_REDIRECTS + 1


async def test_an_address_that_does_not_connect_falls_back_to_the_next():
    upstream = Recorder(refuse={"2001:4860::8888"})
    resolve, _ = resolver(["2001:4860::8888", PUBLIC])
    await (await open_(ORIGIN + "/opds", upstream, resolve)).aclose()
    assert [r.url.host for r in upstream.requests] == [PUBLIC]

    upstream = Recorder(refuse={PUBLIC})
    with pytest.raises(UpstreamError, match="catalog_unreachable"):
        await open_(ORIGIN + "/opds", upstream, resolver([PUBLIC])[0])


async def test_at_most_two_addresses_are_tried():
    answers = ["2001:4860::8888", "2001:4860::8844", PUBLIC]
    upstream = Recorder(refuse=set(answers))
    with pytest.raises(UpstreamError, match="catalog_unreachable"):
        await open_(ORIGIN + "/opds", upstream, resolver(answers)[0])
    assert upstream.attempts == answers[:2]


async def test_headers_must_arrive_before_the_deadline(monkeypatch):
    monkeypatch.setattr(catalog_proxy, "HEADERS_DEADLINE_S", 0.05)

    async def slow(host, port):
        await asyncio.sleep(5)
        return [ip(PUBLIC)]

    with pytest.raises(UpstreamError) as late:
        await open_(ORIGIN + "/opds", Recorder(), slow)
    assert (late.value.status, late.value.detail) == (502, "catalog_unreachable")
    assert late.value.reason == "headers_deadline"


async def test_a_name_that_does_not_resolve_is_unreachable():
    async def fails(host, port):
        raise OSError("no such host")

    with pytest.raises(UpstreamError) as error:
        await open_(ORIGIN + "/opds", Recorder(), fails)
    assert (error.value.status, error.value.detail) == (502, "catalog_unreachable")


async def test_no_login_is_sent_when_the_catalog_has_none():
    upstream = Recorder()
    await (
        await open_(ORIGIN + "/opds", upstream, resolver([PUBLIC])[0], authorization=None)
    ).aclose()
    assert "authorization" not in upstream.requests[0].headers


async def test_httpx_does_not_log_the_catalog_address(caplog):
    caplog.set_level(logging.DEBUG)
    await (await open_(ORIGIN + "/opds/secret-key", Recorder(), resolver([PUBLIC])[0])).aclose()
    assert "secret-key" not in caplog.text and PUBLIC not in caplog.text
    # Other requests still log as before.
    async with httpx.AsyncClient(transport=httpx.MockTransport(Recorder())) as client:
        await client.get("https://other.example.org/logged")
    assert "/logged" in caplog.text


def test_rate_window_counts_per_account_and_resets_each_minute():
    now = [1000.0]
    window = RateWindow(2, clock=lambda: now[0])
    assert window.retry_after("a") is None and window.retry_after("a") is None
    assert window.retry_after("b") is None
    now[0] += 20
    assert window.retry_after("a") == 41
    now[0] += 40
    assert window.retry_after("a") is None


def test_fetch_slots_hold_per_account_and_in_all():
    slots = FetchSlots(per_account=2, total=3)
    assert slots.take("a") and slots.take("a") and not slots.take("a")
    assert slots.take("b") and not slots.take("c")
    slots.give("a")
    assert slots.take("c") and not slots.take("a")
    for account in ("a", "b", "c"):
        slots.give(account)
    assert slots.accounts == {}


def test_login_backoff_pauses_a_catalog_that_keeps_refusing():
    now = [0.0]
    backoff = LoginBackoff(after=3, pause_s=60, clock=lambda: now[0])
    for _ in range(2):
        backoff.answered("cat", "v1", login_rejected=True)
    assert not backoff.paused("cat", "v1")
    backoff.answered("cat", "v1", login_rejected=True)
    assert backoff.paused("cat", "v1")
    # A login saved again is a new revision: asked again at once.
    assert not backoff.paused("cat", "v2")
    now[0] += 61
    assert not backoff.paused("cat", "v1")
    # After the pause one more refusal pauses again; any other answer starts over.
    backoff.answered("cat", "v1", login_rejected=True)
    assert backoff.paused("cat", "v1")
    backoff.answered("cat", "v1", login_rejected=False)
    assert not backoff.paused("cat", "v1")
