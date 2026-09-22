"""Unit tests for the server's self-reported version.

The running process has no way to tell an operator which build it is: the
FastAPI app has always reported a hardcoded, stale `version="0.3.0"`. The
real value comes from `Settings.version` (env var `QUIRE_SERVER_VERSION`,
default `"dev"`), baked into the image at build time by
`server/Dockerfile` from the commit sha. It flows into the FastAPI app's
own `version` field and into `GET /health`, so `curl /health` or the
OpenAPI schema tells an operator exactly which build is running.

Mirrors the wiring pattern in `test_main_config_warnings.py`: the real
factory runs with the DB and calibre-web URLs stubbed (the engine is built
but never connects), so no Postgres testcontainer is needed here.
"""

from __future__ import annotations

import pytest
from httpx import ASGITransport, AsyncClient

from quire_server.config import get_settings


@pytest.fixture(autouse=True)
def _isolate_env(monkeypatch):
    monkeypatch.delenv("QUIRE_SERVER_VERSION", raising=False)
    monkeypatch.setenv("QUIRE_SERVER_DATABASE_URL", "postgresql+asyncpg://x/y")
    monkeypatch.setenv("QUIRE_SERVER_CWA_BASE_URL", "http://stub")
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


def _create_app():
    from quire_server.main import create_app

    return create_app()


def test_settings_version_defaults_to_dev():
    from quire_server.config import Settings

    assert Settings().version == "dev"


def test_settings_version_env_override(monkeypatch):
    monkeypatch.setenv("QUIRE_SERVER_VERSION", "2026.09.22.250")
    from quire_server.config import Settings

    assert Settings().version == "2026.09.22.250"


def test_app_version_defaults_to_dev():
    app = _create_app()
    assert app.version == "dev"


def test_app_version_follows_settings_version(monkeypatch):
    monkeypatch.setenv("QUIRE_SERVER_VERSION", "abc1234")
    app = _create_app()
    assert app.version == "abc1234"


async def test_health_reports_version_default():
    app = _create_app()
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        r = await client.get("/health")
    assert r.status_code == 200
    assert r.json()["version"] == "dev"


async def test_health_reports_version_override(monkeypatch):
    monkeypatch.setenv("QUIRE_SERVER_VERSION", "abc1234")
    app = _create_app()
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        r = await client.get("/health")
    assert r.json()["version"] == "abc1234"
