"""AuthBackend protocol + ``CalibreWebBasicAuth`` and ``NativeAuth``.

Phase 0, task S-1 (per
``docs/superpowers/specs/2026-05-22-quire-monetization-design.md``,
"Architectural changes → Thin auth abstraction in ``quire_server``" and
"Build sequence → Phase 0" item 3).

The OSS server historically coupled primary auth to CalibreWeb Basic. This
module introduces a thin protocol the rest of the server depends on, plus
two implementations:

* ``CalibreWebBasicAuth`` — wraps the existing :class:`CalibreAuthValidator`.
  Default in OSS. Emits ``user_id`` as the lowercase CWA username (preserved
  byte-for-byte from the pre-refactor behavior).
* ``NativeAuth`` — email/password + opaque session tokens. Used by Quire
  Cloud. Emits ``user_id`` as ``"native:<users.id>"`` so user-scoped storage
  rows do not collide with the CalibreWeb namespace.

Scope discipline: ``NativeAuth`` ships the minimum Cloud needs (login,
logout, session-validation, magic-link STUBS). Password reset, recovery,
abuse handling, rate-limiting, and refresh tokens are deferred per spec.

FastAPI wiring (see :mod:`quire_server.core.auth`):
the public dependency is still ``current_user_id``, returning ``str``. Tests
that override that dependency continue to work unchanged. Endpoints needing
the full :class:`AuthUser` (notably ``/auth/v1/logout``, which needs the
session token to revoke) use ``current_auth_user``.

AI-routes seam (``quire_server.api.ai_auth.AiAuthenticator``) remains
independent: AI keeps its own auth mechanism so token-mode multi-tenant
deployments are not entangled with primary-auth selection. The app factory
guards against the misconfiguration ``auth_backend=native`` +
``ai_enabled=true`` + ``ai_auth_mode=basic`` (which would silently leave
AI on CalibreWeb Basic).
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Literal, Protocol

from fastapi import HTTPException, Request, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import async_sessionmaker

from quire_server.core.auth import CalibreAuthValidator
from quire_server.core.native_auth import (
    canonical_email,
    generate_session_token,
    get_dummy_hash,
    hash_password,
    hash_session_token,
    verify_password,
)
from quire_server.db.models import NativeSession, NativeUser

logger = logging.getLogger(__name__)


# ----------------------------------------------------------------------------
# Public types
# ----------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class AuthUser:
    """A request's authenticated identity, as seen by primary-auth callers.

    ``user_id`` is the storage-scope identifier persisted into existing
    user-scoped tables (``documents.user_id``, ``library_items.user_id``,
    etc.). The format is backend-dependent:

      * CalibreWeb: lowercase CWA username (e.g. ``"alice"``).
      * Native: ``"native:<NativeUser.id>"`` (e.g. ``"native:42"``).

    ``backend`` is the configured backend that authenticated this request.
    ``session_token_hash`` is set only under Native auth (it is the hash
    used as the ``native_sessions`` primary key); ``logout`` needs it to
    target the right session row. CalibreWeb has no equivalent state.
    """

    user_id: str
    backend: Literal["calibreweb", "native"]
    session_token_hash: str | None = None


class AuthBackend(Protocol):
    """The primary-auth seam.

    A backend MUST resolve any valid request to an :class:`AuthUser` or
    raise :class:`fastapi.HTTPException(401)` on failure. The dependency
    layer above translates the result into the legacy ``user_id`` str
    expected by every existing route.
    """

    async def current_user(self, request: Request) -> AuthUser: ...


# ----------------------------------------------------------------------------
# CalibreWeb Basic auth
# ----------------------------------------------------------------------------


class CalibreWebBasicAuth:
    """Default OSS backend.

    Wraps the existing :class:`CalibreAuthValidator` so the byte-exact
    behavior of pre-S-1 deployments (Basic header, lowercase username as
    ``user_id``, CWA probe + TTL cache) is preserved verbatim.
    """

    def __init__(self, validator: CalibreAuthValidator) -> None:
        self._validator = validator

    async def current_user(self, request: Request) -> AuthUser:
        auth = request.headers.get("authorization")
        if not auth:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="missing credentials",
            )
        # CalibreAuthValidator raises HTTPException(401/503) on failure;
        # let those propagate verbatim so the wire shape matches the
        # pre-refactor server.
        user_id = await self._validator.validate(auth)
        return AuthUser(
            user_id=user_id,
            backend="calibreweb",
            session_token_hash=None,
        )


# ----------------------------------------------------------------------------
# Native auth
# ----------------------------------------------------------------------------


def _now_utc() -> datetime:
    return datetime.now(UTC)


# Sentinel returned by ``revoke`` to indicate idempotent no-op. Tests use it
# to assert the second logout of the same token is silent rather than 404.
REVOKE_NOOP = "noop"
REVOKE_OK = "ok"


class NativeAuth:
    """Email/password + opaque session tokens backend.

    Operations:

      * :meth:`register` — create a NativeUser with an argon2id hash. Used
        by tests and (in Phase 1) by Cloud's signup flow. Returns the new
        user id.
      * :meth:`login` — verify email+password, mint a session token, return
        ``(token_plain, expires_at)``. Constant-time against unknown email
        via the dummy-hash trick.
      * :meth:`revoke` — set ``revoked_at`` on the session matching the
        given token hash. Idempotent: revoking an already-revoked or
        unknown token returns silently.
      * :meth:`current_user` — validate the Bearer token on an inbound
        request, return the :class:`AuthUser`.

    The class never touches FastAPI globals; it is constructed with an
    async sessionmaker so unit tests can wire a clean engine without
    spinning up an app.

    All login/lookup paths fail with a single ``401 invalid credentials``
    response body — the same shape used by :class:`CalibreAuthValidator`.
    The reason for failure goes to the structured log only.
    """

    def __init__(
        self,
        *,
        session_factory: async_sessionmaker,
        session_ttl_s: int,
        clock: callable = _now_utc,  # type: ignore[valid-type]
    ) -> None:
        self._sf = session_factory
        self._ttl_s = session_ttl_s
        self._clock = clock

    # ------------------------------------------------------------------ #
    # Public API (called by the /auth/v1/* router and by tests)          #
    # ------------------------------------------------------------------ #

    async def register(self, *, email: str, password: str) -> int:
        """Create a new NativeUser. Returns the new user's id.

        The router exposes signup as a Cloud-controlled flow (Phase 1), so
        the OSS server itself does NOT mount a signup endpoint. This method
        is the building block used by tests and by the future control plane.

        Raises ``ValueError`` on duplicate email; the router translates to
        409 / 422 as appropriate.
        """
        canon = canonical_email(email)
        if not canon:
            raise ValueError("empty email")
        if not password:
            raise ValueError("empty password")
        pw_hash = await hash_password(password)
        async with self._sf() as s:
            row = NativeUser(email=canon, password_hash=pw_hash)
            s.add(row)
            try:
                await s.flush()
                user_id = row.id
                await s.commit()
            except Exception:
                await s.rollback()
                raise
        return user_id

    async def login(self, *, email: str, password: str) -> tuple[str, datetime]:
        """Verify creds and issue a session.

        Returns ``(token_plain, expires_at)`` on success. Raises
        ``HTTPException(401, detail='invalid credentials')`` on any failure
        (unknown email OR wrong password) AFTER running argon2-verify
        against either the real hash or a precomputed dummy. The verify
        cost dominates the DB hit/miss timing, so unknown-email and
        wrong-password responses are not trivially distinguishable.
        """
        canon = canonical_email(email)
        async with self._sf() as s:
            user_row = None
            if canon:
                user_row = (
                    await s.execute(
                        select(NativeUser).where(NativeUser.email == canon)
                    )
                ).scalar_one_or_none()

            if user_row is None:
                # Run a verify against the dummy hash anyway so the failure
                # path takes the same wall-clock cost as a real wrong-
                # password. The result is discarded.
                await verify_password(get_dummy_hash(), password or "")
                logger.info("event=auth.login_failed reason=unknown_email")
                raise HTTPException(
                    status_code=status.HTTP_401_UNAUTHORIZED,
                    detail="invalid credentials",
                )

            ok = await verify_password(user_row.password_hash, password or "")
            if not ok:
                logger.info(
                    "event=auth.login_failed reason=wrong_password user_id=%s",
                    user_row.id,
                )
                raise HTTPException(
                    status_code=status.HTTP_401_UNAUTHORIZED,
                    detail="invalid credentials",
                )

            token_plain, token_hash = generate_session_token()
            now = self._clock()
            expires_at = now + timedelta(seconds=self._ttl_s)
            s.add(
                NativeSession(
                    token_hash=token_hash,
                    user_id=user_row.id,
                    expires_at=expires_at,
                )
            )
            await s.commit()
            logger.info(
                "event=auth.login_ok user_id=%s expires_at=%s",
                user_row.id,
                expires_at.isoformat(),
            )
            return token_plain, expires_at

    async def revoke(self, token_hash: str) -> str:
        """Set ``revoked_at`` on the session matching ``token_hash``.

        Idempotent: returns :data:`REVOKE_NOOP` if no live session matched
        (either unknown hash or already-revoked), :data:`REVOKE_OK` on a
        first-time revoke. The router treats both as 204.
        """
        now = self._clock()
        async with self._sf() as s:
            row = (
                await s.execute(
                    select(NativeSession).where(NativeSession.token_hash == token_hash)
                )
            ).scalar_one_or_none()
            if row is None or row.revoked_at is not None:
                return REVOKE_NOOP
            row.revoked_at = now
            await s.commit()
            return REVOKE_OK

    # ------------------------------------------------------------------ #
    # AuthBackend protocol implementation                                #
    # ------------------------------------------------------------------ #

    async def current_user(self, request: Request) -> AuthUser:
        token = _extract_bearer_token(request)
        token_hash = hash_session_token(token)
        now = self._clock()

        async with self._sf() as s:
            row = (
                await s.execute(
                    select(NativeSession).where(NativeSession.token_hash == token_hash)
                )
            ).scalar_one_or_none()
            if row is None:
                logger.info("event=auth.session_invalid reason=unknown_token")
                raise HTTPException(
                    status_code=status.HTTP_401_UNAUTHORIZED,
                    detail="invalid credentials",
                )
            if row.revoked_at is not None:
                logger.info(
                    "event=auth.session_invalid reason=revoked user_id=%s",
                    row.user_id,
                )
                raise HTTPException(
                    status_code=status.HTTP_401_UNAUTHORIZED,
                    detail="invalid credentials",
                )
            if row.expires_at <= now:
                logger.info(
                    "event=auth.session_invalid reason=expired user_id=%s",
                    row.user_id,
                )
                raise HTTPException(
                    status_code=status.HTTP_401_UNAUTHORIZED,
                    detail="invalid credentials",
                )
            return AuthUser(
                user_id=f"native:{row.user_id}",
                backend="native",
                session_token_hash=token_hash,
            )


# ----------------------------------------------------------------------------
# Helpers
# ----------------------------------------------------------------------------


def _extract_bearer_token(request: Request) -> str:
    """Pull the opaque token out of an ``Authorization: Bearer ...`` header.

    Strict format: exactly ``"Bearer "`` followed by the token; no leading
    whitespace, no trailing tokens, no tabs. All failure modes collapse to
    a single 401 so the response shape mirrors the AI-routes token
    authenticator.
    """
    header = request.headers.get("authorization", "")
    if not header:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="missing credentials",
        )
    if not header.startswith("Bearer "):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="invalid credentials",
        )
    token = header[len("Bearer ") :]
    if not token or " " in token or "\t" in token:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="invalid credentials",
        )
    return token
