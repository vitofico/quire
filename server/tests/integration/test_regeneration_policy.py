"""Rejected regeneration must preserve the shared cache for every reader."""

import base64
from datetime import UTC, datetime

import httpx
import pytest
from sqlalchemy import select
from sqlalchemy.ext.asyncio import async_sessionmaker

from quire_server.core.ai.monthly_policy import MonthlyPolicy, PolicySettings
from quire_server.db.models import BookInsight

pytestmark = pytest.mark.requires_ai


def credentials(user):
    return {"Authorization": "Basic " + base64.b64encode(f"{user}:p".encode()).decode()}


@pytest.mark.parametrize("policy_status,expected", [(200, 429), (503, 503)])
async def test_rejected_regeneration_preserves_shared_insight(
    client_factory, app, configure_ai, session, engine, monkeypatch, policy_status, expected
):
    now = datetime(2026, 10, 25, tzinfo=UTC)

    def policy_handler(request):
        assert request.headers["authorization"] == "Bearer " + "x" * 32
        return httpx.Response(
            policy_status,
            json={
                "version": 1,
                "account_id": "alice",
                "expires_at": int(now.timestamp()) + 120,
                "limits": {"monthly_jobs": 0},
            },
        )

    async with client_factory(
        ai_enabled=True, ai_base_url="http://fake", ai_model="synthetic"
    ) as client:
        orch = configure_ai(
            app, {"schema_version": 2, "intro": "Shared insight", "confidence": "low"}
        )
        await client.put(
            "/ai/v1/preferences", headers=credentials("alice"), json={"ai_enabled": True}
        )
        payload = {
            "identity": {"content_hash": "shared-regeneration-policy"},
            "bundle": {"title": "Synthetic shared book"},
        }
        original = await client.post(
            "/ai/v1/insights/lookup", headers=credentials("alice"), json=payload
        )
        assert original.status_code == 200, original.text
        provider_calls = []

        async def unexpected_provider_call(*args, **kwargs):
            provider_calls.append(True)
            raise AssertionError("Rejected regeneration must not call the model provider")

        monkeypatch.setattr(orch.ai, "chat_structured", unexpected_provider_call)
        async with httpx.AsyncClient(
            transport=httpx.MockTransport(policy_handler)
        ) as policy_client:
            orch._monthly_policy = MonthlyPolicy(
                PolicySettings(url="http://policy", token="x" * 32),
                policy_client,
                async_sessionmaker(engine),
                clock=lambda: now,
            )
            rejected = await client.post(
                "/ai/v1/insights/regenerate",
                headers=credentials("alice"),
                json=payload | {"reason": "Synthetic regeneration request"},
            )
        assert rejected.status_code == expected, rejected.text
        rows = (await session.execute(select(BookInsight))).scalars().all()
        assert len(rows) == 1
        assert rows[0].superseded_at is None
        other_reader = await client.post(
            "/ai/v1/insights/get",
            headers=credentials("bob"),
            json={"identity": payload["identity"]},
        )
        assert other_reader.status_code == 200, other_reader.text
        assert other_reader.json()["payload"]["intro"] == "Shared insight"
        assert provider_calls == []
