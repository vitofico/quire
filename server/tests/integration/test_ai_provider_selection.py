"""Provider selection is account-scoped, endpoint-specific and secret-free."""

import base64
import json

import pytest

from quire_server.core.ai.health_state import AiHealthState

pytestmark = pytest.mark.requires_ai


def auth(user):
    return {"Authorization": "Basic " + base64.b64encode(f"{user}:p".encode()).decode()}


async def test_provider_selection(client_factory, app, configure_ai, monkeypatch, session):
    monkeypatch.setenv("TEST_OPENAI_KEY", "synthetic-provider-key")
    profiles = json.dumps(
        [
            {
                "id": "openai",
                "label": "GPT-6.1 Terra",
                "base_url": "https://api.openai.com/v1",
                "model": "gpt-6.1-terra",
                "key_env": "TEST_OPENAI_KEY",
            }
        ]
    )
    context = client_factory(
        ai_enabled=True, ai_base_url="http://default/v1", ai_model="haiku", ai_providers=profiles
    )
    default = configure_ai(app, {"intro": "Synthetic insight", "confidence": "low"})
    selected = default.with_provider(
        ai=default.ai, model_id="openai:gpt-6.1-terra", health_state=AiHealthState()
    )
    app.state.ai_provider_orchestrators = {"openai": selected}
    async with context as client:
        response = await client.get("/ai/v1/config", headers=auth("provider-alice"))
        assert [p["id"] for p in response.json()["providers"]] == ["default", "openai"]
        assert "synthetic-provider-key" not in response.text
        assert response.headers["cache-control"] == "private, no-store"
        changed = await client.put(
            "/ai/v1/preferences",
            headers=auth("provider-alice"),
            json={"ai_enabled": True, "provider_id": "openai"},
        )
        assert changed.json()["provider_id"] == "openai"
        # Legacy clients changing style must preserve the provider preference.
        await client.put(
            "/ai/v1/preferences",
            headers=auth("provider-alice"),
            json={"style": {"tone": "casual"}, "provider_id": None},
        )
        config = (await client.get("/ai/v1/config", headers=auth("provider-alice"))).json()
        assert config["provider_id"] == "openai"
        assert config["model_id"] == "openai:gpt-6.1-terra"
        assert config["base_url_host"] == "api.openai.com"
        assert (await client.get("/ai/v1/config", headers=auth("provider-bob"))).json()[
            "provider_id"
        ] == "default"
        body = {
            "identity": {"metadata_id": "isbn:9780140328721"},
            "bundle": {"title": "Synthetic book", "author": "Synthetic author"},
        }
        result = await client.post(
            "/ai/v1/insights/lookup", headers=auth("provider-alice"), json=body
        )
        assert result.status_code == 200, result.text
        assert result.json()["model_id"] == "openai:gpt-6.1-terra"
        rejected = await client.put(
            "/ai/v1/preferences",
            headers=auth("provider-alice"),
            json={"provider_id": "not-configured"},
        )
        assert rejected.status_code == 422
        assert (await client.get("/ai/v1/preferences", headers=auth("provider-alice"))).json()[
            "provider_id"
        ] == "openai"
        app.state.ai_provider_orchestrators = {}
        unavailable = await client.post(
            "/ai/v1/insights/lookup", headers=auth("provider-alice"), json=body
        )
        assert unavailable.status_code == 409
        assert unavailable.json()["detail"] == "ai_provider_unavailable"
        reset = await client.put(
            "/ai/v1/preferences", headers=auth("provider-alice"), json={"provider_id": "default"}
        )
        assert reset.json()["provider_id"] == "default"


def test_provider_clones_share_limits_and_locks(monkeypatch):
    from quire_server.config import get_settings
    from quire_server.main import create_app

    monkeypatch.setenv("QUIRE_SERVER_AI_BASE_URL", "http://default/v1")
    monkeypatch.setenv("QUIRE_SERVER_AI_MODEL", "haiku")
    monkeypatch.setenv("TEST_OPENAI_KEY", "synthetic-provider-key")
    monkeypatch.setenv(
        "QUIRE_SERVER_AI_PROVIDERS",
        json.dumps(
            [
                {
                    "id": "openai",
                    "label": "Terra",
                    "base_url": "https://api.openai.com/v1",
                    "model": "gpt-6.1-terra",
                    "key_env": "TEST_OPENAI_KEY",
                }
            ]
        ),
    )
    get_settings.cache_clear()
    try:
        app = create_app()
        baseline = app.state.ai_orchestrator
        selected = app.state.ai_provider_orchestrators["openai"]
        for attribute in ("_sem", "_bucket", "_locks", "_profile_locks", "_promote_counter"):
            assert getattr(selected, attribute) is getattr(baseline, attribute)
        assert selected.model_id != baseline.model_id
        assert selected.ai is not baseline.ai
    finally:
        get_settings.cache_clear()
