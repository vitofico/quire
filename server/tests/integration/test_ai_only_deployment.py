"""An AI-only app can persist insights without the progress migration branch."""

from __future__ import annotations

import base64
import json

import httpx
import pytest
from sqlalchemy import select
from sqlalchemy.ext.asyncio import create_async_engine

from quire_server.config import get_settings
from quire_server.db.models import AIGenerationLog, BookInsight

from .deployment_shapes import assert_partial_schema

pytestmark = pytest.mark.ci_lane("ai_only")


async def test_ai_only_lookup_without_progress_tables(
    partial_postgres_url, monkeypatch, cwa_transport, managed_app_factory
):
    heads = await assert_partial_schema(partial_postgres_url, enabled="ai", disabled="progress")
    for key, value in {
        "DATABASE_URL": partial_postgres_url,
        "CWA_BASE_URL": "http://test-cwa",
        "PROGRESS_ENABLED": "false",
        "AI_ENABLED": "true",
        "AUTH_BACKEND": "calibreweb",
        "AI_AUTH_MODE": "basic",
        "AI_BASE_URL": "https://model.example.test/v1",
        "AI_MODEL": "ai-only-fixture",
        "AI_SOURCES": "",
    }.items():
        monkeypatch.setenv(f"QUIRE_SERVER_{key}", value)
    get_settings.cache_clear()

    model_calls = []
    intro = "An insight from the AI-only deployment."
    content_hash = "ai-only-fixture"

    def provider(request):
        assert request.url == "https://model.example.test/v1/chat/completions"
        model_calls.append(request.method)
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "message": {
                            "content": json.dumps(
                                {"schema_version": 2, "intro": intro, "confidence": "low"}
                            )
                        }
                    }
                ]
            },
        )

    app = managed_app_factory()
    engine = create_async_engine(partial_postgres_url)
    try:
        monkeypatch.setattr(app.state.ai_client, "_transport", httpx.MockTransport(provider))
        async with (
            httpx.AsyncClient(transport=cwa_transport) as cwa,
            httpx.AsyncClient(
                transport=httpx.ASGITransport(app=app), base_url="http://test"
            ) as client,
        ):
            monkeypatch.setattr(app.state.auth_validator, "_client", cwa)
            headers = {"Authorization": "Basic " + base64.b64encode(b"alice:alicepass").decode()}

            health = await client.get("/health")
            assert health.status_code == 200
            assert health.json()["modes"] == ["ai"]
            ready = await client.get("/readyz")
            assert ready.status_code == 200, ready.text
            assert ready.json()["heads_applied"] == heads

            for route in (
                "/sync/v1/progress",
                "/sync/v1/highlights",
                "/library/v1/items",
                "/library/v1/stats",
            ):
                assert (await client.get(route, headers=headers)).status_code == 404, route

            preferences = await client.put(
                "/ai/v1/preferences", headers=headers, json={"ai_enabled": True}
            )
            assert preferences.status_code == 200, preferences.text
            lookup = await client.post(
                "/ai/v1/insights/lookup",
                headers=headers,
                json={
                    "identity": {"content_hash": content_hash},
                    "bundle": {"title": "Synthetic fixture book"},
                },
            )
            assert lookup.status_code == 200, lookup.text
            assert lookup.json()["payload"]["intro"] == intro

        assert model_calls == ["POST"]
        async with engine.connect() as connection:
            audit = await connection.execute(
                select(AIGenerationLog.status, AIGenerationLog.book_insight_id)
                .join(BookInsight, BookInsight.id == AIGenerationLog.book_insight_id)
                .where(AIGenerationLog.subject == "alice", BookInsight.content_hash == content_hash)
            )
            status, insight_id = audit.one()
        assert status == "miss"
        assert insight_id is not None
    finally:
        await engine.dispose()
        get_settings.cache_clear()
