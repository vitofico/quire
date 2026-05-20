"""Unit tests for the AI auth abstraction (PR-B).

Covers:
* `AiPrincipal` shape (frozen, hashable, request_id captured).
* `BasicAuthAiAuthenticator` over an httpx-mocked calibre-web verifier.
* `TokenAiAuthenticator` HMAC-SHA256 verification, including every claim
  validation step and corner case enumerated in the design spec §7.1.
"""

from __future__ import annotations

import base64
import dataclasses
import hashlib
import hmac
import json

import httpx
import pytest
from fastapi import HTTPException
from starlette.datastructures import Headers
from starlette.requests import Request

from quire_server.api.ai_auth import (
    AiPrincipal,
    BasicAuthAiAuthenticator,
    TokenAiAuthenticator,
)
from quire_server.core.auth import CalibreAuthValidator
from quire_server.core.logging_ctx import request_id_var

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _make_request(headers: dict[str, str], *, client_host: str = "127.0.0.1") -> Request:
    """Build a Starlette Request with the given headers. ASGI-shape scope.

    A bare minimum scope keeps Starlette happy without an actual ASGI app.
    """
    raw_headers = [(k.lower().encode("latin-1"), v.encode("latin-1")) for k, v in headers.items()]
    scope = {
        "type": "http",
        "method": "GET",
        "path": "/",
        "headers": raw_headers,
        "query_string": b"",
        "client": (client_host, 0),
    }
    return Request(scope)


def _basic_header_value(user: str, password: str) -> str:
    token = base64.b64encode(f"{user}:{password}".encode()).decode("ascii")
    return f"Basic {token}"


def _make_calibre_validator(*, valid_user: str = "alice", valid_pass: str = "alicepass"):
    def handler(req: httpx.Request) -> httpx.Response:
        auth = req.headers.get("authorization", "")
        if not auth.lower().startswith("basic "):
            return httpx.Response(401)
        try:
            decoded = base64.b64decode(auth[6:].strip()).decode("utf-8")
            user, pw = decoded.split(":", 1)
        except Exception:
            return httpx.Response(401)
        if user == valid_user and pw == valid_pass:
            return httpx.Response(200, text="<feed/>")
        return httpx.Response(401)

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler), base_url="http://cwa")
    return CalibreAuthValidator(client=client, cwa_base_url="http://cwa", clock=lambda: 0.0)


# Token signing helper. Lives in the test module on purpose — the production
# code does NOT ship a minter.


def _b64url(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode("ascii")


def _sign_token(
    claims: dict,
    *,
    secret: str,
    kid: str | None = "k1",
    alg: str = "HS256",
    tamper_sig: bool = False,
    extra_header: dict | None = None,
    omit_alg: bool = False,
) -> str:
    header: dict = {}
    if not omit_alg:
        header["alg"] = alg
    if kid is not None:
        header["kid"] = kid
    if extra_header:
        header.update(extra_header)

    header_b64 = _b64url(json.dumps(header, separators=(",", ":")).encode("utf-8"))
    payload_b64 = _b64url(json.dumps(claims, separators=(",", ":")).encode("utf-8"))
    signing_input = f"{header_b64}.{payload_b64}".encode("ascii")
    sig = hmac.new(secret.encode("utf-8"), signing_input, hashlib.sha256).digest()
    if tamper_sig:
        sig = sig[:-1] + bytes([sig[-1] ^ 0x01])
    sig_b64 = _b64url(sig)
    return f"{header_b64}.{payload_b64}.{sig_b64}"


_SECRET = "x" * 32
_ISS = "quire-cloud"
_AUD = "opds-sync"


def _good_claims(now: int = 1_700_000_000, **overrides) -> dict:
    base = {
        "iss": _ISS,
        "aud": _AUD,
        "exp": now + 60,
        "iat": now,
        "sub": "acme:alice",
        "tenant_id": "acme",
    }
    base.update(overrides)
    return base


def _token_auth(*, secrets=None, issuer=_ISS, audience=_AUD, now: int = 1_700_000_000):
    return TokenAiAuthenticator(
        secrets=secrets or {"k1": _SECRET},
        issuer=issuer,
        audience=audience,
        clock=lambda: now,
    )


# ---------------------------------------------------------------------------
# AiPrincipal shape
# ---------------------------------------------------------------------------


def test_principal_shape_is_frozen_and_hashable():
    p = AiPrincipal(
        subject="alice",
        tenant_id="local",
        scopes=("ai:read",),
        auth_mode="basic",
        request_id="rid-1",
    )
    assert p.subject == "alice"
    assert p.tenant_id == "local"
    assert p.scopes == ("ai:read",)
    assert p.auth_mode == "basic"
    assert p.request_id == "rid-1"
    # frozen → cannot mutate
    with pytest.raises(dataclasses.FrozenInstanceError):
        p.subject = "bob"  # type: ignore[misc]
    # hashable (scopes is a tuple, not a list) — must not raise
    hash(p)


def test_principal_request_id_defaults_none():
    p = AiPrincipal(subject="a", tenant_id="t", scopes=(), auth_mode="basic")
    assert p.request_id is None


# ---------------------------------------------------------------------------
# BasicAuthAiAuthenticator
# ---------------------------------------------------------------------------


async def test_basic_auth_valid_credential_returns_principal():
    auth = BasicAuthAiAuthenticator(validator=_make_calibre_validator())
    req = _make_request({"authorization": _basic_header_value("alice", "alicepass")})
    p = await auth.authenticate(req)
    assert p == AiPrincipal(
        subject="alice",
        tenant_id="local",
        scopes=(),
        auth_mode="basic",
        request_id=None,
    )


async def test_basic_auth_missing_header_raises_401():
    auth = BasicAuthAiAuthenticator(validator=_make_calibre_validator())
    req = _make_request({})
    with pytest.raises(HTTPException) as exc:
        await auth.authenticate(req)
    assert exc.value.status_code == 401


async def test_basic_auth_invalid_credential_raises_401():
    auth = BasicAuthAiAuthenticator(validator=_make_calibre_validator())
    req = _make_request({"authorization": _basic_header_value("alice", "wrong")})
    with pytest.raises(HTTPException) as exc:
        await auth.authenticate(req)
    assert exc.value.status_code == 401


async def test_basic_auth_carries_request_id_from_contextvar():
    auth = BasicAuthAiAuthenticator(validator=_make_calibre_validator())
    token = request_id_var.set("rid-abc-123")
    try:
        req = _make_request({"authorization": _basic_header_value("alice", "alicepass")})
        p = await auth.authenticate(req)
        assert p.request_id == "rid-abc-123"
    finally:
        request_id_var.reset(token)


async def test_basic_auth_request_id_none_when_unset():
    auth = BasicAuthAiAuthenticator(validator=_make_calibre_validator())
    # ContextVar default is "" — we should normalize that to None.
    req = _make_request({"authorization": _basic_header_value("alice", "alicepass")})
    p = await auth.authenticate(req)
    assert p.request_id is None


# ---------------------------------------------------------------------------
# TokenAiAuthenticator — happy paths
# ---------------------------------------------------------------------------


async def test_token_valid_claims_returns_principal():
    now = 1_700_000_000
    auth = _token_auth(now=now)
    token = _sign_token(_good_claims(now, scope="ai:read ai:write"), secret=_SECRET)
    req = _make_request({"authorization": f"Bearer {token}"})
    p = await auth.authenticate(req)
    assert p == AiPrincipal(
        subject="acme:alice",
        tenant_id="acme",
        scopes=("ai:read", "ai:write"),
        auth_mode="token",
        request_id=None,
    )


async def test_token_two_kids_old_kid_accepted():
    now = 1_700_000_000
    secrets = {"k1": _SECRET, "k2": "y" * 32}
    auth = _token_auth(secrets=secrets, now=now)
    # Token signed under k1 (the "older" key in operator parlance)
    token = _sign_token(_good_claims(now), secret=_SECRET, kid="k1")
    p = await auth.authenticate(_make_request({"authorization": f"Bearer {token}"}))
    assert p.subject == "acme:alice"
    # Token signed under k2 (the "newer" key)
    token2 = _sign_token(_good_claims(now), secret="y" * 32, kid="k2")
    p2 = await auth.authenticate(_make_request({"authorization": f"Bearer {token2}"}))
    assert p2.tenant_id == "acme"


async def test_token_carries_request_id():
    now = 1_700_000_000
    auth = _token_auth(now=now)
    token = _sign_token(_good_claims(now), secret=_SECRET)
    rid_token = request_id_var.set("rid-token-1")
    try:
        req = _make_request({"authorization": f"Bearer {token}"})
        p = await auth.authenticate(req)
        assert p.request_id == "rid-token-1"
    finally:
        request_id_var.reset(rid_token)


async def test_token_scope_empty_yields_empty_tuple():
    now = 1_700_000_000
    auth = _token_auth(now=now)
    # No scope claim at all.
    token = _sign_token(_good_claims(now), secret=_SECRET)
    p = await auth.authenticate(_make_request({"authorization": f"Bearer {token}"}))
    assert p.scopes == ()
    # Explicit empty string.
    token2 = _sign_token(_good_claims(now, scope=""), secret=_SECRET)
    p2 = await auth.authenticate(_make_request({"authorization": f"Bearer {token2}"}))
    assert p2.scopes == ()


async def test_token_iat_at_boundary_accepted():
    now = 1_700_000_000
    auth = _token_auth(now=now)
    # iat == now + 300 is the exact upper bound.
    token = _sign_token(_good_claims(now, iat=now + 300, exp=now + 400), secret=_SECRET)
    p = await auth.authenticate(_make_request({"authorization": f"Bearer {token}"}))
    assert p.subject == "acme:alice"


# ---------------------------------------------------------------------------
# TokenAiAuthenticator — failure modes (each raises HTTPException(401))
# ---------------------------------------------------------------------------


def _make_token_req(headers: dict[str, str]) -> Request:
    return _make_request(headers)


async def _expect_401(auth: TokenAiAuthenticator, req: Request) -> None:
    with pytest.raises(HTTPException) as exc:
        await auth.authenticate(req)
    assert exc.value.status_code == 401


# Algorithm / header
async def test_token_alg_none_rejected():
    now = 1_700_000_000
    auth = _token_auth(now=now)
    token = _sign_token(_good_claims(now), secret=_SECRET, alg="none")
    await _expect_401(auth, _make_token_req({"authorization": f"Bearer {token}"}))


async def test_token_alg_wrong_rejected():
    now = 1_700_000_000
    auth = _token_auth(now=now)
    token = _sign_token(_good_claims(now), secret=_SECRET, alg="HS512")
    await _expect_401(auth, _make_token_req({"authorization": f"Bearer {token}"}))


async def test_token_alg_missing_rejected():
    now = 1_700_000_000
    auth = _token_auth(now=now)
    token = _sign_token(_good_claims(now), secret=_SECRET, omit_alg=True)
    await _expect_401(auth, _make_token_req({"authorization": f"Bearer {token}"}))


async def test_token_kid_missing_in_header_rejected():
    now = 1_700_000_000
    auth = _token_auth(now=now)
    token = _sign_token(_good_claims(now), secret=_SECRET, kid=None)
    await _expect_401(auth, _make_token_req({"authorization": f"Bearer {token}"}))


async def test_token_kid_empty_in_header_rejected():
    now = 1_700_000_000
    auth = _token_auth(now=now)
    token = _sign_token(_good_claims(now), secret=_SECRET, kid="")
    await _expect_401(auth, _make_token_req({"authorization": f"Bearer {token}"}))


async def test_token_kid_in_payload_rejected():
    now = 1_700_000_000
    auth = _token_auth(now=now)
    token = _sign_token(_good_claims(now, kid="k1"), secret=_SECRET, kid="k1")
    await _expect_401(auth, _make_token_req({"authorization": f"Bearer {token}"}))


async def test_token_unknown_kid_rejected():
    now = 1_700_000_000
    auth = _token_auth(now=now)
    token = _sign_token(_good_claims(now), secret=_SECRET, kid="unknown")
    await _expect_401(auth, _make_token_req({"authorization": f"Bearer {token}"}))


# Time
async def test_token_expired_rejected():
    now = 1_700_000_000
    auth = _token_auth(now=now)
    token = _sign_token(_good_claims(now, exp=now - 1), secret=_SECRET)
    await _expect_401(auth, _make_token_req({"authorization": f"Bearer {token}"}))


async def test_token_exp_equals_now_rejected():
    now = 1_700_000_000
    auth = _token_auth(now=now)
    token = _sign_token(_good_claims(now, exp=now), secret=_SECRET)
    await _expect_401(auth, _make_token_req({"authorization": f"Bearer {token}"}))


async def test_token_iat_too_far_in_future_rejected():
    now = 1_700_000_000
    auth = _token_auth(now=now)
    token = _sign_token(_good_claims(now, iat=now + 3600, exp=now + 3700), secret=_SECRET)
    await _expect_401(auth, _make_token_req({"authorization": f"Bearer {token}"}))


async def test_token_exp_lte_iat_rejected():
    now = 1_700_000_000
    auth = _token_auth(now=now)
    token = _sign_token(_good_claims(now, iat=now, exp=now - 10), secret=_SECRET)
    await _expect_401(auth, _make_token_req({"authorization": f"Bearer {token}"}))


async def test_token_lifetime_over_24h_rejected():
    now = 1_700_000_000
    auth = _token_auth(now=now)
    token = _sign_token(_good_claims(now, iat=now, exp=now + 86_401), secret=_SECRET)
    await _expect_401(auth, _make_token_req({"authorization": f"Bearer {token}"}))


# Identity
async def test_token_wrong_iss_rejected():
    now = 1_700_000_000
    auth = _token_auth(now=now)
    token = _sign_token(_good_claims(now, iss="evil"), secret=_SECRET)
    await _expect_401(auth, _make_token_req({"authorization": f"Bearer {token}"}))


async def test_token_wrong_aud_rejected():
    now = 1_700_000_000
    auth = _token_auth(now=now)
    token = _sign_token(_good_claims(now, aud="wrong"), secret=_SECRET)
    await _expect_401(auth, _make_token_req({"authorization": f"Bearer {token}"}))


# Signature integrity
async def test_token_tampered_signature_rejected():
    now = 1_700_000_000
    auth = _token_auth(now=now)
    token = _sign_token(_good_claims(now), secret=_SECRET, tamper_sig=True)
    await _expect_401(auth, _make_token_req({"authorization": f"Bearer {token}"}))


async def test_token_tampered_payload_rejected():
    now = 1_700_000_000
    auth = _token_auth(now=now)
    # Sign claims A, swap to claims B without re-signing.
    token = _sign_token(_good_claims(now), secret=_SECRET)
    h, _p_old, s = token.split(".")
    p_new = _b64url(
        json.dumps(_good_claims(now, sub="evil"), separators=(",", ":")).encode("utf-8")
    )
    tampered = f"{h}.{p_new}.{s}"
    await _expect_401(auth, _make_token_req({"authorization": f"Bearer {tampered}"}))


async def test_token_signature_wrong_length_rejected():
    now = 1_700_000_000
    auth = _token_auth(now=now)
    token = _sign_token(_good_claims(now), secret=_SECRET)
    h, p, _s = token.split(".")
    short_sig = _b64url(b"\x00" * 16)  # 16 bytes, not 32
    tampered = f"{h}.{p}.{short_sig}"
    await _expect_401(auth, _make_token_req({"authorization": f"Bearer {tampered}"}))


# Claim validation
@pytest.mark.parametrize("drop", ["iss", "aud", "exp", "iat", "sub", "tenant_id"])
async def test_token_missing_required_claim_rejected(drop):
    now = 1_700_000_000
    auth = _token_auth(now=now)
    claims = _good_claims(now)
    claims.pop(drop)
    token = _sign_token(claims, secret=_SECRET)
    await _expect_401(auth, _make_token_req({"authorization": f"Bearer {token}"}))


async def test_token_exp_as_string_rejected():
    now = 1_700_000_000
    auth = _token_auth(now=now)
    token = _sign_token(_good_claims(now, exp=str(now + 60)), secret=_SECRET)
    await _expect_401(auth, _make_token_req({"authorization": f"Bearer {token}"}))


async def test_token_iat_as_bool_rejected():
    now = 1_700_000_000
    auth = _token_auth(now=now)
    token = _sign_token(_good_claims(now, iat=True), secret=_SECRET)
    await _expect_401(auth, _make_token_req({"authorization": f"Bearer {token}"}))


async def test_token_sub_as_list_rejected():
    now = 1_700_000_000
    auth = _token_auth(now=now)
    token = _sign_token(_good_claims(now, sub=["a"]), secret=_SECRET)
    await _expect_401(auth, _make_token_req({"authorization": f"Bearer {token}"}))


async def test_token_tenant_id_empty_rejected():
    now = 1_700_000_000
    auth = _token_auth(now=now)
    token = _sign_token(_good_claims(now, tenant_id=""), secret=_SECRET)
    await _expect_401(auth, _make_token_req({"authorization": f"Bearer {token}"}))


async def test_token_tenant_id_over_128_chars_rejected():
    now = 1_700_000_000
    auth = _token_auth(now=now)
    token = _sign_token(_good_claims(now, tenant_id="a" * 129), secret=_SECRET)
    await _expect_401(auth, _make_token_req({"authorization": f"Bearer {token}"}))


@pytest.mark.parametrize("bad", ["a/b", "a b", "a\tb", "a+b"])
async def test_token_tenant_id_disallowed_chars_rejected(bad):
    now = 1_700_000_000
    auth = _token_auth(now=now)
    token = _sign_token(_good_claims(now, tenant_id=bad), secret=_SECRET)
    await _expect_401(auth, _make_token_req({"authorization": f"Bearer {token}"}))


async def test_token_scope_non_string_rejected():
    now = 1_700_000_000
    auth = _token_auth(now=now)
    token = _sign_token(_good_claims(now, scope=["ai:read"]), secret=_SECRET)
    await _expect_401(auth, _make_token_req({"authorization": f"Bearer {token}"}))


# Wire format
async def test_token_bearer_prefix_missing_rejected():
    now = 1_700_000_000
    auth = _token_auth(now=now)
    token = _sign_token(_good_claims(now), secret=_SECRET)
    await _expect_401(auth, _make_token_req({"authorization": token}))


async def test_token_wrong_scheme_rejected():
    now = 1_700_000_000
    auth = _token_auth(now=now)
    await _expect_401(auth, _make_token_req({"authorization": "Basic dXNlcjpwdw=="}))


async def test_token_bearer_extra_tokens_rejected():
    now = 1_700_000_000
    auth = _token_auth(now=now)
    token = _sign_token(_good_claims(now), secret=_SECRET)
    await _expect_401(auth, _make_token_req({"authorization": f"Bearer {token} extra"}))


async def test_token_segment_count_wrong_rejected():
    now = 1_700_000_000
    auth = _token_auth(now=now)
    await _expect_401(auth, _make_token_req({"authorization": "Bearer a.b"}))
    await _expect_401(auth, _make_token_req({"authorization": "Bearer a.b.c.d"}))


async def test_token_empty_segment_rejected():
    now = 1_700_000_000
    auth = _token_auth(now=now)
    token = _sign_token(_good_claims(now), secret=_SECRET)
    h, _p, s = token.split(".")
    await _expect_401(auth, _make_token_req({"authorization": f"Bearer {h}..{s}"}))


async def test_token_padding_present_rejected():
    now = 1_700_000_000
    auth = _token_auth(now=now)
    token = _sign_token(_good_claims(now), secret=_SECRET)
    # Force = padding into the payload segment.
    h, p, s = token.split(".")
    padded = p + "="
    await _expect_401(auth, _make_token_req({"authorization": f"Bearer {h}.{padded}.{s}"}))


async def test_token_non_base64url_chars_rejected():
    now = 1_700_000_000
    auth = _token_auth(now=now)
    token = _sign_token(_good_claims(now), secret=_SECRET)
    h, p, s = token.split(".")
    # Inject a `+` (standard base64, NOT url-safe).
    tampered_p = "+" + p[1:]
    await _expect_401(auth, _make_token_req({"authorization": f"Bearer {h}.{tampered_p}.{s}"}))


async def test_token_header_array_rejected():
    now = 1_700_000_000
    auth = _token_auth(now=now)
    # Build by hand: header is a JSON array, not an object.
    header_b64 = _b64url(json.dumps(["HS256", "k1"]).encode("utf-8"))
    payload_b64 = _b64url(json.dumps(_good_claims(now), separators=(",", ":")).encode("utf-8"))
    sig_b64 = _b64url(b"\x00" * 32)
    token = f"{header_b64}.{payload_b64}.{sig_b64}"
    await _expect_401(auth, _make_token_req({"authorization": f"Bearer {token}"}))


async def test_token_payload_array_rejected():
    now = 1_700_000_000
    auth = _token_auth(now=now)
    header_b64 = _b64url(json.dumps({"alg": "HS256", "kid": "k1"}).encode("utf-8"))
    payload_b64 = _b64url(json.dumps(["not", "an", "object"]).encode("utf-8"))
    signing_input = f"{header_b64}.{payload_b64}".encode("ascii")
    sig = hmac.new(_SECRET.encode(), signing_input, hashlib.sha256).digest()
    token = f"{header_b64}.{payload_b64}.{_b64url(sig)}"
    await _expect_401(auth, _make_token_req({"authorization": f"Bearer {token}"}))


async def test_token_payload_non_json_rejected():
    now = 1_700_000_000
    auth = _token_auth(now=now)
    header_b64 = _b64url(json.dumps({"alg": "HS256", "kid": "k1"}).encode("utf-8"))
    payload_b64 = _b64url(b"not-json-just-bytes")
    signing_input = f"{header_b64}.{payload_b64}".encode("ascii")
    sig = hmac.new(_SECRET.encode(), signing_input, hashlib.sha256).digest()
    token = f"{header_b64}.{payload_b64}.{_b64url(sig)}"
    await _expect_401(auth, _make_token_req({"authorization": f"Bearer {token}"}))


# ---------------------------------------------------------------------------
# TokenAiAuthenticator — guard rails
# ---------------------------------------------------------------------------


def test_token_authenticator_rejects_empty_secrets():
    with pytest.raises(ValueError):
        TokenAiAuthenticator(secrets={}, issuer=_ISS, audience=_AUD)


# Touch Headers import to keep linters quiet (used implicitly by Request init).
def test_headers_import_sanity():
    h = Headers({"x": "y"})
    assert h.get("x") == "y"
