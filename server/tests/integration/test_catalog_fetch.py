"""``GET /catalogs/v1/catalogs/{id}/fetch`` against a real catalog on a local socket.

The catalog listens on 127.0.0.1. The server always refuses loopback, so
these tests let that one address through wherever ``catalog_proxy_allow_private``
is on, as if it were a private address on the network; the defaults are
checked to refuse it.
"""

from __future__ import annotations

import asyncio
import base64
import gzip
import ipaddress
import logging
from uuid import uuid4

import httpx
import pytest
from sqlalchemy import update

from quire_server.api import catalogs as catalogs_api
from quire_server.core import catalog_proxy
from quire_server.db import session as db_session
from quire_server.db.models import AccountCatalog

from .test_catalogs_api import KEYS, PASSWORD, bearer, path

FEED = b'<feed xmlns="http://www.w3.org/2005/Atom"><title>Shelf</title></feed>'
LOGIN_PASSWORD = "proxy-login-password"  # pragma: allowlist secret
BASIC = "Basic " + base64.b64encode(f"reader:{LOGIN_PASSWORD}".encode()).decode()


LOOPBACK = ipaddress.ip_address("127.0.0.1")


@pytest.fixture(autouse=True)
def loopback_as_private(monkeypatch):
    real = catalog_proxy.address_refused

    def refused(address, allow_private):
        return not (allow_private and address == LOOPBACK) and real(address, allow_private)

    monkeypatch.setattr(catalog_proxy, "address_refused", refused)


class Catalog:
    """A tiny HTTP/1.1 server: one canned answer per path, every request recorded.

    A route can ``stall`` before its headers or after them (``"headers"`` or
    ``"body"``) until ``release`` is set, or ``"drip"`` its body a byte at a time.
    """

    def __init__(self) -> None:
        self.requests: list[tuple[str, dict[str, str]]] = []
        self.routes: dict[str, tuple[int, list[tuple[str, str]], bytes, str | None]] = {}
        self.server: asyncio.Server | None = None
        self.release = asyncio.Event()
        self.arrival = asyncio.Event()

    @property
    def origin(self) -> str:
        return f"http://127.0.0.1:{self.server.sockets[0].getsockname()[1]}"

    def route(self, path, body=FEED, status=200, headers=(), stall=None):
        self.routes[path] = (status, list(headers), body, stall)

    async def arrived(self, count):
        async with asyncio.timeout(10):
            while len(self.requests) < count:
                self.arrival.clear()
                await self.arrival.wait()

    async def __aenter__(self):
        self.server = await asyncio.start_server(self._serve, "127.0.0.1", 0)
        return self

    async def __aexit__(self, *exc):
        self.release.set()
        self.server.close()
        await self.server.wait_closed()

    async def _serve(self, reader, writer):
        try:
            head = (await reader.readuntil(b"\r\n\r\n")).decode("latin-1")
            line, *fields = head.split("\r\n")
            target = line.split(" ")[1]
            headers = {}
            for field in fields:
                if ":" in field:
                    name, value = field.split(":", 1)
                    headers[name.strip().lower()] = value.strip()
            self.requests.append((target, headers))
            self.arrival.set()
            status, extra, body, stall = self.routes.get(
                target, (404, [("Content-Type", "text/plain")], b"missing", None)
            )
            if stall == "headers":
                await self.release.wait()
            names = {name.lower() for name, _ in extra}
            if "content-type" not in names:
                extra = [*extra, ("Content-Type", "application/atom+xml;profile=opds-catalog")]
            # No Content-Length unless a route sets one: the body ends at close.
            lines = [f"HTTP/1.1 {status} X", "Connection: close"]
            lines += [f"{name}: {value}" for name, value in extra]
            writer.write(("\r\n".join(lines) + "\r\n\r\n").encode("latin-1"))
            await writer.drain()
            if stall == "body":
                await self.release.wait()
            if stall == "drip":
                for byte in body:
                    writer.write(bytes([byte]))
                    await writer.drain()
                    await asyncio.sleep(0.1)
            for start in range(0, len(body), 65536):
                writer.write(body[start : start + 65536])
                await writer.drain()
        except (asyncio.IncompleteReadError, ConnectionError):
            pass
        finally:
            writer.close()


def proxied(client_factory, **env):
    return client_factory(
        **{
            "auth_backend": "native",
            "skip_auth_overrides": True,
            "catalog_keys": KEYS,
            "catalog_allow_http": "true",
            "catalog_proxy_allow_private": "true",
        }
        | env
    )


async def saved(client, auth, url, login=True) -> str:
    catalog_id = uuid4()
    body = {"name": "Local", "kind": "opds", "url": url}
    if login:
        body |= {"username": "reader", "password": LOGIN_PASSWORD}
    response = await client.put(path(catalog_id), headers=auth, json=body)
    assert response.status_code == 200, response.text
    return path(catalog_id) + "/fetch"


async def test_the_saved_login_goes_upstream_and_only_allowed_headers_cross(
    client_factory, app, session, caplog
):
    caplog.set_level(logging.DEBUG)
    async with Catalog() as upstream, proxied(client_factory) as client:
        upstream.route(
            "/opds/key-in-path",
            headers=[
                ("Set-Cookie", "catalog=1"),
                ("ETag", '"v1"'),
                ("X-Upstream-Secret", "leak"),
                ("Access-Control-Allow-Origin", "*"),
            ],
        )
        auth = await bearer(app)
        fetch = await saved(client, auth, upstream.origin + "/opds/key-in-path")

        response = await client.get(
            fetch,
            headers=auth
            | {
                "Cookie": "quire-session=browser-cookie",
                "Accept": "application/atom+xml",
                "Range": "bytes=0-",
                "X-Custom": "1",
            },
        )
        assert response.status_code == 200, response.text
        assert response.content == FEED
        assert response.headers["content-type"].startswith("application/atom+xml")
        assert response.headers["content-location"] == upstream.origin + "/opds/key-in-path"
        assert response.headers["etag"] == '"v1"'
        assert response.headers["x-content-type-options"] == "nosniff"
        assert response.headers["content-security-policy"] == "sandbox; default-src 'none'"
        assert response.headers["cross-origin-resource-policy"] == "same-origin"
        assert response.headers["cache-control"] == "no-store"
        for name in ("set-cookie", "x-upstream-secret", "access-control-allow-origin"):
            assert name not in response.headers

        ((target, sent),) = upstream.requests
        assert target == "/opds/key-in-path"
        assert sent["authorization"] == BASIC
        assert sent["accept"] == "application/atom+xml" and sent["range"] == "bytes=0-"
        assert sent["user-agent"] == "Quire" and sent["accept-encoding"] == "identity"
        assert "cookie" not in sent and "x-custom" not in sent and "x-quire-account" not in sent

        # An explicit same-origin address, and one without a login.
        bare = await saved(client, auth, upstream.origin + "/opds/key-in-path", login=False)
        upstream.route("/opds/books?page=2")
        page = await client.get(
            bare, params={"url": upstream.origin + "/opds/books?page=2"}, headers=auth
        )
        assert page.status_code == 200, page.text
        assert upstream.requests[-1][0] == "/opds/books?page=2"
        assert "authorization" not in upstream.requests[-1][1]

    for secret in ("key-in-path", LOGIN_PASSWORD, "/opds/books"):
        assert secret not in caplog.text


async def test_the_target_must_be_on_the_saved_origin(client_factory, app, session):
    async with Catalog() as upstream, Catalog() as other, proxied(client_factory) as client:
        auth = await bearer(app)
        fetch = await saved(client, auth, upstream.origin + "/opds")
        for url, detail in (
            (other.origin + "/opds", "other_origin"),
            (upstream.origin.replace("http:", "https:") + "/opds", "other_origin"),
            ("http://localhost:" + upstream.origin.rsplit(":", 1)[1] + "/opds", "other_origin"),
            (upstream.origin.replace("//", "//reader:pw@") + "/opds", "credentials_in_url"),
            ("file:///etc/passwd", "invalid_url"),
        ):
            response = await client.get(fetch, params={"url": url}, headers=auth)
            assert (response.status_code, response.json()) == (422, {"detail": detail}), url
        assert upstream.requests == [] and other.requests == []


async def test_redirects_stay_on_the_origin(client_factory, app, session):
    async with Catalog() as upstream, Catalog() as other, proxied(client_factory) as client:
        upstream.route("/opds", status=302, headers=[("Location", "/opds/")])
        upstream.route("/opds/")
        upstream.route("/away", status=302, headers=[("Location", other.origin + "/opds")])
        auth = await bearer(app)
        fetch = await saved(client, auth, upstream.origin + "/opds")

        followed = await client.get(fetch, headers=auth)
        assert followed.status_code == 200 and followed.content == FEED
        assert followed.headers["content-location"] == upstream.origin + "/opds/"
        assert [sent["authorization"] for _, sent in upstream.requests] == [BASIC, BASIC]

        away = await client.get(fetch, params={"url": upstream.origin + "/away"}, headers=auth)
        assert (away.status_code, away.json()) == (502, {"detail": "catalog_redirect_refused"})
        assert other.requests == []


async def test_private_addresses_are_refused_by_default(client_factory, app, session):
    async with (
        Catalog() as upstream,
        proxied(client_factory, catalog_proxy_allow_private="false") as client,
    ):
        auth = await bearer(app)
        fetch = await saved(client, auth, upstream.origin + "/opds")
        response = await client.get(fetch, headers=auth)
        assert (response.status_code, response.json()) == (502, {"detail": "catalog_unreachable"})
        assert response.headers["cache-control"] == "no-store"
        assert upstream.requests == []


async def test_a_refused_name_answers_like_one_that_does_not_resolve(
    client_factory, app, session, monkeypatch, caplog
):
    caplog.set_level(logging.INFO)
    calls = []

    async def resolve(host, port):
        calls.append(host)
        if host == "missing.example.org":
            raise OSError("no such host")
        return [ipaddress.ip_address("10.1.2.3")]

    monkeypatch.setattr(catalog_proxy, "resolve_host", resolve)
    async with proxied(client_factory, catalog_proxy_allow_private="false") as client:
        auth = await bearer(app)
        internal = await saved(client, auth, "https://internal.example.org/opds")
        missing = await saved(client, auth, "https://missing.example.org/opds")
        answers = [await client.get(fetch, headers=auth) for fetch in (internal, missing)]
        assert [(a.status_code, a.json()) for a in answers] == [
            (502, {"detail": "catalog_unreachable"})
        ] * 2
        assert calls == ["internal.example.org", "missing.example.org"]
    internal_id = internal.split("/")[-2]
    assert f"id={internal_id} reason=address_refused" in caplog.text
    assert "internal.example.org" not in caplog.text


async def test_size_caps_hold_while_streaming(client_factory, app, session, monkeypatch):
    monkeypatch.setattr(catalogs_api, "FEED_LIMIT", 1000)
    monkeypatch.setattr(catalogs_api, "DOWNLOAD_LIMIT", 200_000)
    async with Catalog() as upstream, proxied(client_factory) as client:
        upstream.route("/feed", body=b"<feed>" + b"x" * 2000)
        upstream.route(
            "/declared",
            body=b"PK",
            headers=[("Content-Type", "application/epub+zip"), ("Content-Length", "300000")],
        )
        upstream.route(
            "/book.epub",
            body=b"PK\x03\x04" + b"0" * 150_000,
            headers=[("Content-Type", "application/epub+zip")],
        )
        upstream.route(
            "/huge.epub",
            body=b"PK\x03\x04" + b"0" * 400_000,
            headers=[("Content-Type", "application/epub+zip")],
        )
        auth = await bearer(app)
        fetch = await saved(client, auth, upstream.origin + "/feed")

        def at(name):
            return {"url": upstream.origin + name}

        feed = await client.get(fetch, headers=auth)
        assert (feed.status_code, feed.json()) == (502, {"detail": "catalog_too_large"})
        declared = await client.get(fetch, params=at("/declared"), headers=auth)
        assert (declared.status_code, declared.json()) == (502, {"detail": "catalog_too_large"})

        # Sent without a length: gathered first, so the browser gets one.
        book = await client.get(fetch, params=at("/book.epub"), headers=auth)
        assert book.status_code == 200 and len(book.content) == 150_004
        assert book.headers["content-type"] == "application/epub+zip"
        assert book.headers["content-disposition"] == "attachment"
        assert book.headers["content-length"] == "150004"

        # Over the cap before anything went out: an error, never a short book.
        huge = await client.get(fetch, params=at("/huge.epub"), headers=auth)
        assert (huge.status_code, huge.json()) == (502, {"detail": "catalog_too_large"})
        assert app.state.catalog_proxy_slots.accounts == {}


async def test_an_upstream_page_is_never_served_as_one(client_factory, app, session):
    async with Catalog() as upstream, proxied(client_factory) as client:
        script = b"<html><script>alert(document.cookie)</script></html>"
        upstream.route("/page", body=script, headers=[("Content-Type", "text/html")])
        upstream.route("/vector", body=b"<svg/>", headers=[("Content-Type", "image/svg+xml")])
        upstream.route(
            "/named.epub",
            body=b"PK",
            headers=[
                ("Content-Type", "application/epub+zip"),
                ("Content-Disposition", 'attachment; filename="book.epub"'),
                ("Content-Length", "2"),
            ],
        )
        upstream.route(
            "/inline.epub",
            body=b"PK",
            headers=[("Content-Type", "application/epub+zip"), ("Content-Disposition", "inline")],
        )
        auth = await bearer(app)
        fetch = await saved(client, auth, upstream.origin + "/page")

        for name in ("/page", "/vector"):
            response = await client.get(fetch, params={"url": upstream.origin + name}, headers=auth)
            assert response.status_code == 200
            assert response.headers["content-type"] == "application/octet-stream"
            assert response.headers["content-disposition"] == "attachment"
            assert response.headers["x-content-type-options"] == "nosniff"
            assert "sandbox" in response.headers["content-security-policy"]
        named = await client.get(
            fetch, params={"url": upstream.origin + "/named.epub"}, headers=auth
        )
        assert named.headers["content-disposition"] == "attachment; filename*=UTF-8''book.epub"
        assert named.headers["content-length"] == "2"
        inline = await client.get(
            fetch, params={"url": upstream.origin + "/inline.epub"}, headers=auth
        )
        assert inline.headers["content-disposition"] == "attachment"


async def test_upstream_failures_have_their_own_codes(client_factory, app, session):
    async with Catalog() as upstream, proxied(client_factory) as client:
        upstream.route("/denied", status=401, headers=[("WWW-Authenticate", "Basic")])
        upstream.route("/forbidden", status=403)
        upstream.route("/same", status=304, headers=[("ETag", '"v1"')])
        auth = await bearer(app)
        fetch = await saved(client, auth, upstream.origin + "/denied")

        async def get(name):
            return await client.get(fetch, params={"url": upstream.origin + name}, headers=auth)

        for name in ("/denied", "/forbidden"):
            response = await get(name)
            assert (response.status_code, response.json()) == (
                502,
                {"detail": "catalog_login_rejected"},
            )
            assert "www-authenticate" not in response.headers
        missing = await get("/missing")
        assert (missing.status_code, missing.json()) == (
            502,
            {"detail": "catalog_error", "status": 404},
        )
        same = await get("/same")
        assert same.status_code == 304 and same.headers["etag"] == '"v1"'

        port = upstream.origin.rsplit(":", 1)[1]
    # The catalog is gone: nothing listens on its port any more.
    async with proxied(client_factory) as client:
        auth = await bearer(app, "other@example.test")
        fetch = await saved(client, auth, f"http://127.0.0.1:{port}/opds")
        gone = await client.get(fetch, headers=auth)
        assert (gone.status_code, gone.json()) == (502, {"detail": "catalog_unreachable"})


async def test_another_accounts_catalog_and_an_unreadable_one(client_factory, app, session):
    async with Catalog() as upstream, proxied(client_factory) as client:
        alice = await bearer(app, "alice@example.test")
        bob = await bearer(app, "bob@example.test")
        fetch = await saved(client, alice, upstream.origin + "/opds")
        response = await client.get(fetch, headers=bob)
        assert (response.status_code, response.json()) == (404, {"detail": "not_found"})
        missing = await client.get(path(uuid4()) + "/fetch", headers=alice)
        assert missing.status_code == 404
        assert upstream.requests == []

        await session.execute(update(AccountCatalog).values(secret="k1:broken"))
        await session.commit()
        unreadable = await client.get(fetch, headers=alice)
        assert (unreadable.status_code, unreadable.json()) == (409, {"detail": "needs_login"})
        assert (await client.get(fetch)).status_code == 401


async def test_each_account_is_rate_limited(client_factory, app, session):
    async with (
        Catalog() as upstream,
        proxied(client_factory, catalog_proxy_rate_per_min=2) as client,
    ):
        upstream.route("/opds")
        alice = await bearer(app, "alice@example.test")
        bob = await bearer(app, "bob@example.test")
        fetch = await saved(client, alice, upstream.origin + "/opds")
        assert [(await client.get(fetch, headers=alice)).status_code for _ in range(2)] == [
            200,
            200,
        ]
        limited = await client.get(fetch, headers=alice)
        assert (limited.status_code, limited.json()) == (429, {"detail": "rate_limited"})
        assert 1 <= int(limited.headers["retry-after"]) <= 61
        assert limited.headers["cache-control"] == "no-store"
        assert len(upstream.requests) == 2
        # Bob has his own allowance.
        assert (await client.get(fetch, headers=bob)).status_code == 404


async def test_switched_off_it_is_a_404_and_bootstrap_says_so(
    client_factory, app, session, monkeypatch
):
    origin = "https://reader.example.test"
    monkeypatch.setenv("QUIRE_IDENTITY_PUBLIC_URL", origin)
    async with Catalog() as upstream:
        upstream.route("/opds")
        for enabled in (True, False):
            async with proxied(
                client_factory,
                catalog_proxy_enabled=str(enabled).lower(),
                web_native_auth_enabled="true",
            ) as client:
                auth = await bearer(app, f"{enabled}@example.test")
                fetch = await saved(client, auth, upstream.origin + "/opds")
                for headers in (auth, {}):
                    response = await client.get(fetch, headers=headers)
                    if enabled:
                        assert response.status_code in (200, 401)
                    else:
                        assert (response.status_code, response.json()) == (
                            404,
                            {"detail": "Not Found"},
                        )
                bootstrap = await client.get(
                    "/quire/v1/bootstrap", headers={"Host": "reader.example.test"}
                )
                assert bootstrap.json()["catalogProxy"] is enabled
                assert bootstrap.json()["catalogSync"] is True


async def test_slow_catalogs_hold_no_connection_and_stall_no_one(client_factory, app, session):
    async with Catalog() as upstream, proxied(client_factory) as client:
        upstream.route("/slow", stall="headers")
        alice = await bearer(app, "alice@example.test")
        carol = await bearer(app, "carol@example.test")
        bob = await bearer(app, "bob@example.test")
        alice_fetch = await saved(client, alice, upstream.origin + "/slow")
        carol_fetch = await saved(client, carol, upstream.origin + "/slow")
        # As many slow fetches as the database pool has connections.
        waiting = [asyncio.create_task(client.get(alice_fetch, headers=alice)) for _ in range(4)]
        waiting.append(asyncio.create_task(client.get(carol_fetch, headers=carol)))
        await upstream.arrived(5)

        assert db_session._engine.pool.checkedout() == 0
        async with asyncio.timeout(5):
            listed = await client.get("/catalogs/v1/catalogs", headers=bob)
        assert listed.status_code == 200
        # Alice already has 4 fetches in flight.
        busy = await client.get(alice_fetch, headers=alice)
        assert (busy.status_code, busy.json()) == (429, {"detail": "too_many_fetches"})
        assert busy.headers["retry-after"] == "1"

        upstream.release.set()
        assert [(await task).status_code for task in waiting] == [200] * 5
        # Every slot came back.
        assert (await client.get(alice_fetch, headers=alice)).status_code == 200
        assert app.state.catalog_proxy_slots.accounts == {}


async def test_the_server_caps_fetches_in_flight(client_factory, app, session):
    async with (
        Catalog() as upstream,
        proxied(client_factory, catalog_proxy_max_concurrent=1) as client,
    ):
        upstream.route("/slow", stall="headers")
        alice = await bearer(app, "alice@example.test")
        bob = await bearer(app, "bob@example.test")
        alice_fetch = await saved(client, alice, upstream.origin + "/slow")
        bob_fetch = await saved(client, bob, upstream.origin + "/slow")
        first = asyncio.create_task(client.get(alice_fetch, headers=alice))
        await upstream.arrived(1)
        busy = await client.get(bob_fetch, headers=bob)
        assert (busy.status_code, busy.json()) == (429, {"detail": "too_many_fetches"})
        upstream.release.set()
        assert (await first).status_code == 200


async def test_slow_answers_meet_deadlines(client_factory, app, session, monkeypatch):
    monkeypatch.setattr(catalogs_api, "FEED_DEADLINE_S", 0.5)
    monkeypatch.setattr(
        catalog_proxy, "TIMEOUT", httpx.Timeout(connect=5.0, read=0.3, write=5.0, pool=5.0)
    )
    async with Catalog() as upstream, proxied(client_factory) as client:
        # Never quiet for long, never done.
        upstream.route("/feed", stall="drip")
        upstream.route(
            "/book.epub", headers=[("Content-Type", "application/epub+zip")], stall="body"
        )
        upstream.route(
            "/framed.epub",
            headers=[("Content-Type", "application/epub+zip"), ("Content-Length", "10")],
            stall="body",
        )
        auth = await bearer(app)
        fetch = await saved(client, auth, upstream.origin + "/feed")
        feed = await client.get(fetch, headers=auth)
        assert (feed.status_code, feed.json()) == (502, {"detail": "catalog_unreachable"})
        # A download has no total limit, but a catalog that goes quiet ends it:
        # with an error while it is still being gathered, else by a cut stream.
        book = await client.get(fetch, params={"url": upstream.origin + "/book.epub"}, headers=auth)
        assert (book.status_code, book.json()) == (502, {"detail": "catalog_unreachable"})
        with pytest.raises(httpx.ReadTimeout):
            await client.get(fetch, params={"url": upstream.origin + "/framed.epub"}, headers=auth)
        assert app.state.catalog_proxy_slots.accounts == {}


async def test_a_compressed_answer_is_refused_not_unpacked(client_factory, app, session):
    packed = gzip.compress(b"<feed>" + b" " * 4_000_000 + b"</feed>")
    async with Catalog() as upstream, proxied(client_factory) as client:
        upstream.route("/feed", body=packed, headers=[("Content-Encoding", "gzip")])
        upstream.route(
            "/book.epub",
            body=packed,
            headers=[("Content-Type", "application/epub+zip"), ("Content-Encoding", "gzip")],
        )
        auth = await bearer(app)
        fetch = await saved(client, auth, upstream.origin + "/feed")
        for answer in (
            await client.get(fetch, headers=auth),
            await client.get(fetch, params={"url": upstream.origin + "/book.epub"}, headers=auth),
        ):
            assert (answer.status_code, answer.json()) == (502, {"detail": "catalog_compressed"})


def raw(text: str) -> str:
    """``text`` as UTF-8 bytes on the wire (the test catalog writes latin-1)."""
    return text.encode().decode("latin-1")


async def test_odd_headers_are_dropped_not_fatal(client_factory, app, session):
    async with Catalog() as upstream, proxied(client_factory) as client:
        upstream.route(
            "/feed",
            headers=[("ETag", raw('"€"')), ("Last-Modified", "Tue, 1 Sep 2026 10:00:00 GMT")],
        )
        upstream.route(
            "/book.epub",
            body=b"PK\x03\x04",
            headers=[
                ("Content-Type", "application/epub+zip"),
                ("Content-Disposition", raw('inline; filename="Café/€.epub"')),
            ],
        )
        upstream.route(
            "/chunked.epub",
            body=b"4\r\nPK\x03\x04\r\n0\r\n\r\n",
            headers=[
                ("Content-Type", "application/epub+zip"),
                ("Transfer-Encoding", "chunked"),
                ("Content-Length", "999"),
            ],
        )
        auth = await bearer(app)
        fetch = await saved(client, auth, upstream.origin + "/feed")

        feed = await client.get(fetch, headers=auth | {"Accept-Language": "fr-€".encode()})
        assert feed.status_code == 200, feed.text
        assert "etag" not in feed.headers and feed.headers["last-modified"].startswith("Tue")
        assert "accept-language" not in upstream.requests[-1][1]

        book = await client.get(fetch, params={"url": upstream.origin + "/book.epub"}, headers=auth)
        assert book.status_code == 200 and book.content == b"PK\x03\x04"
        assert book.headers["content-disposition"] == (
            "attachment; filename*=UTF-8''Caf%C3%A9%E2%82%AC.epub"
        )
        chunked = await client.get(
            fetch, params={"url": upstream.origin + "/chunked.epub"}, headers=auth
        )
        assert chunked.content == b"PK\x03\x04" and chunked.headers["content-length"] == "4"


async def test_a_catalog_that_keeps_refusing_its_login_is_left_alone(client_factory, app, session):
    async with Catalog() as upstream, proxied(client_factory) as client:
        upstream.route("/opds", status=401)
        auth = await bearer(app)
        fetch = await saved(client, auth, upstream.origin + "/opds")
        answers = [(await client.get(fetch, headers=auth)).json() for _ in range(6)]
        assert answers == [{"detail": "catalog_login_rejected"}] * 6
        assert len(upstream.requests) == 5

        # Saving the login again asks the catalog again.
        body = {"name": "Local", "kind": "opds", "url": upstream.origin + "/opds"}
        body |= {"username": "reader", "password": "a-new-password"}
        assert (await client.put(fetch.removesuffix("/fetch"), headers=auth, json=body)).is_success
        await client.get(fetch, headers=auth)
        assert len(upstream.requests) == 6


async def test_a_browser_session_reads_no_login_and_names_its_account(
    client_factory, app, session, monkeypatch
):
    monkeypatch.setenv("QUIRE_IDENTITY_PUBLIC_URL", "https://reader.example.test")
    async with (
        Catalog() as upstream,
        proxied(client_factory, web_native_auth_enabled="true") as client,
    ):
        upstream.route("/opds")
        auth = await bearer(app)
        fetch = await saved(client, auth, upstream.origin + "/opds")
        token, _ = await app.state.auth_backend.login(
            email="reader@example.test", password=PASSWORD
        )
        browser = {"Host": "reader.example.test", "Cookie": f"__Host-quire-session={token}"}
        account = (await client.get("/auth/v1/me", headers=auth)).json()["user"]["id"]
        named = browser | {"X-Quire-Account": account.encode().hex()}

        secret = fetch.removesuffix("/fetch") + "/secret"
        refused = await client.get(secret, headers=named)
        assert (refused.status_code, refused.json()) == (403, {"detail": "token_required"})
        assert (await client.get(secret, headers=auth)).status_code == 200

        unnamed = await client.get(fetch, headers=browser)
        assert (unnamed.status_code, unnamed.json()) == (
            403,
            {"detail": "account_header_required"},
        )
        assert (await client.get(fetch, headers=named)).status_code == 200
        other = browser | {"X-Quire-Account": b"native:999".hex()}
        assert (await client.get(fetch, headers=other)).status_code == 409
        assert len(upstream.requests) == 1
