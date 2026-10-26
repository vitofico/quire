"""Synthetic providers only; no real issuer, account or credential is contacted."""

import base64
import hashlib
import time
from dataclasses import replace
from urllib.parse import parse_qs, urlsplit

import httpx
import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa

from quire_server.core.oidc import MemoryTransactionStore, OIDCClient, OIDCError, OIDCSettings

ISSUER = "https://identity.example.test/application/o/quire/"
BROWSER = "fixture-browser-binding-with-256-bits-of-entropy"


@pytest.fixture
def provider():
    private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    public = jwt.algorithms.RSAAlgorithm.to_jwk(private.public_key(), as_dict=True)
    public.update(kid="fixture-key", use="sig", alg="RS256")
    state = {"requests": [], "claims": {}, "keys": [public], "nonce": None, "redirect": False}

    def handler(request):
        state["requests"].append(request)
        if request.url.path.endswith("openid-configuration"):
            return httpx.Response(
                200,
                json={
                    "issuer": state.get("issuer", ISSUER),
                    "authorization_endpoint": "https://identity.example.test/authorize",
                    "token_endpoint": state.get(
                        "token_endpoint", "https://identity.example.test/token"
                    ),
                    "jwks_uri": "https://identity.example.test/jwks",
                    "id_token_signing_alg_values_supported": ["RS256"],
                    "code_challenge_methods_supported": ["S256"],
                },
            )
        if request.url.path == "/token":
            if state["redirect"]:
                return httpx.Response(
                    307, headers={"Location": "https://attacker.example.test/token"}
                )
            claims = {
                "iss": ISSUER,
                "aud": "quire",
                "sub": "stable-subject",
                "nonce": state["nonce"],
                "iat": int(time.time()),
                "exp": int(time.time()) + 300,
                "email": "synthetic@example.test",
                "email_verified": True,
                "name": "Synthetic User",
            } | state["claims"]
            token = jwt.encode(claims, private, algorithm="RS256", headers={"kid": "fixture-key"})
            return httpx.Response(
                200, json={"id_token": token, "access_token": "discard-this-token"}
            )
        if request.url.path == "/jwks":
            return httpx.Response(200, json={"keys": state["keys"]})
        raise AssertionError(f"Unexpected provider request: {request.url}")

    return state, httpx.MockTransport(handler)


def settings():
    return OIDCSettings(ISSUER, "quire", "https://notes.example.test/api/v1/auth/oidc/callback")


async def begin(client, provider_state):
    authorization = await client.begin(BROWSER)
    params = parse_qs(urlsplit(authorization.url).query)
    provider_state["nonce"] = params["nonce"][0]
    return authorization, params


@pytest.mark.asyncio
async def test_code_pkce_identity_and_single_use(provider):
    state, transport = provider
    async with httpx.AsyncClient(transport=transport) as http:
        client = OIDCClient(settings(), http_client=http)
        authorization, params = await begin(client, state)
        assert params["response_type"] == ["code"]
        assert params["code_challenge_method"] == ["S256"]
        assert params["scope"] == ["openid profile email"]
        identity = await client.complete("synthetic-code", authorization.state, BROWSER)
        assert (identity.issuer, identity.subject) == (ISSUER, "stable-subject")
        assert identity.email == "synthetic@example.test"
        assert identity.email_verified
        assert not hasattr(identity, "access_token")
        token_request = next(r for r in state["requests"] if r.url.path == "/token")
        form = parse_qs(token_request.content.decode())
        challenge = (
            base64.urlsafe_b64encode(hashlib.sha256(form["code_verifier"][0].encode()).digest())
            .rstrip(b"=")
            .decode()
        )
        assert challenge == params["code_challenge"][0]
        assert form["redirect_uri"] == [settings().redirect_uri]
        with pytest.raises(OIDCError, match="invalid or expired"):
            await client.complete("synthetic-code", authorization.state, BROWSER)


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "claims",
    [
        {"iss": "https://attacker.example.test/"},
        {"aud": "another-client"},
        {"nonce": "incorrect"},
        {"exp": 1},
        {"iat": int(time.time()) + 3600},
        {"aud": ["quire", "other"]},
        {"azp": "other"},
        {"sub": ""},
    ],
)
async def test_rejects_invalid_identity_claims(provider, claims):
    state, transport = provider
    state["claims"] = claims
    async with httpx.AsyncClient(transport=transport) as http:
        client = OIDCClient(settings(), http_client=http)
        authorization, _ = await begin(client, state)
        with pytest.raises(OIDCError, match="ID token is invalid"):
            await client.complete("code", authorization.state, BROWSER)
        with pytest.raises(OIDCError, match="invalid or expired"):
            await client.complete("code", authorization.state, BROWSER)


@pytest.mark.asyncio
@pytest.mark.parametrize("wrong", ["state", "browser", "expiry"])
async def test_state_browser_and_expiry_fail_before_code_exchange(provider, wrong):
    state, transport = provider
    now = [1_000.0]
    async with httpx.AsyncClient(transport=transport) as http:
        client = OIDCClient(settings(), http_client=http, clock=lambda: now[0])
        authorization, _ = await begin(client, state)
        if wrong == "expiry":
            now[0] += 301
        with pytest.raises(OIDCError, match="invalid or expired"):
            await client.complete(
                "code",
                "wrong" if wrong == "state" else authorization.state,
                "wrong" if wrong == "browser" else BROWSER,
            )
        assert not any(r.url.path == "/token" for r in state["requests"])


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "bad_keys", ["missing", "duplicate", "encryption", "wrong-key", "bad-format"]
)
async def test_rejects_untrusted_or_ambiguous_jwks(provider, bad_keys):
    state, transport = provider
    good = state["keys"][0]
    if bad_keys == "missing":
        state["keys"] = []
    elif bad_keys == "duplicate":
        state["keys"] = [good, good]
    elif bad_keys == "encryption":
        state["keys"] = [good | {"use": "enc"}]
    elif bad_keys == "wrong-key":
        other = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        state["keys"] = [
            jwt.algorithms.RSAAlgorithm.to_jwk(other.public_key(), as_dict=True)
            | {"kid": "fixture-key", "alg": "RS256"}
        ]
    else:
        state["keys"] = [{"kid": "fixture-key", "kty": "RSA", "n": "invalid", "e": "invalid"}]
    async with httpx.AsyncClient(transport=transport) as http:
        client = OIDCClient(settings(), http_client=http)
        authorization, _ = await begin(client, state)
        with pytest.raises(OIDCError, match="ID token is invalid"):
            await client.complete("code", authorization.state, BROWSER)


@pytest.mark.asyncio
async def test_discovery_issuer_and_insecure_endpoint_rejected(provider):
    state, transport = provider
    async with httpx.AsyncClient(transport=transport) as http:
        client = OIDCClient(settings(), http_client=http)
        state["issuer"] = ISSUER.rstrip("/")
        with pytest.raises(OIDCError, match="issuer does not match"):
            await client.begin(BROWSER)
        state["issuer"] = ISSUER
        state["token_endpoint"] = "http://identity.example.test/token"
        with pytest.raises(OIDCError, match="invalid endpoint"):
            await client.begin(BROWSER)


@pytest.mark.asyncio
async def test_token_endpoint_redirect_never_followed(provider):
    state, transport = provider
    state["redirect"] = True
    async with httpx.AsyncClient(transport=transport, follow_redirects=True) as http:
        client = OIDCClient(settings(), http_client=http)
        authorization, _ = await begin(client, state)
        with pytest.raises(OIDCError, match="request failed"):
            await client.complete("code", authorization.state, BROWSER)
        assert all(r.url.host == "identity.example.test" for r in state["requests"])


@pytest.mark.asyncio
async def test_memory_store_is_bounded_and_purges_expired(provider):
    state, transport = provider
    now = [1_000.0]
    store = MemoryTransactionStore(clock=lambda: now[0], capacity=1)
    async with httpx.AsyncClient(transport=transport) as http:
        client = OIDCClient(
            settings(), transaction_store=store, http_client=http, clock=lambda: now[0]
        )
        await begin(client, state)
        with pytest.raises(OIDCError, match="Too many"):
            await begin(client, state)
        now[0] += 301
        await begin(client, state)


def test_only_explicit_loopback_can_use_http_and_only_pinned_algorithms():
    with pytest.raises(ValueError, match="HTTPS"):
        replace(settings(), issuer="http://localhost:8000")
    replace(settings(), issuer="http://127.0.0.1:8000", allow_insecure_loopback=True)
    with pytest.raises(ValueError, match="HTTPS"):
        replace(settings(), issuer="http://identity.example.test", allow_insecure_loopback=True)
    with pytest.raises(ValueError, match="asymmetric"):
        replace(settings(), algorithms=("HS256",))
    with pytest.raises(ValueError, match="credentials"):
        replace(settings(), issuer="https://user:password@identity.example.test/")


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "auth_time", [None, 1, "invalid", True, float("nan"), float("inf"), "future"]
)
async def test_sensitive_reauthentication_requires_fresh_signed_auth_time(provider, auth_time):
    state, transport = provider
    if auth_time is not None:
        state["claims"]["auth_time"] = (
            int(time.time()) + 100 if auth_time == "future" else auth_time
        )
    async with httpx.AsyncClient(transport=transport) as http:
        client = OIDCClient(settings(), http_client=http)
        authorization = await client.begin(
            BROWSER,
            purpose="local-password",
            user_id="fixture-user",
            session_hash="fixture-session-hash",
        )
        params = parse_qs(urlsplit(authorization.url).query)
        state["nonce"] = params["nonce"][0]
        assert params["prompt"] == ["login"] and params["max_age"] == ["0"]
        with pytest.raises(OIDCError):
            await client.complete("synthetic-code", authorization.state, BROWSER)
        with pytest.raises(OIDCError, match="invalid or expired"):
            await client.complete("synthetic-code", authorization.state, BROWSER)


@pytest.mark.asyncio
async def test_fresh_reauthentication_retains_only_server_owned_account_binding(provider):
    state, transport = provider
    state["claims"]["auth_time"] = int(time.time())
    # Claims with these names cannot change the transaction's account or purpose.
    state["claims"].update(user_id="attacker", purpose="oidc-link", session_hash="attacker")
    async with httpx.AsyncClient(transport=transport) as http:
        client = OIDCClient(settings(), http_client=http)
        authorization = await client.begin(
            BROWSER,
            purpose="local-password",
            user_id="fixture-user",
            session_hash="fixture-session-hash",
        )
        state["nonce"] = parse_qs(urlsplit(authorization.url).query)["nonce"][0]
        identity = await client.complete("synthetic-code", authorization.state, BROWSER)
        assert (identity.purpose, identity.user_id, identity.session_hash) == (
            "local-password",
            "fixture-user",
            "fixture-session-hash",
        )
        assert identity.auth_time == state["claims"]["auth_time"]
