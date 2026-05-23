"""``/auth/v1/*`` router for NativeAuth.

Phase 0, task S-1 (per
``docs/superpowers/specs/2026-05-22-quire-monetization-design.md``,
"Architectural changes → Thin auth abstraction in ``quire_server``").

This router is mounted ONLY when ``QUIRE_SERVER_AUTH_BACKEND=native``. OSS
deployments running CalibreWeb Basic never see these endpoints — a stray
``POST /auth/v1/login`` against an OSS server returns the FastAPI default
404, which is exactly the right shape: the endpoints do not exist there.

Endpoint set (the MINIMUM Cloud needs):

  * ``POST /auth/v1/login`` — email/password → opaque bearer session token.
  * ``POST /auth/v1/logout`` — revoke the bearer used to authenticate the
    request. Idempotent (a repeat call is silent, not 404).
  * ``POST /auth/v1/magic-link/request`` — STUB. Always 202. Sends nothing.
    Future Cloud-side delivery will replace the body, NOT the URL.
  * ``GET  /auth/v1/magic-link/consume`` — STUB. Always 501.

EXPLICITLY DEFERRED per spec ("self-host UX polish for Native auth is
deferred"): signup, password reset, email change, OAuth, refresh tokens,
rate limiting, lockout, CAPTCHA, MFA, audit trail surfacing, account
deletion. Cloud's control plane will handle signup/password-management
flows; the OSS server has no UX to support them.
"""

from __future__ import annotations

from datetime import datetime
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Request, Response, status
from pydantic import BaseModel, Field

from quire_server.core.auth import current_auth_user
from quire_server.core.auth_backend import AuthUser, NativeAuth

router = APIRouter(tags=["auth"])


# ----------------------------------------------------------------------------
# Schemas
# ----------------------------------------------------------------------------


class LoginRequest(BaseModel):
    # We accept ``str`` rather than :class:`EmailStr` so the response shape
    # for an obviously malformed email matches the unknown-email path
    # (single 401). Cloud's control plane can validate strictly at signup
    # time; the OSS login path treats all failures the same.
    email: str = Field(..., min_length=1, max_length=320)
    password: str = Field(..., min_length=1, max_length=1024)


class LoginResponse(BaseModel):
    token: str
    # Expiry as ISO-8601. Clients should refresh BEFORE this fires; the
    # server does not currently support refresh-without-relogin (deferred).
    expires_at: datetime


class MagicLinkRequest(BaseModel):
    # `EmailStr` requires the optional `email-validator` package which we
    # do not depend on. Accept raw str; the stub does no work anyway.
    email: str = Field(..., min_length=1, max_length=320)


class MagicLinkStubResponse(BaseModel):
    status: str = Field(default="accepted")
    # Hard-coded explanation so the response shape signals "this is a stub"
    # to any client probing it from outside Cloud. Cloud's real
    # implementation will keep ``status`` but drop or reshape ``detail``.
    detail: str = Field(
        default=(
            "magic-link delivery is not implemented in the OSS server; "
            "Quire Cloud sends magic links from a separate control plane"
        )
    )


# ----------------------------------------------------------------------------
# Helpers
# ----------------------------------------------------------------------------


async def _require_native_backend(request: Request) -> NativeAuth:
    """Return the configured backend, asserting it is :class:`NativeAuth`.

    The router is mounted only under Native mode, but a misconfigured
    deployment could in principle swap backends at runtime via app.state
    mutation. Failing fast with a 500 surfaces that instead of returning
    a confusing 401.
    """
    backend = getattr(request.app.state, "auth_backend", None)
    if not isinstance(backend, NativeAuth):
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="auth_backend_not_native",
        )
    return backend


# ----------------------------------------------------------------------------
# Endpoints
# ----------------------------------------------------------------------------


@router.post("/login", response_model=LoginResponse)
async def login(
    body: LoginRequest,
    backend: Annotated[NativeAuth, Depends(_require_native_backend)],
) -> LoginResponse:
    """Verify creds, issue a session token.

    Failure modes (unknown email, wrong password, malformed input) all
    collapse to ``401 {"detail": "invalid credentials"}``. The dummy-hash
    trick in :class:`NativeAuth.login` keeps the verify cost roughly
    constant across paths.
    """
    token, expires_at = await backend.login(email=body.email, password=body.password)
    return LoginResponse(token=token, expires_at=expires_at)


@router.post("/logout", status_code=status.HTTP_204_NO_CONTENT)
async def logout(
    user: Annotated[AuthUser, Depends(current_auth_user)],
    backend: Annotated[NativeAuth, Depends(_require_native_backend)],
) -> Response:
    """Revoke the session backing the current bearer token.

    Idempotent. A repeat call against an already-revoked token (or a token
    that was never valid) still returns 204; the ``current_auth_user``
    dependency above is what rejects unknown tokens at the boundary.
    """
    if user.session_token_hash is None:
        # Should be impossible under NativeAuth (current_user always sets
        # the hash); defensive 500 catches a future regression.
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="no_session_token",
        )
    await backend.revoke(user.session_token_hash)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.post(
    "/magic-link/request",
    status_code=status.HTTP_202_ACCEPTED,
    response_model=MagicLinkStubResponse,
)
async def magic_link_request(_: MagicLinkRequest) -> MagicLinkStubResponse:
    """STUB: accept the request shape, do nothing.

    Always returns 202 regardless of whether the email exists — leaks
    nothing about user existence. Cloud's control plane handles real
    delivery in Phase 1; the OSS server keeps the URL reserved.
    """
    return MagicLinkStubResponse(status="accepted")


@router.get(
    "/magic-link/consume",
    status_code=status.HTTP_501_NOT_IMPLEMENTED,
)
async def magic_link_consume(token: str = "") -> Response:
    """STUB: deliberately unimplemented in the OSS server.

    Cloud will implement consumption in its closed control plane; until
    then this endpoint reserves the URL and signals 501 so a client
    probing it gets a clear "not here yet" rather than a 404 that would
    suggest the URL might exist on a different mount.
    """
    # Use ``EmailStr``-style hint var to satisfy linters that flag unused
    # query params; the param exists for future-shape parity only.
    _ = token
    raise HTTPException(
        status_code=status.HTTP_501_NOT_IMPLEMENTED,
        detail="not implemented",
    )
