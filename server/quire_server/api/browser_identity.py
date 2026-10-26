"""Generic native/browser identity, OIDC onboarding and one-use phone pairing."""

import hashlib
import hmac
import re
import secrets
from dataclasses import asdict
from datetime import UTC, datetime, timedelta
from urllib.parse import urlsplit

from fastapi import APIRouter, HTTPException, Request, Response
from fastapi.responses import RedirectResponse
from pydantic import Field, ValidationError, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict
from sqlalchemy import select, text

from quire_server.core.auth_backend import AuthUser
from quire_server.core.native_auth import (
    generate_session_token,
    hash_password,
    hash_session_token,
    verify_password,
)
from quire_server.core.oidc import OIDCError, OIDCSettings, OIDCTransaction
from quire_server.db.models import NativeSession, NativeUser

router = APIRouter()


class IdentitySettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="QUIRE_IDENTITY_")
    public_url: str | None = None
    allow_insecure_loopback: bool = False
    oidc_issuer: str | None = None
    oidc_client_id: str | None = None
    oidc_client_secret: str | None = Field(None, repr=False)
    service_token: str | None = Field(None, repr=False)

    @model_validator(mode="after")
    def validate_settings(self):
        if self.public_url:
            parts = urlsplit(self.public_url)
            _ = parts.port
            if (
                not parts.hostname
                or parts.username
                or parts.password
                or parts.query
                or parts.fragment
                or parts.path not in {"", "/"}
                or not (
                    parts.scheme == "https"
                    or (
                        parts.scheme == "http"
                        and self.allow_insecure_loopback
                        and parts.hostname in {"localhost", "127.0.0.1", "::1"}
                    )
                )
            ):
                raise ValueError(
                    "Identity needs a fixed HTTPS public origin or explicit loopback fixture"
                )
            self.public_url = self.public_url.rstrip("/")
        if bool(self.oidc_issuer) != bool(self.oidc_client_id) or (
            self.oidc_issuer and not self.public_url
        ):
            raise ValueError("OIDC needs issuer, client ID and public origin")
        if self.oidc_issuer:
            self.oidc_settings()
        if self.service_token and len(self.service_token) < 32:
            raise ValueError("Identity service token needs 32+ characters")
        return self

    @property
    def cookie_name(self):
        return (
            "__Host-quire-session"
            if self.public_url and self.public_url.startswith("https:")
            else "quire-session"
        )

    def oidc_settings(self):
        return OIDCSettings(
            self.oidc_issuer,
            self.oidc_client_id,
            self.public_url + "/auth/v1/oidc/callback",
            client_secret=self.oidc_client_secret,
            allow_insecure_loopback=self.allow_insecure_loopback,
        )


def csrf(token):
    return hmac.new(token.encode(), b"quire-browser-csrf-v1", "sha256").hexdigest()


class Transactions:
    def __init__(self, factory):
        self.factory = factory

    async def put(self, state_hash, transaction):
        async with self.factory() as session:
            await session.execute(
                text("SELECT pg_advisory_xact_lock(hashtextextended('oidc-transactions',0))")
            )
            await session.execute(text("DELETE FROM oidc_transactions WHERE expires_at<=now()"))
            count = (
                await session.execute(text("SELECT count(*) FROM oidc_transactions"))
            ).scalar_one()
            if count >= 10000:
                raise OIDCError("Too many pending sign-in attempts.")
            import json

            await session.execute(
                text(
                    "INSERT INTO oidc_transactions(state_hash,payload,expires_at) "
                    "VALUES (:hash,CAST(:payload AS JSON),:expires)"
                ),
                {
                    "hash": state_hash,
                    "payload": json.dumps(asdict(transaction)),
                    "expires": datetime.fromtimestamp(transaction.expires_at, UTC),
                },
            )
            await session.commit()

    async def take(self, state_hash):
        async with self.factory() as session:
            row = await session.execute(
                text("DELETE FROM oidc_transactions WHERE state_hash=:hash RETURNING payload"),
                {"hash": state_hash},
            )
            payload = row.scalar_one_or_none()
            await session.commit()
        return OIDCTransaction(**payload) if payload is not None else None


async def issue(factory, user_id, ttl, kind="browser", device_name=None):
    now = datetime.now(UTC)
    token, digest = generate_session_token()
    async with factory() as session:
        user = (
            await session.execute(
                select(NativeUser).where(NativeUser.id == user_id).with_for_update()
            )
        ).scalar_one_or_none()
        if user is None or user.closed_at is not None:
            raise HTTPException(401, "account_closed")
        session.add(
            NativeSession(
                token_hash=digest,
                user_id=user_id,
                expires_at=now + timedelta(seconds=ttl),
                kind=kind,
                device_name=device_name,
            )
        )
        await session.commit()
    return token


def set_cookie(response, settings, token):
    response.set_cookie(
        settings.cookie_name,
        token,
        path="/",
        max_age=3600,
        httponly=True,
        secure=settings.public_url.startswith("https:"),
        samesite="lax",
    )
    response.headers["Cache-Control"] = "no-store"


def origin(request):
    settings = request.app.state.identity_settings
    if not settings.public_url or request.headers.get("origin") != settings.public_url:
        raise HTTPException(403, "invalid_origin")
    return settings


@router.get("/browser")
async def browser_options(request: Request):
    settings = request.app.state.identity_settings
    return {
        "enabled": bool(settings.public_url),
        "oidc": bool(settings.oidc_issuer),
        "local_login": True,
        "signup_recovery": "identity_provider" if settings.oidc_issuer else "operator",
    }


@router.post("/browser/login")
async def browser_login(request: Request):
    settings = origin(request)
    from quire_server.api.auth import LoginRequest

    try:
        body = LoginRequest.model_validate(await request.json())
    except (ValidationError, ValueError):
        raise HTTPException(400, "invalid_login_request") from None
    guard = request.app.state.native_login_guard
    guard.begin(request.client.host if request.client else "unknown")
    try:
        token, _ = await request.app.state.auth_backend.login(
            email=body.email, password=body.password
        )
    finally:
        guard.inflight -= 1
    async with request.app.state.identity_factory() as session:
        await session.execute(
            text("UPDATE native_sessions SET kind='browser' WHERE token_hash=:hash"),
            {"hash": hash_session_token(token)},
        )
        await session.execute(
            text(
                "UPDATE native_sessions SET expires_at=LEAST(expires_at,:expires) "
                "WHERE token_hash=:hash"
            ),
            {"hash": hash_session_token(token), "expires": datetime.now(UTC) + timedelta(hours=1)},
        )
        await session.commit()
    response = Response(content='{"status":"signed_in"}', media_type="application/json")
    set_cookie(response, settings, token)
    return response


@router.get("/oidc/start")
async def oidc_start(request: Request, reauth: str | None = None):
    settings = request.app.state.identity_settings
    if not settings.oidc_issuer:
        raise HTTPException(404, "oidc_disabled")
    kwargs = {}
    if reauth is not None:
        if reauth != "account-delete":
            raise HTTPException(400, "invalid_reauthentication")
        user = await request.app.state.auth_backend.current_user(request)
        kwargs = {
            "purpose": reauth,
            "user_id": user.user_id,
            "session_hash": user.session_token_hash,
        }
    binding = secrets.token_urlsafe(32)
    client = request.app.state.oidc_client
    try:
        authorization = await client.begin(binding, **kwargs)
    except OIDCError as exc:
        raise HTTPException(503, "identity_provider_unavailable") from exc
    response = RedirectResponse(authorization.url, status_code=303)
    response.set_cookie(
        settings.cookie_name + "-oidc",
        binding,
        path="/",
        max_age=300,
        httponly=True,
        secure=settings.public_url.startswith("https:"),
        samesite="lax",
    )
    response.headers["Cache-Control"] = "no-store"
    return response


@router.get("/oidc/callback")
async def oidc_callback(request: Request, code: str = "", state: str = ""):
    settings = request.app.state.identity_settings
    if not settings.oidc_issuer:
        raise HTTPException(404, "oidc_disabled")
    try:
        identity = await request.app.state.oidc_client.complete(
            code, state, request.cookies.get(settings.cookie_name + "-oidc", "")
        )
    except OIDCError as exc:
        raise HTTPException(401, "invalid_oidc_login") from exc
    factory = request.app.state.identity_factory
    if identity.purpose:
        # The validated one-use browser binding and server-held transaction
        # identify this session. Cross-site IdP callbacks need not carry the
        # ordinary application cookie. Never create/recover a session from email.
        if (
            identity.purpose != "account-delete"
            or not isinstance(identity.user_id, str)
            or not re.fullmatch(r"native:[1-9][0-9]{0,17}", identity.user_id)
            or not isinstance(identity.session_hash, str)
        ):
            raise HTTPException(401, "reauthentication_required")
        ordinary = request.cookies.get(settings.cookie_name)
        if ordinary is not None and not hmac.compare_digest(
            hash_session_token(ordinary), identity.session_hash
        ):
            raise HTTPException(401, "reauthentication_required")
        number = int(identity.user_id.split(":")[1])
        async with factory() as session:
            live = await session.scalar(
                select(NativeSession.token_hash)
                .join(NativeUser, NativeUser.id == NativeSession.user_id)
                .where(
                    NativeSession.token_hash == identity.session_hash,
                    NativeSession.user_id == number,
                    NativeSession.revoked_at.is_(None),
                    NativeSession.expires_at > datetime.now(UTC),
                    NativeUser.closed_at.is_(None),
                )
            )
            linked = await session.scalar(
                text(
                    "SELECT 1 FROM external_identities WHERE issuer=:iss AND subject=:sub "
                    "AND user_id=:id"
                ),
                {"iss": identity.issuer, "sub": identity.subject, "id": number},
            )
        if not live or not linked:
            raise HTTPException(401, "reauthentication_required")
        user = AuthUser(
            user_id=identity.user_id, backend="native", session_token_hash=identity.session_hash
        )
        proof = await grant_delete(request, user)
        response = RedirectResponse(
            settings.public_url + "/cloud/account?security=verified", status_code=303
        )
        response.set_cookie(
            settings.cookie_name + "-delete",
            proof,
            max_age=120,
            path="/",
            secure=settings.public_url.startswith("https:"),
            httponly=True,
            samesite="strict",
        )
        response.delete_cookie(
            settings.cookie_name + "-oidc",
            path="/",
            secure=settings.public_url.startswith("https:"),
            httponly=True,
            samesite="lax",
        )
        response.headers["Cache-Control"] = "no-store"
        return response
    # No email linking: an issuer+subject maps to exactly one stable storage ID.
    async with factory() as session:
        await session.execute(
            text("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))"),
            {
                "key": hashlib.sha256(
                    (identity.issuer + "\0" + identity.subject).encode()
                ).hexdigest()
            },
        )
        row = (
            await session.execute(
                text("SELECT user_id FROM external_identities WHERE issuer=:iss AND subject=:sub"),
                {"iss": identity.issuer, "sub": identity.subject},
            )
        ).scalar_one_or_none()
        if row is None:
            user = NativeUser(
                email="oidc+" + secrets.token_hex(20) + "@identity.invalid",
                password_hash=await hash_password(secrets.token_urlsafe(48)),
            )
            session.add(user)
            await session.flush()
            row = user.id
            await session.execute(
                text(
                    "INSERT INTO external_identities(issuer,subject,user_id,email,email_verified) "
                    "VALUES (:iss,:sub,:id,:email,:verified)"
                ),
                {
                    "iss": identity.issuer,
                    "sub": identity.subject,
                    "id": row,
                    "email": identity.email,
                    "verified": identity.email_verified,
                },
            )
        await session.commit()
    if request.app.state.hosted_policy:
        await request.app.state.hosted_policy.allow_account(f"native:{row}")
    token = await issue(factory, row, min(3600, request.app.state.auth_backend._ttl_s))
    response = RedirectResponse(settings.public_url + "/cloud/account", status_code=303)
    set_cookie(response, settings, token)
    response.delete_cookie(
        settings.cookie_name + "-oidc",
        path="/",
        secure=settings.public_url.startswith("https:"),
        httponly=True,
        samesite="lax",
    )
    return response


@router.post("/pairing")
async def pairing(request: Request):
    user = await request.app.state.auth_backend.current_user(request)
    token = secrets.token_urlsafe(32)
    expires = datetime.now(UTC) + timedelta(seconds=120)
    async with request.app.state.identity_factory() as session:
        await session.execute(
            text("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))"),
            {"key": "pairing:" + user.user_id},
        )
        await session.execute(text("DELETE FROM pairing_tickets WHERE expires_at<=now()"))
        count = await session.scalar(
            text("SELECT count(*) FROM pairing_tickets WHERE user_id=:id"),
            {"id": int(user.user_id.split(":")[1])},
        )
        if count >= 10:
            raise HTTPException(429, "pairing_rate_limited")
        await session.execute(
            text(
                "INSERT INTO pairing_tickets(ticket_hash,user_id,session_hash,expires_at) "
                "VALUES (:ticket,:user,:session,:expires)"
            ),
            {
                "ticket": hash_session_token(token),
                "user": int(user.user_id.split(":")[1]),
                "session": user.session_token_hash,
                "expires": expires,
            },
        )
        await session.commit()
    return {
        "ticket": token,
        "expires_at": expires.isoformat(),
        "exchange_path": "/auth/v1/pairing/exchange",
    }


@router.post("/pairing/exchange")
async def exchange(request: Request):
    try:
        body = await request.json()
    except ValueError:
        raise HTTPException(400, "invalid_pairing") from None
    if not isinstance(body, dict):
        raise HTTPException(400, "invalid_pairing")
    ticket = body.get("ticket", "")
    name = body.get("device_name", "Phone")
    if (
        not isinstance(ticket, str)
        or not 20 <= len(ticket) <= 128
        or not ticket.isascii()
        or not isinstance(name, str)
        or len(name) > 80
    ):
        raise HTTPException(400, "invalid_pairing")
    async with request.app.state.identity_factory() as session:
        row = (
            (
                await session.execute(
                    text(
                        "DELETE FROM pairing_tickets WHERE ticket_hash=:hash "
                        "RETURNING user_id,session_hash,expires_at"
                    ),
                    {"hash": hash_session_token(ticket)},
                )
            )
            .mappings()
            .one_or_none()
        )
        await session.commit()
        if row is None or row["expires_at"] <= datetime.now(UTC):
            raise HTTPException(401, "invalid_pairing")
        user = (
            await session.execute(
                select(NativeUser).where(NativeUser.id == row["user_id"]).with_for_update()
            )
        ).scalar_one_or_none()
        if user is None or user.closed_at is not None:
            raise HTTPException(401, "account_closed")
        live = await session.scalar(
            select(NativeSession.token_hash)
            .where(
                NativeSession.token_hash == row["session_hash"],
                NativeSession.revoked_at.is_(None),
                NativeSession.expires_at > datetime.now(UTC),
            )
            .with_for_update()
        )
        if live is None:
            raise HTTPException(401, "invalid_pairing")
        token, digest = generate_session_token()
        expires_at = datetime.now(UTC) + timedelta(seconds=request.app.state.auth_backend._ttl_s)
        session.add(
            NativeSession(
                token_hash=digest,
                user_id=row["user_id"],
                kind="device",
                device_name=name,
                expires_at=expires_at,
            )
        )
        await session.commit()
    return {
        "token": token,
        "token_type": "Bearer",
        "expires_at": expires_at.isoformat(),
        "account_id": f"native:{row['user_id']}",
        "account_subject": user.email,
    }


async def grant_delete(request, user):
    proof = secrets.token_urlsafe(32)
    async with request.app.state.identity_factory() as session:
        await session.execute(
            text(
                "DELETE FROM account_delete_authorizations WHERE expires_at<=now() "
                "OR session_hash=:hash"
            ),
            {"hash": user.session_token_hash},
        )
        await session.execute(
            text(
                "INSERT INTO account_delete_authorizations"
                "(proof_hash,user_id,session_hash,expires_at) "
                "VALUES (:proof,:id,:hash,now()+interval '120 seconds')"
            ),
            {
                "proof": hash_session_token(proof),
                "id": int(user.user_id.split(":")[1]),
                "hash": user.session_token_hash,
            },
        )
        await session.commit()
    return proof


@router.post("/account/delete-authorization")
async def authorize_delete(request: Request):
    user = await request.app.state.auth_backend.current_user(request)
    body = await request.json()
    password = body.get("password") if isinstance(body, dict) else None
    if not isinstance(password, str) or not 1 <= len(password) <= 1024:
        raise HTTPException(400, "invalid_reauthentication")
    guard = request.app.state.native_login_guard
    guard.begin(request.client.host if request.client else "unknown")
    try:
        async with request.app.state.identity_factory() as session:
            native = await session.get(NativeUser, int(user.user_id.split(":")[1]))
            if native is None or not await verify_password(native.password_hash, password):
                raise HTTPException(401, "reauthentication_required")
    finally:
        guard.inflight -= 1
    return {"proof": await grant_delete(request, user), "expires_in": 120}


@router.post("/account/delete-authorization/consume")
async def consume_delete(request: Request):
    user = await request.app.state.auth_backend.current_user(request)
    body = await request.json()
    proof = body.get("proof") if isinstance(body, dict) else None
    if not isinstance(proof, str) or not 20 <= len(proof) <= 128 or not proof.isascii():
        raise HTTPException(401, "reauthentication_required")
    async with request.app.state.identity_factory() as session:
        row = await session.scalar(
            text(
                "DELETE FROM account_delete_authorizations "
                "WHERE proof_hash=:proof AND user_id=:id AND session_hash=:hash "
                "AND expires_at>now() RETURNING user_id"
            ),
            {
                "proof": hash_session_token(proof),
                "id": int(user.user_id.split(":")[1]),
                "hash": user.session_token_hash,
            },
        )
        await session.commit()
    if row is None:
        raise HTTPException(401, "reauthentication_required")
    return {"account_id": user.user_id, "status": "authorized"}
