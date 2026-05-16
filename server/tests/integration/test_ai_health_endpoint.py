"""Integration tests for GET /ai/v1/health.

These tests run the full FastAPI app via ``client_factory`` so the routing,
mode-gating, lazy-import boundary, and orchestrator wiring are all exercised
the same way they will be in production.
"""

from __future__ import annotations

import json

import httpx
import pytest

from opds_sync.core.ai.client import (
    AIClient,
    ProviderRejected,
    ProviderTimeout,
    ProviderUnreachable,
)
from opds_sync.core.ai.health_state import AiHealthState
from opds_sync.core.ai.service import InsightOrchestrator

# All tests in this file hit /ai/v1/* and so require the ai router.
pytestmark = pytest.mark.requires_ai


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _basic_header(user: str, password: str = "p") -> dict:
    import base64

    return {"Authorization": "Basic " + base64.b64encode(f"{user}:{password}".encode()).decode()}


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


def _install_fake_ai(
    app,
    *,
    fake_handler,
    sources_enabled: tuple[str, ...] = (),
) -> InsightOrchestrator:
    """Replace the orchestrator with one whose AIClient uses ``fake_handler``.

    The orchestrator created by ``main.create_app`` is wired to a real
    ``AIClient`` against the configured base URL. For tests we substitute one
    that talks to a MockTransport — but we keep the same ``app.state.ai_health``
    holder that ``main.py`` created, so the health endpoint reads from the
    same store the orchestrator writes to.
    """
    ai = AIClient(
        base_url="http://fake/v1",
        api_key=None,
        model="test-model",
        transport=httpx.MockTransport(fake_handler),
    )

    class _NoOpRetriever:
        async def lookup_wikipedia(self, **kw):
            return []

        async def lookup_openlibrary(self, **kw):
            return []

    # Reuse the holder created in main.py so the endpoint sees the same state.
    health: AiHealthState = app.state.ai_health
    orch = InsightOrchestrator(
        ai=ai,
        retriever_factory=lambda s: _NoOpRetriever(),
        sources_enabled=sources_enabled,
        model_id="test-model",
        prompt_version="t1",
        max_concurrency=4,
        ai_timeout_s=5.0,
        health_state=health,
    )
    app.state.ai_orchestrator = orch
    return orch


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------


async def test_health_404_when_ai_disabled(client_factory):
    async with client_factory(ai_enabled=False) as client:
        r = await client.get("/ai/v1/health")
    assert r.status_code == 404


async def test_health_200_when_ai_enabled_unconfigured(client_factory):
    """AI enabled but base_url/model missing: endpoint mounts; snapshot is all-null."""
    async with client_factory(ai_enabled=True, ai_sources="") as client:
        r = await client.get("/ai/v1/health")
    assert r.status_code == 200
    body = r.json()
    assert body["provider_reachable"] is None
    assert body["provider_last_checked_at"] is None
    assert body["model_id"] is None
    assert body["last_failure_at"] is None
    assert body["last_failure_class"] is None
    # No retrieval sources configured in this mode.
    assert body["retrieval_sources"] == []


async def test_health_no_auth_required(client_factory):
    """Operational endpoint — no Authorization header should still return 200."""
    async with client_factory(ai_enabled=True) as client:
        r = await client.get("/ai/v1/health")  # no header
    assert r.status_code == 200


async def test_health_seeds_configured_sources(client_factory):
    """Sources from ai_sources appear as null-state rows before any lookup."""
    async with client_factory(
        ai_enabled=True,
        ai_base_url="http://x",
        ai_model="m",
        ai_sources="wikipedia,openlibrary",
    ) as client:
        r = await client.get("/ai/v1/health")
    assert r.status_code == 200
    body = r.json()
    names = {s["name"]: s for s in body["retrieval_sources"]}
    assert set(names) == {"wikipedia", "openlibrary"}
    assert names["wikipedia"]["reachable"] is None
    assert names["openlibrary"]["reachable"] is None


async def test_health_provider_success(client_factory, app, session):
    """A successful chat_structured call flips provider_reachable to True."""

    def ok_handler(req: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json=_ai_chat_response({"schema_version": 2, "intro": "ok", "confidence": "low"}),
        )

    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        _install_fake_ai(app, fake_handler=ok_handler)

        # Opt-in + lookup to exercise the orchestrator.
        await client.put(
            "/ai/v1/preferences",
            headers=_basic_header("alice"),
            json={"ai_enabled": True},
        )
        await client.post(
            "/ai/v1/insights/lookup",
            headers=_basic_header("alice"),
            json={
                "identity": {"content_hash": "ch-ok"},
                "bundle": {"title": "Foundation"},
            },
        )

        r = await client.get("/ai/v1/health")
    assert r.status_code == 200
    body = r.json()
    assert body["provider_reachable"] is True
    assert body["provider_last_checked_at"] is not None
    assert body["model_id"] == "test-model"
    assert body["last_failure_at"] is None
    assert body["last_failure_class"] is None


async def test_health_provider_timeout_classified(client_factory, app, session):
    """httpx.ReadTimeout → ProviderTimeout → last_failure_class='ProviderTimeout'."""

    def timeout_handler(req: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("simulated")

    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        _install_fake_ai(app, fake_handler=timeout_handler)
        await client.put(
            "/ai/v1/preferences",
            headers=_basic_header("alice"),
            json={"ai_enabled": True},
        )
        # The orchestrator re-raises the provider exception; ASGITransport
        # propagates app exceptions by default, so we catch here and confirm
        # the side effect on the health endpoint below.
        with pytest.raises(ProviderTimeout):
            await client.post(
                "/ai/v1/insights/lookup",
                headers=_basic_header("alice"),
                json={
                    "identity": {"content_hash": "ch-to"},
                    "bundle": {"title": "X"},
                },
            )

        r = await client.get("/ai/v1/health")
    body = r.json()
    assert body["provider_reachable"] is False
    assert body["provider_last_checked_at"] is not None
    assert body["last_failure_at"] is not None
    assert body["last_failure_class"] == "ProviderTimeout"


async def test_health_provider_502_classified(client_factory, app, session):
    """5xx → ProviderUnreachable → last_failure_class='ProviderUnreachable'."""

    def err_handler(req: httpx.Request) -> httpx.Response:
        return httpx.Response(502, text="bad gateway")

    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        _install_fake_ai(app, fake_handler=err_handler)
        await client.put(
            "/ai/v1/preferences",
            headers=_basic_header("alice"),
            json={"ai_enabled": True},
        )
        with pytest.raises(ProviderUnreachable):
            await client.post(
                "/ai/v1/insights/lookup",
                headers=_basic_header("alice"),
                json={
                    "identity": {"content_hash": "ch-502"},
                    "bundle": {"title": "X"},
                },
            )

        r = await client.get("/ai/v1/health")
    body = r.json()
    assert body["provider_reachable"] is False
    assert body["last_failure_class"] == "ProviderUnreachable"


async def test_health_provider_400_classified(client_factory, app, session):
    """4xx (other than 429) → ProviderRejected."""

    def reject_handler(req: httpx.Request) -> httpx.Response:
        return httpx.Response(400, text="nope")

    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        _install_fake_ai(app, fake_handler=reject_handler)
        await client.put(
            "/ai/v1/preferences",
            headers=_basic_header("alice"),
            json={"ai_enabled": True},
        )
        with pytest.raises(ProviderRejected):
            await client.post(
                "/ai/v1/insights/lookup",
                headers=_basic_header("alice"),
                json={
                    "identity": {"content_hash": "ch-400"},
                    "bundle": {"title": "X"},
                },
            )

        r = await client.get("/ai/v1/health")
    body = r.json()
    assert body["provider_reachable"] is False
    assert body["last_failure_class"] == "ProviderRejected"


async def test_health_recovery_clears_failure(client_factory, app, session):
    """Failure followed by success clears last_failure_* in the health snapshot."""
    handler_state = {"mode": "fail"}

    def flip_handler(req: httpx.Request) -> httpx.Response:
        if handler_state["mode"] == "fail":
            return httpx.Response(502, text="bad")
        return httpx.Response(
            200,
            json=_ai_chat_response({"schema_version": 2, "intro": "ok", "confidence": "low"}),
        )

    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        _install_fake_ai(app, fake_handler=flip_handler)
        await client.put(
            "/ai/v1/preferences",
            headers=_basic_header("alice"),
            json={"ai_enabled": True},
        )

        # First call: fails.
        with pytest.raises(ProviderUnreachable):
            await client.post(
                "/ai/v1/insights/lookup",
                headers=_basic_header("alice"),
                json={
                    "identity": {"content_hash": "ch-rec-1"},
                    "bundle": {"title": "X"},
                },
            )
        r1 = await client.get("/ai/v1/health")
        assert r1.json()["provider_reachable"] is False

        # Switch to success and call again with a DIFFERENT identity so we
        # don't hit the cache.
        handler_state["mode"] = "ok"
        await client.post(
            "/ai/v1/insights/lookup",
            headers=_basic_header("alice"),
            json={
                "identity": {"content_hash": "ch-rec-2"},
                "bundle": {"title": "X"},
            },
        )

        r = await client.get("/ai/v1/health")
    body = r.json()
    assert body["provider_reachable"] is True
    assert body["last_failure_at"] is None
    assert body["last_failure_class"] is None


async def test_health_cache_hit_does_not_update_timestamp(client_factory, app, session):
    """Second lookup that hits the insight cache must not touch health state."""

    def ok_handler(req: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json=_ai_chat_response({"schema_version": 2, "intro": "cached", "confidence": "low"}),
        )

    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        _install_fake_ai(app, fake_handler=ok_handler)
        await client.put(
            "/ai/v1/preferences",
            headers=_basic_header("alice"),
            json={"ai_enabled": True},
        )

        body = {
            "identity": {"metadata_id": "id1", "content_hash": "ch-cache"},
            "bundle": {"title": "X"},
        }
        await client.post("/ai/v1/insights/lookup", headers=_basic_header("alice"), json=body)
        r1 = await client.get("/ai/v1/health")
        ts1 = r1.json()["provider_last_checked_at"]
        assert ts1 is not None

        # Second lookup hits cache.
        await client.post("/ai/v1/insights/lookup", headers=_basic_header("alice"), json=body)

        r2 = await client.get("/ai/v1/health")
    ts2 = r2.json()["provider_last_checked_at"]
    assert ts2 == ts1, "cache hit must not touch provider_last_checked_at"
