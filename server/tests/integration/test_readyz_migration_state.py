"""End-to-end /readyz tests covering migration-state edge cases."""

from __future__ import annotations

import pytest
from httpx import ASGITransport, AsyncClient
from sqlalchemy import text
from sqlalchemy.ext.asyncio import create_async_engine


def _build_app(monkeypatch, postgres_url: str, *, progress: bool, ai: bool):
    monkeypatch.setenv("OPDS_SYNC_DATABASE_URL", postgres_url)
    monkeypatch.setenv("OPDS_SYNC_CWA_BASE_URL", "http://test-cwa")
    monkeypatch.setenv("OPDS_SYNC_PROGRESS_ENABLED", "true" if progress else "false")
    monkeypatch.setenv("OPDS_SYNC_AI_ENABLED", "true" if ai else "false")
    from opds_sync.config import get_settings

    get_settings.cache_clear()
    from opds_sync.main import create_app

    return create_app()


async def _stamp(postgres_url: str, revision: str) -> None:
    """Forcibly set alembic_version to the given revision (single row).

    Bypasses Alembic so we can put the DB into states it wouldn't normally
    reach via legal upgrade paths.
    """
    eng = create_async_engine(postgres_url, future=True)
    async with eng.begin() as conn:
        await conn.execute(text("DELETE FROM alembic_version"))
        await conn.execute(
            text("INSERT INTO alembic_version (version_num) VALUES (:r)"),
            {"r": revision},
        )
    await eng.dispose()


async def _restore_to_0004(postgres_url: str) -> None:
    """Set alembic_version row back to 0004 (the real schema state)."""
    await _stamp(postgres_url, "0004")


@pytest.fixture
async def restore_after(postgres_url: str, alembic_upgrade):
    """Ensure each test ends with the DB stamped back to 0004."""
    yield
    await _restore_to_0004(postgres_url)


async def test_readyz_200_when_at_ai_head(monkeypatch, postgres_url, alembic_upgrade):
    """With ai_001 materialized and ai mode on, the required head is ai@head."""
    app = _build_app(monkeypatch, postgres_url, progress=True, ai=True)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as c:
        r = await c.get("/readyz")
    assert r.status_code == 200
    body = r.json()
    assert body["ready"] is True
    assert body["heads_applied"] == ["ai_001"]


async def test_readyz_503_when_db_below_backbone(
    monkeypatch, postgres_url, alembic_upgrade, restore_after
):
    """DB stamped below backbone; with both modes enabled, required head is
    ai_001 (ai@head) — that's what should be reported missing."""
    await _stamp(postgres_url, "0003")
    app = _build_app(monkeypatch, postgres_url, progress=True, ai=True)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as c:
        r = await c.get("/readyz")
    assert r.status_code == 503
    body = r.json()
    assert body["ready"] is False
    assert "ai_001" in body["missing"]


async def test_readyz_200_with_neither_mode_at_backbone(
    monkeypatch, postgres_url, alembic_upgrade, restore_after
):
    """With neither mode enabled, backbone tip (0004) is the only required head.

    Stamp the DB to 0004 explicitly because the session-scoped fixture brings
    everything up to ai@head; we want to exercise the "fresh sync-only deploy
    that never materialized the ai branch" code path.
    """
    await _stamp(postgres_url, "0004")
    app = _build_app(monkeypatch, postgres_url, progress=False, ai=False)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as c:
        r = await c.get("/readyz")
    assert r.status_code == 200
    body = r.json()
    assert body["modes"] == []
    assert body["heads_applied"] == ["0004"]


async def test_readyz_503_with_neither_mode_below_backbone(
    monkeypatch, postgres_url, alembic_upgrade, restore_after
):
    await _stamp(postgres_url, "0002")
    app = _build_app(monkeypatch, postgres_url, progress=False, ai=False)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as c:
        r = await c.get("/readyz")
    assert r.status_code == 503
    body = r.json()
    assert "0004" in body["missing"]
