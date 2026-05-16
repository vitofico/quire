"""Mode-gated router mounting tests.

Verifies that OPDS_SYNC_PROGRESS_ENABLED / OPDS_SYNC_AI_ENABLED correctly
include or exclude the per-domain routers while keeping /health and /readyz
always mounted.
"""

from __future__ import annotations

import base64

from httpx import ASGITransport, AsyncClient


def _basic_header(user: str, password: str = "x") -> dict[str, str]:
    token = base64.b64encode(f"{user}:{password}".encode()).decode("ascii")
    return {"Authorization": f"Basic {token}"}


def _build_app(monkeypatch, postgres_url: str, *, progress_enabled: bool, ai_enabled: bool):
    """Build a fresh app with the given mode flags."""
    monkeypatch.setenv("OPDS_SYNC_DATABASE_URL", postgres_url)
    monkeypatch.setenv("OPDS_SYNC_CWA_BASE_URL", "http://test-cwa")
    monkeypatch.setenv("OPDS_SYNC_PROGRESS_ENABLED", "true" if progress_enabled else "false")
    monkeypatch.setenv("OPDS_SYNC_AI_ENABLED", "true" if ai_enabled else "false")
    from opds_sync.config import get_settings

    get_settings.cache_clear()
    from opds_sync.main import create_app

    return create_app()


async def test_full_mode_mounts_everything(monkeypatch, postgres_url: str, alembic_upgrade):
    app = _build_app(monkeypatch, postgres_url, progress_enabled=True, ai_enabled=True)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as c:
        h = await c.get("/health")
        assert h.status_code == 200
        assert h.json()["modes"] == ["progress", "ai"]

        # Progress router mounted: any response other than 404 confirms routing.
        prog = await c.get("/sync/v1/progress", headers=_basic_header("alice"))
        assert prog.status_code != 404

        # AI router mounted: any response other than 404 confirms routing.
        ai = await c.get("/ai/v1/config", headers=_basic_header("alice"))
        assert ai.status_code != 404


async def test_sync_only_mode_excludes_ai(monkeypatch, postgres_url: str, alembic_upgrade):
    app = _build_app(monkeypatch, postgres_url, progress_enabled=True, ai_enabled=False)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as c:
        h = await c.get("/health")
        assert h.status_code == 200
        assert h.json()["modes"] == ["progress"]

        # AI namespace entirely unmounted.
        ai = await c.get("/ai/v1/config", headers=_basic_header("alice"))
        assert ai.status_code == 404


async def test_ai_only_mode_excludes_progress(monkeypatch, postgres_url: str, alembic_upgrade):
    app = _build_app(monkeypatch, postgres_url, progress_enabled=False, ai_enabled=True)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as c:
        h = await c.get("/health")
        assert h.status_code == 200
        assert h.json()["modes"] == ["ai"]

        # Sync namespace unmounted.
        prog = await c.get("/sync/v1/progress", headers=_basic_header("alice"))
        assert prog.status_code == 404


async def test_neither_mode_only_mounts_health(monkeypatch, postgres_url: str, alembic_upgrade):
    app = _build_app(monkeypatch, postgres_url, progress_enabled=False, ai_enabled=False)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as c:
        h = await c.get("/health")
        assert h.status_code == 200
        assert h.json()["modes"] == []

        r = await c.get("/readyz")
        assert r.status_code == 200

        # Both router namespaces unmounted.
        prog = await c.get("/sync/v1/progress", headers=_basic_header("alice"))
        assert prog.status_code == 404
        ai = await c.get("/ai/v1/config", headers=_basic_header("alice"))
        assert ai.status_code == 404
