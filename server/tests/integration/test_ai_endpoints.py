"""Integration tests for /ai/v1 endpoints.

Uses a real Postgres database (via testcontainers) and a fake AI provider
injected into the InsightOrchestrator so no real LLM calls are made.
"""

from __future__ import annotations

import base64
import json

import httpx
import pytest
from sqlalchemy import select

from opds_sync.core.ai.client import AIClient
from opds_sync.core.ai.service import InsightOrchestrator
from opds_sync.db.models import BookInsight


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _basic_header(user: str, password: str = "p") -> dict:
    return {
        "Authorization": "Basic "
        + base64.b64encode(f"{user}:{password}".encode()).decode()
    }


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


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


@pytest.fixture
def configure_ai():
    """Return a helper that installs a fake AI orchestrator on app.state."""

    def _apply(app, fake_ai_payload: dict, sources_enabled: tuple[str, ...] = ()):
        def fake_handler(req: httpx.Request) -> httpx.Response:
            return httpx.Response(200, json=_ai_chat_response(fake_ai_payload))

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

        orch = InsightOrchestrator(
            ai=ai,
            retriever_factory=lambda s: _NoOpRetriever(),
            sources_enabled=sources_enabled,
            model_id="test-model",
            prompt_version="t1",
            max_concurrency=4,
            ai_timeout_s=5.0,
        )
        app.state.ai_orchestrator = orch
        return orch

    return _apply


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------


async def test_config_endpoint_when_disabled(client_factory):
    async with client_factory(ai_enabled=False) as client:
        r = await client.get("/ai/v1/config", headers=_basic_header("alice"))
    assert r.status_code == 200
    body = r.json()
    assert body["configured"] is False
    assert body["base_url_host"] is None
    assert body["model_id"] is None
    assert body["sources_enabled"] == []
    assert body["daily_budget"] == 200
    assert body["regen_daily_limit"] == 3


async def test_config_endpoint_when_enabled(client_factory):
    async with client_factory(
        ai_enabled=True,
        ai_base_url="http://ollama.lan:11434/v1",
        ai_model="llama3.1:8b",
    ) as client:
        r = await client.get("/ai/v1/config", headers=_basic_header("alice"))
    assert r.status_code == 200
    body = r.json()
    assert body["configured"] is True
    assert body["base_url_host"] == "ollama.lan"
    assert body["model_id"] == "llama3.1:8b"
    assert body["daily_budget"] == 200
    assert body["regen_daily_limit"] == 3


async def test_lookup_blocked_when_not_opted_in(client_factory, configure_ai, app):
    async with client_factory(
        ai_enabled=True, ai_base_url="http://x", ai_model="m"
    ) as client:
        # Now that client_factory has populated app, install the fake AI.
        configure_ai(app, {"schema_version": 1, "summary": "ok", "confidence": "low"})
        r = await client.post(
            "/ai/v1/insights/lookup",
            headers=_basic_header("alice"),
            json={
                "identity": {"content_hash": "ch1"},
                "bundle": {"title": "Foundation"},
            },
        )
    assert r.status_code == 409
    assert r.json()["detail"] == "not_opted_in"


async def test_lookup_generates_then_get_serves_from_cache(
    client_factory, configure_ai, app, session
):
    async with client_factory(
        ai_enabled=True, ai_base_url="http://x", ai_model="m"
    ) as client:
        configure_ai(
            app,
            {"schema_version": 1, "summary": "Foundational sci-fi.", "confidence": "high"},
        )

        # Opt alice in.
        r = await client.put(
            "/ai/v1/preferences",
            headers=_basic_header("alice"),
            json={"ai_enabled": True},
        )
        assert r.status_code == 200

        body = {
            "identity": {"metadata_id": "9780553293357", "content_hash": "ch1"},
            "bundle": {"title": "Foundation", "author": "Isaac Asimov"},
        }

        # Alice generates an insight.
        r1 = await client.post(
            "/ai/v1/insights/lookup", headers=_basic_header("alice"), json=body
        )
        assert r1.status_code == 200
        assert r1.json()["payload"]["summary"] == "Foundational sci-fi."

        # Bob is not opted in: lookup must 409.
        r2 = await client.post(
            "/ai/v1/insights/lookup", headers=_basic_header("bob"), json=body
        )
        assert r2.status_code == 409
        assert r2.json()["detail"] == "not_opted_in"

        # GET path serves from cache without opt-in.
        r3 = await client.post(
            "/ai/v1/insights/get",
            headers=_basic_header("bob"),
            json={"identity": {"metadata_id": "9780553293357", "content_hash": "ch1"}},
        )
        assert r3.status_code == 200
        assert r3.json()["payload"]["summary"] == "Foundational sci-fi."


async def test_invalidate_drops_cache(client_factory, configure_ai, app, session):
    async with client_factory(
        ai_enabled=True, ai_base_url="http://x", ai_model="m"
    ) as client:
        configure_ai(app, {"schema_version": 1, "summary": "v1", "confidence": "low"})

        await client.put(
            "/ai/v1/preferences",
            headers=_basic_header("alice"),
            json={"ai_enabled": True},
        )

        await client.post(
            "/ai/v1/insights/lookup",
            headers=_basic_header("alice"),
            json={
                "identity": {"content_hash": "ch-inv"},
                "bundle": {"title": "X"},
            },
        )

        rows_before = (await session.execute(select(BookInsight))).scalars().all()
        assert len(rows_before) >= 1

        r = await client.post(
            "/ai/v1/insights/invalidate",
            headers=_basic_header("alice"),
            json={"identity": {"content_hash": "ch-inv"}},
        )
        assert r.status_code == 200
        assert r.json()["deleted"] >= 1

        r2 = await client.post(
            "/ai/v1/insights/get",
            headers=_basic_header("alice"),
            json={"identity": {"content_hash": "ch-inv"}},
        )
        assert r2.status_code == 404
