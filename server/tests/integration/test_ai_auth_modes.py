"""End-to-end tests for the AI auth mode switch (PR-B).

Spins up the FastAPI app with `OPDS_SYNC_AI_AUTH_MODE` set to each value,
then verifies that:

* `basic` mode rejects Bearer tokens (Authorization header doesn't decode as
  Basic → 401).
* `token` mode rejects Basic credentials (no Bearer prefix → 401).
* `token` mode accepts a freshly-minted, properly-signed HMAC token.
* `token` mode honors `kid` rotation: two secrets registered, token signed
  under either is accepted.
* `token` mode + valid token → `ai_generation_log` rows carry the token's
  `tenant_id` (not the literal `"local"` that PR-C used to hardcode).

These tests pass `skip_auth_overrides=True` to `client_factory` so the real
authenticator runs.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time

import httpx
import pytest
from sqlalchemy import select

from opds_sync.core.ai.client import AIClient
from opds_sync.core.ai.service import InsightOrchestrator
from opds_sync.db.models import AIGenerationLog

pytestmark = pytest.mark.requires_ai


_SECRET_1 = "k1-" + "x" * 32  # >= 32 utf-8 bytes
_SECRET_2 = "k2-" + "y" * 32
_ISS = "quire-cloud"
_AUD = "opds-sync"


def _b64url(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode("ascii")


def _sign_token(
    claims: dict,
    *,
    secret: str,
    kid: str = "k1",
) -> str:
    header = {"alg": "HS256", "kid": kid}
    header_b64 = _b64url(json.dumps(header, separators=(",", ":")).encode("utf-8"))
    payload_b64 = _b64url(json.dumps(claims, separators=(",", ":")).encode("utf-8"))
    signing_input = f"{header_b64}.{payload_b64}".encode("ascii")
    sig = hmac.new(secret.encode("utf-8"), signing_input, hashlib.sha256).digest()
    return f"{header_b64}.{payload_b64}.{_b64url(sig)}"


def _good_claims(*, sub: str = "acme:alice", tenant: str = "acme", lifetime_s: int = 60) -> dict:
    now = int(time.time())
    return {
        "iss": _ISS,
        "aud": _AUD,
        "exp": now + lifetime_s,
        "iat": now - 1,
        "sub": sub,
        "tenant_id": tenant,
    }


def _basic_header(user: str, password: str = "p") -> dict:
    return {
        "Authorization": "Basic "
        + base64.b64encode(f"{user}:{password}".encode()).decode()
    }


# ---------------------------------------------------------------------------
# Mode rejection
# ---------------------------------------------------------------------------


async def test_basic_mode_rejects_bearer_token(client_factory):
    """Basic-mode deploy must not silently accept token-style requests."""
    async with client_factory(
        ai_enabled=True,
        ai_auth_mode="basic",
        skip_auth_overrides=True,
    ) as client:
        r = await client.get(
            "/ai/v1/config",
            headers={"Authorization": "Bearer some.thing.signed"},
        )
    assert r.status_code == 401


async def test_token_mode_rejects_basic_credentials(client_factory):
    """Token-mode deploy must not silently accept basic-auth requests."""
    async with client_factory(
        ai_enabled=True,
        ai_auth_mode="token",
        ai_token_secrets=json.dumps({"k1": _SECRET_1}),
        ai_token_issuer=_ISS,
        ai_token_audience=_AUD,
        skip_auth_overrides=True,
    ) as client:
        r = await client.get("/ai/v1/config", headers=_basic_header("alice"))
    assert r.status_code == 401


# ---------------------------------------------------------------------------
# Happy-path token mode
# ---------------------------------------------------------------------------


async def test_token_mode_valid_token_returns_200(client_factory):
    token = _sign_token(_good_claims(), secret=_SECRET_1, kid="k1")
    async with client_factory(
        ai_enabled=True,
        ai_auth_mode="token",
        ai_token_secrets=json.dumps({"k1": _SECRET_1}),
        ai_token_issuer=_ISS,
        ai_token_audience=_AUD,
        skip_auth_overrides=True,
    ) as client:
        r = await client.get(
            "/ai/v1/config", headers={"Authorization": f"Bearer {token}"}
        )
    assert r.status_code == 200, r.text


async def test_token_mode_kid_rotation_old_kid_accepted(client_factory):
    """Two kids registered; either accepts."""
    secrets = json.dumps({"k1": _SECRET_1, "k2": _SECRET_2})
    async with client_factory(
        ai_enabled=True,
        ai_auth_mode="token",
        ai_token_secrets=secrets,
        ai_token_issuer=_ISS,
        ai_token_audience=_AUD,
        skip_auth_overrides=True,
    ) as client:
        # Sign under the older kid.
        t1 = _sign_token(_good_claims(), secret=_SECRET_1, kid="k1")
        r1 = await client.get(
            "/ai/v1/config", headers={"Authorization": f"Bearer {t1}"}
        )
        assert r1.status_code == 200, r1.text
        # Sign under the newer kid.
        t2 = _sign_token(_good_claims(), secret=_SECRET_2, kid="k2")
        r2 = await client.get(
            "/ai/v1/config", headers={"Authorization": f"Bearer {t2}"}
        )
        assert r2.status_code == 200, r2.text


# ---------------------------------------------------------------------------
# Token auth → ai_generation_log integration (the load-bearing wire to PR-C)
# ---------------------------------------------------------------------------


def _ai_chat_response(payload: dict) -> dict:
    return {
        "id": "x",
        "model": "test-model",
        "choices": [
            {
                "index": 0,
                "message": {"role": "assistant", "content": json.dumps(payload)},
            }
        ],
    }


def _install_fake_orchestrator(app, payload: dict) -> None:
    def handler(req: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=_ai_chat_response(payload))

    ai = AIClient(
        base_url="http://fake/v1",
        api_key=None,
        model="test-model",
        transport=httpx.MockTransport(handler),
    )

    class _NoOpRetriever:
        async def lookup_wikipedia(self, **kw):
            return []

        async def lookup_openlibrary(self, **kw):
            return []

    orch = InsightOrchestrator(
        ai=ai,
        retriever_factory=lambda s: _NoOpRetriever(),
        sources_enabled=(),
        model_id="test-model",
        prompt_version="t1",
        max_concurrency=4,
        ai_timeout_s=5.0,
    )
    app.state.ai_orchestrator = orch


@pytest.mark.requires_ai
async def test_token_auth_writes_tenant_id_to_generation_log(
    client_factory, app, session
):
    """End-to-end proof that PR-B's principal.tenant_id flows into PR-C's log."""
    async with client_factory(
        ai_enabled=True,
        ai_auth_mode="token",
        ai_token_secrets=json.dumps({"k1": _SECRET_1}),
        ai_token_issuer=_ISS,
        ai_token_audience=_AUD,
        ai_base_url="http://fake/v1",
        ai_model="test-model",
        skip_auth_overrides=True,
    ) as client:
        _install_fake_orchestrator(
            app,
            {"schema_version": 2, "intro": "From acme.", "confidence": "high"},
        )

        token = _sign_token(
            _good_claims(sub="acme:alice", tenant="acme"),
            secret=_SECRET_1,
            kid="k1",
        )
        headers = {"Authorization": f"Bearer {token}"}

        # Opt the (token-authenticated) subject in.
        r = await client.put(
            "/ai/v1/preferences",
            headers=headers,
            json={"ai_enabled": True},
        )
        assert r.status_code == 200, r.text

        # Lookup triggers an orchestrator.generate() — which writes the log.
        r2 = await client.post(
            "/ai/v1/insights/lookup",
            headers=headers,
            json={
                "identity": {"content_hash": "ch-tok-1"},
                "bundle": {"title": "Acme Book"},
            },
        )
        assert r2.status_code == 200, r2.text

    logs = (
        (await session.execute(select(AIGenerationLog))).scalars().all()
    )
    assert len(logs) >= 1
    assert any(
        log.tenant_id == "acme" and log.subject == "acme:alice" for log in logs
    ), [
        (log.tenant_id, log.subject, log.status) for log in logs
    ]
    # And critically: nothing was logged under the legacy "local" tenant.
    assert not any(log.tenant_id == "local" for log in logs)


@pytest.mark.requires_ai
async def test_token_auth_propagates_request_id_to_generation_log(
    client_factory, app, session
):
    """X-Request-ID set by the client flows through to ai_generation_log."""
    async with client_factory(
        ai_enabled=True,
        ai_auth_mode="token",
        ai_token_secrets=json.dumps({"k1": _SECRET_1}),
        ai_token_issuer=_ISS,
        ai_token_audience=_AUD,
        ai_base_url="http://fake/v1",
        ai_model="test-model",
        skip_auth_overrides=True,
    ) as client:
        _install_fake_orchestrator(
            app,
            {"schema_version": 2, "intro": "From acme.", "confidence": "high"},
        )

        token = _sign_token(_good_claims(), secret=_SECRET_1, kid="k1")
        headers = {
            "Authorization": f"Bearer {token}",
            "X-Request-ID": "rid-test-pr-b",
        }
        r = await client.put(
            "/ai/v1/preferences", headers=headers, json={"ai_enabled": True}
        )
        assert r.status_code == 200

        r2 = await client.post(
            "/ai/v1/insights/lookup",
            headers=headers,
            json={
                "identity": {"content_hash": "ch-rid-1"},
                "bundle": {"title": "Rid Book"},
            },
        )
        assert r2.status_code == 200

    logs = (await session.execute(select(AIGenerationLog))).scalars().all()
    assert any(log.request_id == "rid-test-pr-b" for log in logs), [
        log.request_id for log in logs
    ]
