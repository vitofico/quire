"""Fetch a saved catalog's pages for Quire Web, which cannot reach them itself.

A browser cannot read most third-party catalogs (they send no CORS headers),
and the saved login must not reach the page. So the server fetches for it,
which makes this a request forgery surface: a reader chooses where the server
connects. The rules, in the order a request meets them:

* The route checks that the target is on the saved catalog's own origin.
* ``open_upstream`` resolves the host once, refuses the whole answer when any
  address is not allowed (``address_refused``), and connects to an address it
  checked. The URL carries that address, while the ``Host`` header and the
  TLS name (SNI and certificate check) stay the real host name, so a DNS
  answer that changes between the check and the connection cannot win.
* Redirects are followed by hand, each hop re-checked for origin and address.
  The login therefore never leaves the catalog's origin.
* Response headers must arrive within ``HEADERS_DEADLINE_S``. The route
  relays the body as raw bytes and refuses a compressed one, so its size
  caps count what actually crosses the wire.

Nothing here logs a URL: httpx and httpcore log each request's address, so
their records are dropped while a catalog request is in flight.
"""

from __future__ import annotations

import asyncio
import ipaddress
import logging
import socket
import time
from collections import OrderedDict
from collections.abc import Awaitable, Callable, Mapping
from contextvars import ContextVar
from dataclasses import dataclass
from urllib.parse import urljoin

import httpx

IPAddress = ipaddress.IPv4Address | ipaddress.IPv6Address
Resolver = Callable[[str, int], Awaitable[list[IPAddress]]]

MAX_REDIRECTS = 3
# Of the addresses a name resolves to (all of them checked), the ones tried.
MAX_ADDRESSES = 2
# ``read`` is also the idle rule for downloads: a catalog that sends nothing
# for that long ends the answer.
TIMEOUT = httpx.Timeout(connect=10.0, read=30.0, write=10.0, pool=10.0)
RESOLVE_TIMEOUT_S = 10.0
# From the first DNS query to the last response header, redirects included.
HEADERS_DEADLINE_S = 20.0
_REDIRECTS = {301, 302, 303, 307, 308}
# What ``catalog_proxy_allow_private`` opens: RFC 1918, CGNAT and ULA.
_OPT_IN = tuple(
    ipaddress.ip_network(network)
    for network in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10", "fc00::/7")
)
# Never a catalog, whatever the operator allows: IPv6 site-local, the
# IPv4-compatible block, and cloud metadata services that sit outside the
# link-local range (Azure; Alibaba in CGNAT; AWS's IPv6 one in ULA).
_ALWAYS_REFUSED = (ipaddress.ip_network("fec0::/10"), ipaddress.ip_network("::/96"))
_METADATA = frozenset(
    ipaddress.ip_address(address)
    for address in (
        "169.254.169.254",
        "169.254.170.2",
        "168.63.129.16",
        "100.100.100.200",
        "fd00:ec2::254",
    )
)
_NAT64 = ipaddress.ip_network("64:ff9b::/96")

_quiet = ContextVar("catalog_proxy_quiet", default=False)


def _not_quiet(record: logging.LogRecord) -> bool:
    return not _quiet.get()


for _name in ("httpx", "httpcore.connection", "httpcore.http11", "httpcore.connection_pool"):
    logging.getLogger(_name).addFilter(_not_quiet)


class UpstreamError(Exception):
    """A request the route answers itself: ``status`` and a public ``detail``.

    ``reason`` is what the server logs; it differs from ``detail`` only where
    the public answer must not tell a reader more (a refused address reads
    as an unreachable one, so names cannot be probed for internal answers).
    """

    def __init__(self, status: int, detail: str, reason: str | None = None) -> None:
        super().__init__(detail)
        self.status, self.detail, self.reason = status, detail, reason or detail


def address_refused(address: IPAddress, allow_private: bool) -> bool:
    """Whether the server must not connect to ``address``.

    Allowed: globally routable addresses, and with ``allow_private`` also
    RFC 1918 (10/8, 172.16/12, 192.168/16), CGNAT (100.64/10) and ULA
    (fc00::/7). Everything else is refused, loopback and link-local (and so
    every metadata service on it) included. So are IPv4-mapped, IPv4-compatible,
    site-local and scoped IPv6, multicast, and the metadata services listed
    above. A NAT64 address is judged by the IPv4 address it carries.
    """
    if address.version == 6:
        if address.ipv4_mapped is not None or address.scope_id is not None:
            return True
        if address in _NAT64:
            return address_refused(ipaddress.IPv4Address(int(address) & 0xFFFFFFFF), allow_private)
    if (
        address.is_multicast
        or address.is_unspecified
        # 240.0.0.0/4 and broadcast. IPv6's "reserved" ::/8 holds loopback.
        or (address.version == 4 and address.is_reserved)
        or address in _METADATA
        or any(
            address in network for network in _ALWAYS_REFUSED if network.version == address.version
        )
    ):
        return True
    if address.is_global:
        return False
    return not (allow_private and any(address in network for network in _OPT_IN))


async def resolve_host(host: str, port: int) -> list[IPAddress]:
    loop = asyncio.get_running_loop()
    answers = await asyncio.wait_for(
        loop.getaddrinfo(host, port, type=socket.SOCK_STREAM), RESOLVE_TIMEOUT_S
    )
    addresses: list[IPAddress] = []
    for *_, sockaddr in answers:
        address = ipaddress.ip_address(sockaddr[0])
        if address not in addresses:
            addresses.append(address)
    return addresses


async def _addresses(
    url: httpx.URL, allow_private: bool, resolver: Resolver
) -> tuple[list[IPAddress], bool]:
    """The checked addresses for ``url``'s host, and whether the host is a name."""
    host = url.raw_host.decode("ascii")
    try:
        addresses, named = [ipaddress.ip_address(host)], False
    except ValueError:
        named = True
        try:
            addresses = await resolver(host, url.port or (443 if url.scheme == "https" else 80))
        except (OSError, TimeoutError, UnicodeError):
            addresses = []
    if not addresses:
        raise UpstreamError(502, "catalog_unreachable")
    # One refused address refuses the name: the system resolver could pick it.
    if any(address_refused(address, allow_private) for address in addresses):
        raise UpstreamError(502, "catalog_unreachable", "address_refused")
    return addresses, named


@dataclass
class Upstream:
    response: httpx.Response
    url: str
    client: httpx.AsyncClient

    async def aclose(self) -> None:
        token = _quiet.set(True)
        try:
            await self.response.aclose()
            await self.client.aclose()
        finally:
            _quiet.reset(token)


async def open_upstream(
    url: str,
    *,
    same_origin: Callable[[str], bool],
    headers: Mapping[str, str],
    authorization: str | None,
    allow_private: bool,
    resolver: Resolver | None = None,
    transport: httpx.AsyncBaseTransport | None = None,
) -> Upstream:
    """GET ``url`` from a checked address; the caller must ``aclose`` the result.

    ``same_origin`` decides whether a redirect target may be followed (and
    so receive ``authorization``). Raises ``UpstreamError`` for a refused
    address or redirect, or a catalog that cannot be reached in time.
    """
    resolver = resolver or resolve_host
    client = httpx.AsyncClient(
        transport=transport, timeout=TIMEOUT, trust_env=False, follow_redirects=False
    )
    token = _quiet.set(True)
    try:
        try:
            async with asyncio.timeout(HEADERS_DEADLINE_S):
                return await _follow(
                    client, url, same_origin, headers, authorization, allow_private, resolver
                )
        except TimeoutError:
            raise UpstreamError(502, "catalog_unreachable", "headers_deadline") from None
    except BaseException:
        await client.aclose()
        raise
    finally:
        _quiet.reset(token)


async def _follow(
    client: httpx.AsyncClient,
    url: str,
    same_origin: Callable[[str], bool],
    headers: Mapping[str, str],
    authorization: str | None,
    allow_private: bool,
    resolver: Resolver,
) -> Upstream:
    for _ in range(MAX_REDIRECTS + 1):
        response = await _send(client, url, headers, authorization, allow_private, resolver)
        location = response.headers.get("location")
        if response.status_code not in _REDIRECTS or location is None:
            return Upstream(response, url, client)
        await response.aclose()
        try:
            url = str(httpx.URL(urljoin(url, location)).copy_with(fragment=None))
        except (httpx.InvalidURL, ValueError):
            raise UpstreamError(502, "catalog_redirect_refused") from None
        if not same_origin(url):
            raise UpstreamError(502, "catalog_redirect_refused")
    raise UpstreamError(502, "catalog_redirect_refused")


async def _send(
    client: httpx.AsyncClient,
    url: str,
    headers: Mapping[str, str],
    authorization: str | None,
    allow_private: bool,
    resolver: Resolver,
) -> httpx.Response:
    target = httpx.URL(url)
    addresses, named = await _addresses(target, allow_private, resolver)
    sent = {
        **headers,
        "Host": target.netloc.decode("ascii"),
        "User-Agent": "Quire",
        "Accept-Encoding": "identity",
    }
    if authorization:
        sent["Authorization"] = authorization
    # The TLS name is the real host; the connection goes to the checked address.
    extensions = {"sni_hostname": target.raw_host.decode("ascii")} if named else {}
    for address in addresses[:MAX_ADDRESSES]:
        request = client.build_request(
            "GET",
            target.copy_with(host=str(address)) if named else target,
            headers=sent,
            extensions=extensions,
        )
        try:
            return await client.send(request, stream=True)
        except (httpx.ConnectError, httpx.ConnectTimeout):
            # Try the next address, as a browser would (an IPv6 answer on a
            # server without an IPv6 route, for example).
            continue
        except httpx.HTTPError:
            raise UpstreamError(502, "catalog_unreachable") from None
    raise UpstreamError(502, "catalog_unreachable")


class FetchSlots:
    """At most ``per_account`` fetches in flight for one account, ``total`` in all."""

    def __init__(self, per_account: int, total: int) -> None:
        self.per_account, self.total = per_account, total
        self.accounts: dict[str, int] = {}

    def take(self, account: str) -> bool:
        held = self.accounts.get(account, 0)
        if held >= self.per_account or sum(self.accounts.values()) >= self.total:
            return False
        self.accounts[account] = held + 1
        return True

    def give(self, account: str) -> None:
        held = self.accounts.pop(account, 0) - 1
        if held > 0:
            self.accounts[account] = held


class LoginBackoff:
    """Stop asking a catalog that keeps refusing its saved login.

    After ``after`` refusals in a row, fetches for that catalog are refused
    for ``pause_s`` seconds, or until its login is saved again (a new
    ``revision``, the row's ``updated_at``). Any other answer starts over.
    """

    def __init__(
        self, after: int = 5, pause_s: float = 300.0, clock: Callable[[], float] = time.monotonic
    ) -> None:
        self.after, self.pause_s, self.clock = after, pause_s, clock
        self.catalogs: OrderedDict[object, tuple[object, int, float]] = OrderedDict()

    def paused(self, catalog: object, revision: object) -> bool:
        entry = self.catalogs.get(catalog)
        if entry is None or entry[0] != revision or entry[1] < self.after:
            return False
        if self.clock() >= entry[2]:
            # The pause is over: the next refusal pauses again.
            self.catalogs[catalog] = (revision, self.after - 1, 0.0)
            return False
        return True

    def answered(self, catalog: object, revision: object, login_rejected: bool) -> None:
        if not login_rejected:
            self.catalogs.pop(catalog, None)
            return
        entry = self.catalogs.get(catalog)
        count = entry[1] + 1 if entry is not None and entry[0] == revision else 1
        self.catalogs[catalog] = (revision, count, self.clock() + self.pause_s)
        self.catalogs.move_to_end(catalog)
        while len(self.catalogs) > 10_000:
            self.catalogs.popitem(last=False)


class RateWindow:
    """At most ``per_minute`` requests per account in each one-minute window."""

    def __init__(self, per_minute: int, clock: Callable[[], float] = time.monotonic) -> None:
        self.per_minute, self.clock = per_minute, clock
        self.accounts: OrderedDict[str, tuple[float, int]] = OrderedDict()

    def retry_after(self, account: str) -> int | None:
        """Count one request; ``None`` when allowed, else seconds until the next window."""
        now = self.clock()
        start, count = self.accounts.get(account, (now, 0))
        if now - start >= 60:
            start, count = now, 0
        if count >= self.per_minute:
            return max(1, int(60 - (now - start)) + 1)
        self.accounts[account] = (start, count + 1)
        self.accounts.move_to_end(account)
        while len(self.accounts) > 10_000:
            self.accounts.popitem(last=False)
        return None
