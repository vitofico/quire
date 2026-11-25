"""``/catalogs/v1``: the book catalogs saved to a Quire account.

A catalog is an address plus an optional login. Both are sealed together in
``account_catalogs.secret`` (``core.catalog_secrets``), because some catalogs
(Kavita) put an account key in the URL path. The list shows only ``host``,
which is scheme, host and a non-default port.

Native Quire accounts only: a Calibre Basic login has no account to keep the
list on. Every route is a 404 while catalog sync is off (no key), and rows are
keyed by the account's storage user id, so another account's catalog looks
exactly like a missing one. Logs name a catalog by id only.
"""

from __future__ import annotations

import hashlib
import ipaddress
import json
import logging
import re
from collections.abc import Mapping
from datetime import UTC, datetime
from typing import Annotated, Literal
from urllib.parse import urlsplit
from uuid import UUID

import idna
from fastapi import APIRouter, Depends, Header, HTTPException, Request, Response, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from fastapi.routing import APIRoute
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import delete, func, select, text
from sqlalchemy.ext.asyncio import AsyncSession
from starlette.exceptions import HTTPException as StarletteHTTPException

from quire_server.config import Settings, get_settings
from quire_server.core.auth import current_auth_user
from quire_server.core.auth_backend import AuthUser
from quire_server.core.catalog_secrets import CatalogSecretError, CatalogSecrets
from quire_server.db.models import AccountCatalog
from quire_server.db.session import get_session

logger = logging.getLogger(__name__)

MAX_CATALOGS = 20
_HOST_NAME = re.compile(r"[a-z0-9.-]+")


class _CatalogRoute(APIRoute):
    """Answer every request ``no-store``, and keep submitted values out of 422s.

    FastAPI's default 422 quotes each rejected ``input``, which here could be
    a catalog address or password.
    """

    def get_route_handler(self):
        handler = super().get_route_handler()

        async def route(request: Request) -> Response:
            try:
                response = await handler(request)
            except RequestValidationError as exc:
                errors = [
                    {key: error[key] for key in ("type", "loc", "msg")} for error in exc.errors()
                ]
                response = JSONResponse({"detail": errors}, status_code=422)
            except StarletteHTTPException as exc:
                response = JSONResponse(
                    {"detail": exc.detail}, status_code=exc.status_code, headers=exc.headers
                )
            response.headers["Cache-Control"] = "no-store"
            return response

        return route


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
    tags=["catalogs"], route_class=_CatalogRoute, dependencies=[Depends(_catalog_secrets)]
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


def _stored_host(stored: dict) -> str | None:
    try:
        return _checked_url(stored["url"], allow_http=True)[1]
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
    catalog_id: UUID, secrets: Secrets, account: Account, session: Session
) -> dict:
    row = await session.get(AccountCatalog, (account, catalog_id))
    if row is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "not_found")
    secret = open_secret(secrets, account, catalog_id, row.secret)
    if secret is None:
        logger.warning("event=catalogs.secret_unreadable id=%s", catalog_id)
        raise HTTPException(status.HTTP_409_CONFLICT, "needs_login")
    return {key: secret.get(key) for key in ("url", "username", "password")}


@router.put("/catalogs/{catalog_id}")
async def put_catalog(
    catalog_id: UUID,
    body: CatalogPutBody,
    secrets: Secrets,
    account: Account,
    session: Session,
    settings: Annotated[Settings, Depends(get_settings)],
) -> dict:
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
        if _stored_host(stored) == host:
            password = stored.get("password")
    if (username is None) != (password is None):
        raise HTTPException(422, "login_incomplete")
    secret = {"url": url, "username": username, "password": password}

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
