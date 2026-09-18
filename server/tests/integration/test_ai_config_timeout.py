"""Issue #102: the app sizes its own wait from the server's generation timeout."""

from __future__ import annotations

import base64

import pytest

pytestmark = pytest.mark.requires_ai


def _basic_header(user: str, password: str = "p") -> dict:
    return {"Authorization": "Basic " + base64.b64encode(f"{user}:{password}".encode()).decode()}


async def test_config_advertises_generation_timeout_rounded_up(client_factory):
    async with client_factory(
        ai_enabled=True, ai_base_url="http://x", ai_model="m", ai_timeout_s=42.5
    ) as client:
        r = await client.get("/ai/v1/config", headers=_basic_header("alice"))
    assert r.status_code == 200
    assert r.json()["generation_timeout_s"] == 43


async def test_config_generation_timeout_defaults_to_120(client_factory):
    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        r = await client.get("/ai/v1/config", headers=_basic_header("alice"))
    assert r.json()["generation_timeout_s"] == 120


async def test_config_generation_timeout_present_even_when_unconfigured(client_factory):
    """AI enabled without a provider still mounts /config; the timeout is a
    deploy setting, not a provider property, so it is still reported."""
    async with client_factory(ai_enabled=True) as client:
        r = await client.get("/ai/v1/config", headers=_basic_header("alice"))
    assert r.status_code == 200
    assert r.json()["configured"] is False
    assert r.json()["generation_timeout_s"] == 120
