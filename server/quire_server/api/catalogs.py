"""``/catalogs/v1``: the book catalogs saved to a Quire account.

A catalog is an address plus an optional login. Both are sealed together in
``account_catalogs.secret`` (``core.catalog_secrets``), because some catalogs
(Kavita) put an account key in the URL path. The list shows only ``host``,
which is scheme, host and a non-default port.

Native Quire accounts only: a Calibre Basic login has no account to keep the
list on. Every route is a 404 while catalog sync is off (no key), and rows are
keyed by the account's storage user id, so another account's catalog looks
exactly like a missing one. Logs name a catalog by id only.

``/fetch`` lets Quire Web browse a saved catalog: the server makes the request
with the saved login and relays the answer (rules in ``core.catalog_proxy``).
"""

from __future__ import annotations

import asyncio
import base64
import hashlib
import ipaddress
import json
import logging
import re
import tempfile
from collections.abc import Awaitable, Callable, Mapping
from datetime import UTC, datetime
from typing import Annotated, Literal
from urllib.parse import quote, unquote, urlsplit
from uuid import UUID

import httpx
import idna
from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request, Response, status
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import delete, func, select, text
from sqlalchemy.ext.asyncio import AsyncSession

from quire_server.api.no_store import NoStoreRoute
from quire_server.config import Settings, get_settings
from quire_server.core import catalog_proxy
from quire_server.core.auth import current_auth_user
from quire_server.core.auth_backend import AuthUser
from quire_server.core.catalog_secrets import CatalogSecretError, CatalogSecrets
from quire_server.db.models import AccountCatalog
from quire_server.db.session import get_session

logger = logging.getLogger(__name__)

MAX_CATALOGS = 20
_HOST_NAME = re.compile(r"[a-z0-9.-]+")


def _catalog_secrets(request: Request) -> CatalogSecrets:
    secrets = request.app.state.catalog_secrets
    if secrets is None:
        # The same answer as a path that was never mounted.
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Not Found")
    return secrets


async def _account(user: Annotated[AuthUser, Depends(current_auth_user)]) -> str:
    if user.backend != "native":
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "native_login_required")
    return user.storage_user_id


router = APIRouter(
    tags=["catalogs"], route_class=NoStoreRoute, dependencies=[Depends(_catalog_secrets)]
)

Secrets = Annotated[CatalogSecrets, Depends(_catalog_secrets)]
Account = Annotated[str, Depends(_account)]
Session = Annotated[AsyncSession, Depends(get_session)]


class CatalogPutBody(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str = Field(min_length=1, max_length=60)
    kind: Literal["calibre", "opds"]
    url: str = Field(min_length=1, max_length=2048)
    username: str | None = Field(None, max_length=256)
    password: str | None = Field(None, max_length=1024)
    # Opt-in, so older apps keep their behaviour: a new entry whose address the
    # account already holds is refused with that entry's id instead of saved twice.
    unique_url: bool = False


class CatalogOrderBody(BaseModel):
    model_config = ConfigDict(extra="forbid")

    ids: list[UUID] = Field(max_length=MAX_CATALOGS)


def open_secret(
    secrets: CatalogSecrets | None, user_id: str, catalog_id: UUID, sealed: str
) -> dict | None:
    """The row's ``{url, username, password}``, or ``None`` when it cannot be read."""
    if secrets is None:
        return None
    try:
        return secrets.decrypt(user_id, catalog_id, sealed)
    except CatalogSecretError:
        return None


def exported_catalog(secrets: CatalogSecrets | None, row: Mapping) -> dict:
    """An account-export row: the readable address and login instead of the ciphertext."""
    exported = dict(row)
    secret = open_secret(secrets, exported["user_id"], exported["id"], exported.pop("secret"))
    if secret is None:
        exported["secret_unreadable"] = True
    else:
        exported.update(secret)
    return exported


def _checked_url(raw: str, allow_http: bool) -> tuple[str, str]:
    """The trimmed address and its ``host``; never the path, which may hold a key.

    The host must be an IPv4 address, a bracketed IPv6 address or a name valid
    under IDNA 2008, kept in its ASCII form, so no other part of the address
    can reach the plain-text ``host`` column.
    """
    url = raw.strip()
    try:
        parts = urlsplit(url)
        port = parts.port
    except ValueError:
        raise HTTPException(422, "invalid_url") from None
    scheme = parts.scheme.lower()
    if (
        scheme not in {"http", "https"}
        or not parts.hostname
        or "\\" in url
        or any(char.isspace() or ord(char) < 32 or ord(char) == 127 for char in url)
    ):
        raise HTTPException(422, "invalid_url")
    if scheme == "http" and not allow_http:
        raise HTTPException(422, "https_required")
    if "@" in parts.netloc:
        raise HTTPException(422, "credentials_in_url")
    host = _checked_host(parts.hostname, bracketed="[" in parts.netloc)
    if port is not None and port != {"http": 80, "https": 443}[scheme]:
        host += f":{port}"
    return url, f"{scheme}://{host}"


def _checked_host(hostname: str, bracketed: bool) -> str:
    try:
        if bracketed:
            address = ipaddress.ip_address(hostname)
            if address.version == 6 and address.scope_id is None:
                return f"[{address}]"
        else:
            name = idna.encode(hostname, uts46=False).decode("ascii")
            if _HOST_NAME.fullmatch(name):
                return name
    except ValueError:
        # From ipaddress, and idna's IDNAError (a UnicodeError).
        pass
    raise HTTPException(422, "invalid_url")


def _origin(url: str, allow_http: bool = True) -> str | None:
    try:
        return _checked_url(url, allow_http)[1]
    except HTTPException:
        return None


def _item(secrets: CatalogSecrets, row: AccountCatalog) -> dict:
    secret = open_secret(secrets, row.user_id, row.id, row.secret)
    return {
        "id": str(row.id),
        "name": row.name,
        "kind": row.kind,
        "host": row.host,
        "has_login": bool(secret and secret.get("username")),
        "needs_login": secret is None,
        "position": row.position,
        "updated_at": row.updated_at.isoformat(),
    }


async def _lock(session: AsyncSession, account: str) -> None:
    """Serialize one account's writes, so the limit and the order hold under races."""
    await session.execute(
        text("SELECT pg_advisory_xact_lock(hashtextextended(:scope, 0))"),
        {"scope": "quire:catalogs:" + account},
    )


async def _list(
    secrets: CatalogSecrets, session: AsyncSession, account: str, if_none_match: str | None
) -> Response:
    rows = await session.scalars(
        select(AccountCatalog)
        .where(AccountCatalog.user_id == account)
        .order_by(AccountCatalog.position, AccountCatalog.id)
    )
    items = [_item(secrets, row) for row in rows]
    # Derived from everything the list shows, so any write (and a key that
    # starts or stops reading a row) gives a new revision.
    revision = hashlib.sha256(json.dumps(items).encode()).hexdigest()[:32]
    etag = f'"{revision}"'
    if if_none_match is not None:
        tags = {tag.strip().removeprefix("W/").strip('"') for tag in if_none_match.split(",")}
        if revision in tags or "*" in tags:
            return Response(status_code=status.HTTP_304_NOT_MODIFIED, headers={"ETag": etag})
    return JSONResponse({"revision": revision, "catalogs": items}, headers={"ETag": etag})


@router.get("/catalogs")
async def list_catalogs(
    secrets: Secrets,
    account: Account,
    session: Session,
    if_none_match: Annotated[str | None, Header()] = None,
) -> Response:
    return await _list(secrets, session, account, if_none_match)


@router.get("/catalogs/{catalog_id}/secret")
async def get_secret(
    catalog_id: UUID, request: Request, secrets: Secrets, account: Account, session: Session
) -> dict:
    # For apps that sign in with a token. A browser session never gets the
    # login: any script on Quire's origin could use that session, and the
    # page browses saved catalogs through ``/fetch`` without it.
    if not request.headers.get("authorization"):
        raise HTTPException(status.HTTP_403_FORBIDDEN, "token_required")
    row = await session.get(AccountCatalog, (account, catalog_id))
    if row is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "not_found")
    secret = open_secret(secrets, account, catalog_id, row.secret)
    if secret is None:
        logger.warning("event=catalogs.secret_unreadable id=%s", catalog_id)
        raise HTTPException(status.HTTP_409_CONFLICT, "needs_login")
    return {key: secret.get(key) for key in ("url", "username", "password")}


@router.put("/catalogs/{catalog_id}", response_model=None)
async def put_catalog(
    catalog_id: UUID,
    body: CatalogPutBody,
    secrets: Secrets,
    account: Account,
    session: Session,
    settings: Annotated[Settings, Depends(get_settings)],
) -> dict | JSONResponse:
    name = body.name.strip()
    if not name:
        raise HTTPException(422, "invalid_name")
    url, host = _checked_url(body.url, settings.catalog_allow_http)
    # As on the phone, a blank username or password counts as absent.
    username = body.username if body.username and body.username.strip() else None
    password = body.password if body.password and body.password.strip() else None

    await _lock(session, account)
    row = await session.get(AccountCatalog, (account, catalog_id))
    stored = open_secret(secrets, account, catalog_id, row.secret) if row is not None else None
    if row is not None and username is not None and password is None:
        # Omitting the password keeps the stored one, which must be readable.
        # It never follows the address to another scheme, host or port.
        if stored is None:
            raise HTTPException(status.HTTP_409_CONFLICT, "needs_login")
        if _origin(stored["url"]) == host:
            password = stored.get("password")
    if (username is None) != (password is None):
        raise HTTPException(422, "login_incomplete")
    secret = {"url": url, "username": username, "password": password}

    if row is None and body.unique_url:
        # Only rows on the same host can hold the address, so few secrets are opened.
        for other in await session.scalars(
            select(AccountCatalog).where(
                AccountCatalog.user_id == account, AccountCatalog.host == host
            )
        ):
            held = open_secret(secrets, account, other.id, other.secret)
            if held is not None and held["url"] == url:
                return JSONResponse(
                    {"detail": "catalog_exists", "id": str(other.id)},
                    status_code=status.HTTP_409_CONFLICT,
                )
    if row is None:
        count, last = (
            await session.execute(
                select(func.count(), func.max(AccountCatalog.position)).where(
                    AccountCatalog.user_id == account
                )
            )
        ).one()
        if count >= MAX_CATALOGS:
            raise HTTPException(status.HTTP_409_CONFLICT, "catalog_limit_reached")
        row = AccountCatalog(
            user_id=account, id=catalog_id, position=0 if last is None else last + 1
        )
        session.add(row)
    elif (row.name, row.kind, stored) == (name, body.kind, secret):
        # A retried save changes nothing visible, not even the revision, but
        # a row sealed with an older key moves to the current one.
        if not secrets.sealed_with_current_key(row.secret):
            row.secret = secrets.encrypt(account, catalog_id, secret)
            await session.commit()
            logger.info("event=catalogs.resealed id=%s", catalog_id)
        return _item(secrets, row)
    row.name, row.kind, row.host = name, body.kind, host
    row.secret = secrets.encrypt(account, catalog_id, secret)
    row.updated_at = datetime.now(UTC)
    await session.commit()
    logger.info("event=catalogs.saved id=%s", catalog_id)
    return _item(secrets, row)


@router.delete("/catalogs/{catalog_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_catalog(catalog_id: UUID, account: Account, session: Session) -> Response:
    await _lock(session, account)
    await session.execute(
        delete(AccountCatalog).where(
            AccountCatalog.user_id == account, AccountCatalog.id == catalog_id
        )
    )
    await session.commit()
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.put("/order")
async def put_order(
    body: CatalogOrderBody, secrets: Secrets, account: Account, session: Session
) -> Response:
    await _lock(session, account)
    rows = {
        row.id: row
        for row in await session.scalars(
            select(AccountCatalog).where(AccountCatalog.user_id == account)
        )
    }
    if len(body.ids) != len(rows) or set(body.ids) != set(rows):
        raise HTTPException(status.HTTP_409_CONFLICT, "order_mismatch")
    now = datetime.now(UTC)
    for position, catalog_id in enumerate(body.ids):
        row = rows[catalog_id]
        if row.position != position:
            row.position, row.updated_at = position, now
    await session.commit()
    return await _list(secrets, session, account, None)


# What the browser may ask the catalog for, and what of the answer it sees.
# Nothing else crosses: not the browser's cookies or Authorization, not the
# catalog's Set-Cookie or Content-Disposition (the server writes its own).
_FORWARDED_REQUEST = (
    "accept",
    "accept-language",
    "range",
    "if-range",
    "if-none-match",
    "if-modified-since",
)
_FORWARDED_RESPONSE = ("etag", "last-modified", "accept-ranges", "content-range")
# Feeds and covers are read whole (and capped at FEED_LIMIT) before the
# browser gets them; anything else is a download, capped at DOWNLOAD_LIMIT.
_FEED_TYPES = frozenset(
    {
        "application/atom+xml",
        "application/xml",
        "text/xml",
        "application/opensearchdescription+xml",
        "image/avif",
        "image/webp",
        "image/png",
        "image/jpeg",
        "image/gif",
    }
)
_DOWNLOAD_TYPES = frozenset(
    {"application/epub+zip", "application/pdf", "application/zip", "application/octet-stream"}
)
FEED_LIMIT = 5 * 1024 * 1024
DOWNLOAD_LIMIT = 200 * 1024 * 1024
# A feed or cover must arrive whole within this; a download instead ends when
# the catalog goes quiet (``catalog_proxy.TIMEOUT.read``).
FEED_DEADLINE_S = 60.0
# A download the catalog sends without a length is gathered here first (in
# memory up to this size, then on disk).
_SPOOL_MEMORY = 1024 * 1024
# The body is another site's content served from Quire's origin: it must never
# run as a page here, whatever it claims to be.
_UNTRUSTED_BODY = {
    "X-Content-Type-Options": "nosniff",
    "Content-Security-Policy": "sandbox; default-src 'none'",
    "Cross-Origin-Resource-Policy": "same-origin",
    "Referrer-Policy": "no-referrer",
}
_FILENAME_UTF8 = re.compile(r"filename\*\s*=\s*utf-8''([^;]+)", re.IGNORECASE)
_FILENAME = re.compile(r'filename\s*=\s*(?:"([^"]*)"|([^;]+))', re.IGNORECASE)
_UNSAFE_NAME = re.compile(r'[\x00-\x1f\x7f/\\"]')


def _proxy_enabled(settings: Annotated[Settings, Depends(get_settings)]) -> None:
    if not settings.catalog_proxy_enabled:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Not Found")


def _parsed_origin(url: str) -> str | None:
    """``url``'s origin as httpx, which makes the request, reads it."""
    try:
        parsed = httpx.URL(url)
        host = parsed.raw_host.decode("ascii")
    except (httpx.InvalidURL, UnicodeError):
        return None
    try:
        host = f"[{ipaddress.IPv6Address(host)}]"
    except ValueError:
        pass
    default = {"http": 80, "https": 443}.get(parsed.scheme)
    port = f":{parsed.port}" if parsed.port not in (None, default) else ""
    return f"{parsed.scheme}://{host}{port}"


def _header(value: str | None) -> str | None:
    """``value`` if it can cross as a header (printable ASCII), else nothing."""
    return value if value and value.isascii() and value.isprintable() else None


def _disposition(upstream: str) -> str:
    """Always an attachment; keeps only a cleaned-up file name from the catalog."""
    if match := _FILENAME_UTF8.search(upstream):
        name = unquote(match.group(1).strip(), errors="replace")
    elif match := _FILENAME.search(upstream):
        name = match.group(1) if match.group(1) is not None else match.group(2).strip()
    else:
        name = ""
    name = _UNSAFE_NAME.sub("", name).strip()[:150]
    return f"attachment; filename*=UTF-8''{quote(name, safe='')}" if name else "attachment"


class _Relayed(StreamingResponse):
    """A streamed download that releases its upstream and fetch slot however it ends."""

    def __init__(self, done: Callable[[], Awaitable[None]], *args, **kwargs) -> None:
        super().__init__(*args, **kwargs)
        self._done = done

    async def __call__(self, scope, receive, send) -> None:
        try:
            await super().__call__(scope, receive, send)
        finally:
            await self._done()


@router.get("/catalogs/{catalog_id}/fetch", dependencies=[Depends(_proxy_enabled)])
async def fetch(
    catalog_id: UUID,
    request: Request,
    secrets: Secrets,
    account: Account,
    session: Session,
    settings: Annotated[Settings, Depends(get_settings)],
    url: Annotated[str | None, Query(max_length=4096)] = None,
) -> Response:
    """GET ``url`` (default: the saved address) from the catalog, with its login."""
    # A browser session must name its account, as Quire Web always does: a
    # page on another site can make the browser send the cookie, not this.
    if not request.headers.get("authorization") and "x-quire-account" not in request.headers:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "account_header_required")
    state = request.app.state
    retry = state.catalog_proxy_limit.retry_after(account)
    if retry is not None:
        raise HTTPException(429, "rate_limited", headers={"Retry-After": str(retry)})
    row = await session.get(AccountCatalog, (account, catalog_id))
    if row is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "not_found")
    sealed, revision = row.secret, row.updated_at
    # Nothing below needs the database: the connection goes back to the pool
    # before the catalog, which may be slow, is contacted.
    await session.close()
    secret = open_secret(secrets, account, catalog_id, sealed)
    if secret is None:
        raise HTTPException(status.HTTP_409_CONFLICT, "needs_login")
    # Keyed by account too: a catalog id is chosen by the device that saved it.
    if state.catalog_proxy_backoff.paused((account, catalog_id), revision):
        logger.info("event=catalogs.fetch_paused id=%s", catalog_id)
        raise HTTPException(502, "catalog_login_rejected")
    origin = _origin(secret["url"])

    def same_origin(candidate: str) -> bool:
        # Both parsers must agree, so a URL read one way when checked and
        # another when sent cannot slip through.
        checked = _origin(candidate, settings.catalog_allow_http)
        return checked is not None and checked == origin == _parsed_origin(candidate)

    target = _checked_url(secret["url"] if url is None else url, settings.catalog_allow_http)[0]
    if not same_origin(target):
        raise HTTPException(422, "other_origin")
    target = str(httpx.URL(target).copy_with(fragment=None))
    authorization = None
    if secret.get("username"):
        login = f"{secret['username']}:{secret['password']}".encode()
        authorization = "Basic " + base64.b64encode(login).decode("ascii")
    headers = {}
    for name in _FORWARDED_REQUEST:
        if value := _header(request.headers.get(name)):
            headers[name] = value

    slots = state.catalog_proxy_slots
    if not slots.take(account):
        raise HTTPException(429, "too_many_fetches", headers={"Retry-After": "1"})
    try:
        upstream = await catalog_proxy.open_upstream(
            target,
            same_origin=same_origin,
            headers=headers,
            authorization=authorization,
            allow_private=settings.catalog_proxy_allow_private,
        )
    except BaseException as exc:
        slots.give(account)
        if isinstance(exc, catalog_proxy.UpstreamError):
            logger.info("event=catalogs.fetch_refused id=%s reason=%s", catalog_id, exc.reason)
            raise HTTPException(exc.status, exc.detail) from None
        raise
    state.catalog_proxy_backoff.answered(
        (account, catalog_id), revision, login_rejected=upstream.response.status_code in (401, 403)
    )

    async def done() -> None:
        try:
            await upstream.aclose()
        finally:
            slots.give(account)

    return await _relay(catalog_id, upstream, done)


async def _relay(
    catalog_id: UUID, upstream: catalog_proxy.Upstream, done: Callable[[], Awaitable[None]]
) -> Response:
    """The browser's copy of the catalog's answer; calls ``done`` when the upstream is spent."""
    streaming = False
    try:
        answer = upstream.response
        if answer.status_code in (401, 403):
            logger.info("event=catalogs.fetch_login_rejected id=%s", catalog_id)
            raise HTTPException(502, "catalog_login_rejected")
        if answer.status_code not in (200, 206, 304):
            logger.info(
                "event=catalogs.fetch_status id=%s status=%s", catalog_id, answer.status_code
            )
            return JSONResponse(
                {"detail": "catalog_error", "status": answer.status_code}, status_code=502
            )
        headers = dict(_UNTRUSTED_BODY)
        if location := _header(upstream.url):
            headers["Content-Location"] = location
        for name in _FORWARDED_RESPONSE:
            if value := _header(answer.headers.get(name)):
                headers[name] = value
        if answer.status_code == 304:
            return Response(status_code=304, headers=headers)
        # Asked for none, and refused if sent anyway: the caps count bytes as
        # they cross, and a small compressed body could unpack to a huge one.
        if answer.headers.get("content-encoding", "identity").strip().lower() not in (
            "",
            "identity",
        ):
            logger.info("event=catalogs.fetch_refused id=%s reason=content_encoding", catalog_id)
            raise HTTPException(502, "catalog_compressed")
        declared = _header(answer.headers.get("content-type")) or ""
        media = declared.split(";")[0].strip().lower()
        feed = media in _FEED_TYPES
        limit = FEED_LIMIT if feed else DOWNLOAD_LIMIT
        length = answer.headers.get("content-length", "")
        # A length counts only for a body framed by it, not a chunked one.
        framed = length.isdigit() and "transfer-encoding" not in answer.headers
        if framed and int(length) > limit:
            raise HTTPException(502, "catalog_too_large")
        if feed:
            body = bytearray()
            try:
                async with asyncio.timeout(FEED_DEADLINE_S):
                    async for chunk in answer.aiter_raw():
                        body += chunk
                        if len(body) > limit:
                            raise HTTPException(502, "catalog_too_large")
            except TimeoutError:
                logger.info("event=catalogs.fetch_refused id=%s reason=feed_deadline", catalog_id)
                raise HTTPException(502, "catalog_unreachable") from None
            return Response(bytes(body), answer.status_code, headers, media_type=declared)

        # Never a page: an unknown type becomes bytes to save.
        headers["Content-Disposition"] = _disposition(answer.headers.get("content-disposition", ""))
        release = done
        if framed:
            # Streamed as it arrives. A catalog that stops short of its length
            # leaves the browser a broken transfer, never a shorter book.
            headers["Content-Length"] = length
            body = answer.aiter_raw()
        else:
            # Without a length, a body cut off on the way (by the cap or the
            # catalog) would pass for a whole, shorter book. So it is gathered
            # first and sent with its length, or not at all.
            spool = await _gathered(answer, limit, catalog_id)
            headers["Content-Length"] = str(spool.tell())
            spool.seek(0)
            body = _replayed(spool)

            async def release() -> None:
                spool.close()
                await done()

        streaming = True
        return _Relayed(
            release,
            body,
            answer.status_code,
            headers,
            media_type=media if media in _DOWNLOAD_TYPES else "application/octet-stream",
        )
    except httpx.HTTPError:
        logger.info("event=catalogs.fetch_refused id=%s reason=catalog_unreachable", catalog_id)
        raise HTTPException(502, "catalog_unreachable") from None
    finally:
        if not streaming:
            await done()


async def _gathered(
    answer: httpx.Response, limit: int, catalog_id: UUID
) -> tempfile.SpooledTemporaryFile:
    spool = tempfile.SpooledTemporaryFile(_SPOOL_MEMORY)
    try:
        async for chunk in answer.aiter_raw():
            if spool.tell() + len(chunk) > limit:
                logger.info("event=catalogs.fetch_refused id=%s reason=too_large", catalog_id)
                raise HTTPException(502, "catalog_too_large")
            await asyncio.to_thread(spool.write, chunk)
    except BaseException:
        spool.close()
        raise
    return spool


async def _replayed(spool: tempfile.SpooledTemporaryFile):
    while chunk := await asyncio.to_thread(spool.read, 65536):
        yield chunk
