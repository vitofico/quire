"""Issue #102: the app sizes its own wait from the server's generation timeout."""

from __future__ import annotations

import base64

import pytest

pytestmark = [pytest.mark.requires_ai, pytest.mark.ci_lane("ai_only")]


def _basic_header(user: str, password: str = "p") -> dict:
    return {"Authorization": "Basic " + base64.b64encode(f"{user}:{password}".encode()).decode()}


@pytest.mark.parametrize(
    "env,expected,configured",
    [
        pytest.param(
            {"ai_timeout_s": 42.5}, {"generation_timeout_s": 43}, True, id="generation-ceiling"
        ),
        pytest.param(
            {"ai_timeout_s": 42.0}, {"generation_timeout_s": 42}, True, id="generation-integer"
        ),
        pytest.param(
            {"ai_profile_timeout_s": 45.5}, {"profile_timeout_s": 46}, True, id="profile-ceiling"
        ),
        pytest.param(
            {}, {"generation_timeout_s": 120, "profile_timeout_s": 90}, True, id="defaults"
        ),
        pytest.param(
            {}, {"generation_timeout_s": 120, "profile_timeout_s": 90}, False, id="unconfigured"
        ),
    ],
)
async def test_issue_102_config_advertises_timeouts(
    client_factory, monkeypatch, env, expected, configured
):
    for name in ("AI_TIMEOUT_S", "AI_PROFILE_TIMEOUT_S", "AI_BASE_URL", "AI_MODEL"):
        monkeypatch.delenv(f"QUIRE_SERVER_{name}", raising=False)
    provider = {"ai_base_url": "http://x", "ai_model": "m"} if configured else {}
    async with client_factory(ai_enabled=True, **(provider | env)) as client:
        response = await client.get("/ai/v1/config", headers=_basic_header("alice"))
    assert response.status_code == 200
    body = response.json()
    assert body["configured"] is configured
    assert {field: body[field] for field in expected} == expected
