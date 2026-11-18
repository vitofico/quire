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


@pytest.mark.parametrize(
    "raw,expected",
    [
        pytest.param(None, "dev", id="unset"),
        pytest.param("2026.09.22.250", "2026.09.22.250", id="calver"),
        pytest.param("abc1234", "abc1234", id="commit-sha"),
    ],
)
async def test_reported_version(monkeypatch, raw, expected):
    from quire_server.config import Settings

    if raw is not None:
        monkeypatch.setenv("QUIRE_SERVER_VERSION", raw)
    assert Settings().version == expected

    app = _create_app()
    assert app.version == expected
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        response = await client.get("/health")
    assert response.status_code == 200
    assert response.json()["version"] == expected
