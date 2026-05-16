"""AI auth abstraction (PR-B seam).

The /ai/v1/* routes used to depend on `current_user_id`, a Basic-auth proxy
to calibre-web. That works for single-tenant deployments (the F-Droid app
case) but assumes the entire concept of "tenant" is the string "local".

This module introduces:

* `AiPrincipal`  – frozen dataclass bundling (subject, tenant_id, scopes,
                   auth_mode, request_id). Stored once per request.
* `AiAuthenticator`  – one-method Protocol the routes depend on (via
                       `get_ai_principal`).
* `BasicAuthAiAuthenticator`  – wraps `CalibreAuthValidator`. Today's default.
                                Emits tenant_id="local", auth_mode="basic".
* `TokenAiAuthenticator`  – HMAC-SHA256 verifier. Stub for the hosted future;
                            never wired by default. `kid` rotation supported
                            from day one via a JSON `{kid: secret}` map.

Sync routes (`/sync/v1/*`) keep depending on `current_user_id` directly.
The seam only swings on `/ai/v1/*`.

Cache-integrity invariant (PR-C): `book_insights`, `book_themes`,
`external_source_cache`, and `insight_identity_aliases` are SHARED cache and
MUST NOT carry user/tenant columns. `AiPrincipal.tenant_id` flows ONLY into
`ai_generation_log` (per-call audit) — never into a cache row's keying.

Token verification mirrors a stripped-down JWT/HS256 with strict
canonicalization (no `=` padding, no algorithm confusion, no claim type
laxity). Verification failures all collapse to a single 401 with no
discriminating detail in the response — failure reasons live in structured
logs only. The principle: never leak validation internals to an
unauthenticated caller.

Background-task caveat: `principal.request_id` is captured when the
authenticator builds the principal during dependency resolution (i.e. inside
the request context, AFTER `RequestIDMiddleware` set the ContextVar). PR-C's
audit logger reads `request_id_var` at log-write time, so background work
that reuses a principal AFTER the request context unwinds needs to either
pass `principal.request_id` explicitly or re-bind the ContextVar around the
task. The API layer does not spawn such tasks today; this is forward-looking.
"""

from __future__ import annotations

import base64
import hmac
import json
import logging
import re
import time
from dataclasses import dataclass, field
from typing import Annotated, Literal, Protocol

from fastapi import Depends, HTTPException, Request, status

from opds_sync.core.auth import CalibreAuthValidator
from opds_sync.core.logging_ctx import request_id_var

logger = logging.getLogger(__name__)


# Subject / tenant_id format guards. Tight enough to keep storage keys
# predictable, lax enough to accept tenant-qualified subjects like
# "acme:alice". Both share length cap 128.
_SUBJECT_RE = re.compile(r"^[A-Za-z0-9._:@-]{1,128}$")
_TENANT_RE = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")

# Hard cap on token lifetime. PR-B ships no replay protection, so absent
# this bound a misconfigured issuer could mint effectively-permanent tokens.
_MAX_TOKEN_LIFETIME_S = 86_400  # 24h
_IAT_CLOCK_SKEW_S = 300  # 5 min

# Strict base64url segment matcher. Disallows padding and any non-base64url
# characters. Length is checked separately for the signature segment.
_BASE64URL_RE = re.compile(r"^[A-Za-z0-9_-]+$")


@dataclass(frozen=True, slots=True)
class AiPrincipal:
    """A request's authenticated AI identity.

    `subject` is the user_id today; under token mode it's the token `sub`
    claim, which must be globally unique under the issuer.

    `tenant_id` is "local" under basic-auth, the token `tenant_id` claim
    under token-auth. Flows into `ai_generation_log.tenant_id`.

    `scopes` is a tuple (not list) so the dataclass stays hashable.

    `request_id` is captured at construction time from `request_id_var`.
    See module docstring for the background-task caveat.
    """

    subject: str
    tenant_id: str
    scopes: tuple[str, ...]
    auth_mode: Literal["basic", "token"]
    request_id: str | None = field(default=None)


class AiAuthenticator(Protocol):
    """The seam: one method, sync routes can ignore."""

    async def authenticate(self, request: Request) -> AiPrincipal: ...


def _read_request_id() -> str | None:
    """Capture the current request_id, normalizing the ContextVar default."""
    rid = request_id_var.get()
    return rid or None


class BasicAuthAiAuthenticator:
    """Wraps the existing calibre-web verifier for today's single-tenant case."""

    def __init__(self, validator: CalibreAuthValidator) -> None:
        self._validator = validator

    async def authenticate(self, request: Request) -> AiPrincipal:
        auth = request.headers.get("authorization")
        if not auth:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="missing credentials",
            )
        # CalibreAuthValidator raises HTTPException(401/503) on failure; we
        # let those propagate verbatim (their detail strings already match
        # what sync routes return).
        user_id = await self._validator.validate(auth)
        return AiPrincipal(
            subject=user_id,
            tenant_id="local",
            scopes=(),
            auth_mode="basic",
            request_id=_read_request_id(),
        )


# ---------------------------------------------------------------------------
# Token authenticator
# ---------------------------------------------------------------------------


class _TokenError(Exception):
    """Internal: one of the verification steps failed. Carries a reason.

    Translated into HTTPException(401) at the boundary so reasons stay in
    the structured log only.
    """

    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


def _b64url_decode_strict(seg: str) -> bytes:
    """Decode a URL-safe base64 segment, rejecting padding and bad chars.

    Mirrors JOSE's "no padding" canonical form, but we go stricter:
    `=` is explicitly rejected (urlsafe_b64decode would accept it),
    and we hand-roll the alphabet check with a regex so empty or
    weird-character segments fail before stdlib gets a chance to be
    permissive.
    """
    if not seg:
        raise _TokenError("malformed_token")
    if "=" in seg:
        raise _TokenError("malformed_token")
    if not _BASE64URL_RE.fullmatch(seg):
        raise _TokenError("malformed_token")
    # Manually pad for the stdlib call (it requires `=` even though our
    # input may not have it). Length-mod-4 padding is the standard recipe.
    pad = "=" * (-len(seg) % 4)
    try:
        return base64.urlsafe_b64decode(seg + pad)
    except (ValueError, base64.binascii.Error) as e:
        raise _TokenError("malformed_token") from e


def _is_real_int(v: object) -> bool:
    """True for int but NOT for bool. Python's `isinstance(True, int)` is True."""
    return type(v) is int


class TokenAiAuthenticator:
    """HMAC-SHA256 token verifier with kid rotation.

    Token wire format: `header.payload.signature`, each segment URL-safe
    base64 with no `=` padding. Header is `{"alg":"HS256","kid":"<kid>"}`.
    Payload claims are documented in the design spec §3.3.

    Verification is strict: algorithm-confusion attacks (`alg=none`) are
    rejected; `kid` in the payload is rejected (it lives in the header);
    `=` padding is rejected; non-base64url chars are rejected; signatures
    that don't decode to exactly 32 bytes are rejected. All failures
    raise HTTPException(401) with a non-discriminating detail.

    Multiple `kid -> secret` entries enable rotation: any registered kid
    is accepted at verification time. Token issuance is out of scope.
    """

    def __init__(
        self,
        *,
        secrets: dict[str, str],
        issuer: str,
        audience: str,
        clock: callable = time.time,  # type: ignore[valid-type]
    ) -> None:
        if not secrets:
            # Defense-in-depth: startup validation in main.py should already
            # have raised. Keep this as a hard guarantee.
            raise ValueError("TokenAiAuthenticator requires non-empty secrets")
        # Normalize to bytes for hmac.
        self._secrets: dict[str, bytes] = {
            kid: secret.encode("utf-8") for kid, secret in secrets.items()
        }
        self._issuer = issuer
        self._audience = audience
        self._clock = clock

    async def authenticate(self, request: Request) -> AiPrincipal:
        try:
            return self._authenticate(request)
        except _TokenError as e:
            # All failures collapse to 401. The reason goes to logs only.
            logger.info(
                "event=ai.auth.token_rejected reason=%s remote=%s",
                e.reason,
                _client_host(request),
            )
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="invalid credentials",
            ) from None

    def _authenticate(self, request: Request) -> AiPrincipal:
        token = _extract_bearer_token(request)
        header_b64, payload_b64, sig_b64 = _split_segments(token)

        header = _decode_json_object(header_b64, kind="header")
        # alg / kid live in the header. Order: check alg first so `alg=none`
        # tokens never reach the signature comparison.
        alg = header.get("alg")
        if not isinstance(alg, str) or alg != "HS256":
            raise _TokenError("bad_alg")
        kid = header.get("kid")
        if not isinstance(kid, str) or not kid:
            raise _TokenError("missing_kid")
        secret = self._secrets.get(kid)
        if secret is None:
            raise _TokenError("unknown_kid")

        # Signature must decode to exactly 32 bytes (HMAC-SHA256 output).
        sig = _b64url_decode_strict(sig_b64)
        if len(sig) != 32:
            raise _TokenError("bad_signature")
        signing_input = f"{header_b64}.{payload_b64}".encode("ascii")
        expected = hmac.new(secret, signing_input, "sha256").digest()
        if not hmac.compare_digest(sig, expected):
            raise _TokenError("bad_signature")

        payload = _decode_json_object(payload_b64, kind="payload")
        if "kid" in payload:
            # `kid` lives in the header; refusing it in the payload prevents
            # any future "which one wins?" ambiguity.
            raise _TokenError("kid_in_payload")

        iss = payload.get("iss")
        aud = payload.get("aud")
        exp = payload.get("exp")
        iat = payload.get("iat")
        sub = payload.get("sub")
        tenant_id = payload.get("tenant_id")
        scope = payload.get("scope")

        if not isinstance(iss, str):
            raise _TokenError("missing_claim:iss")
        if not isinstance(aud, str):
            raise _TokenError("missing_claim:aud")
        if not _is_real_int(exp):
            raise _TokenError("missing_claim:exp")
        if not _is_real_int(iat):
            raise _TokenError("missing_claim:iat")
        if not isinstance(sub, str) or not _SUBJECT_RE.fullmatch(sub):
            raise _TokenError("missing_claim:sub")
        if not isinstance(tenant_id, str) or not _TENANT_RE.fullmatch(tenant_id):
            raise _TokenError("missing_claim:tenant_id")

        if iss != self._issuer:
            raise _TokenError("bad_issuer")
        if aud != self._audience:
            raise _TokenError("bad_audience")

        now = int(self._clock())
        if now >= exp:
            raise _TokenError("expired")
        if iat > now + _IAT_CLOCK_SKEW_S:
            raise _TokenError("iat_in_future")
        if exp <= iat:
            raise _TokenError("bad_lifetime")
        if exp - iat > _MAX_TOKEN_LIFETIME_S:
            raise _TokenError("bad_lifetime")

        # Scope parsing. Spec §3.3: space-separated tokens; absent → ().
        scopes: tuple[str, ...]
        if scope is None:
            scopes = ()
        elif isinstance(scope, str):
            scopes = tuple(s for s in scope.split(" ") if s)
        else:
            raise _TokenError("bad_scope")

        return AiPrincipal(
            subject=sub,
            tenant_id=tenant_id,
            scopes=scopes,
            auth_mode="token",
            request_id=_read_request_id(),
        )


def _extract_bearer_token(request: Request) -> str:
    auth = request.headers.get("authorization", "")
    if not auth:
        raise _TokenError("malformed_authorization")
    # Single space after "Bearer", exactly one token after. No leading
    # whitespace, no trailing whitespace, no extra tokens.
    if not auth.startswith("Bearer "):
        raise _TokenError("malformed_authorization")
    token = auth[len("Bearer ") :]
    if not token or " " in token or "\t" in token:
        raise _TokenError("malformed_authorization")
    return token


def _split_segments(token: str) -> tuple[str, str, str]:
    parts = token.split(".")
    if len(parts) != 3:
        raise _TokenError("malformed_token")
    h, p, s = parts
    if not h or not p or not s:
        raise _TokenError("malformed_token")
    return h, p, s


def _decode_json_object(seg: str, *, kind: str) -> dict:
    raw = _b64url_decode_strict(seg)
    try:
        obj = json.loads(raw)
    except (ValueError, json.JSONDecodeError) as e:
        raise _TokenError(f"malformed_{kind}") from e
    if not isinstance(obj, dict):
        raise _TokenError(f"malformed_{kind}")
    return obj


def _client_host(request: Request) -> str:
    """Best-effort client host for logs. Never raises."""
    client = getattr(request, "client", None)
    if client is None:
        return "-"
    return getattr(client, "host", "-") or "-"


# ---------------------------------------------------------------------------
# FastAPI dependency wiring
# ---------------------------------------------------------------------------


async def get_ai_authenticator(request: Request) -> AiAuthenticator:
    """Pull the singleton authenticator stashed on app.state at startup."""
    auth = getattr(request.app.state, "ai_authenticator", None)
    if auth is None:
        # Should never happen if main.py wired correctly. A 500 is correct:
        # this is a server-side misconfiguration, not an auth failure.
        logger.error("ai_authenticator missing from app.state")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="ai_auth_not_configured",
        )
    return auth


async def get_ai_principal(
    request: Request,
    authenticator: Annotated[AiAuthenticator, Depends(get_ai_authenticator)],
) -> AiPrincipal:
    """FastAPI dependency for /ai/v1/* routes."""
    return await authenticator.authenticate(request)
