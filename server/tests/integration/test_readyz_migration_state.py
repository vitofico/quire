"""End-to-end /readyz tests covering migration-state edge cases."""

from __future__ import annotations

import asyncio

import pytest
from alembic.config import Config as AlembicConfig
from httpx import ASGITransport, AsyncClient
from sqlalchemy import text
from sqlalchemy.ext.asyncio import create_async_engine


def _build_app(monkeypatch, postgres_url: str, *, progress: bool, ai: bool):
    monkeypatch.setenv("QUIRE_SERVER_DATABASE_URL", postgres_url)
    monkeypatch.setenv("QUIRE_SERVER_CWA_BASE_URL", "http://test-cwa")
    monkeypatch.setenv("QUIRE_SERVER_PROGRESS_ENABLED", "true" if progress else "false")
    monkeypatch.setenv("QUIRE_SERVER_AI_ENABLED", "true" if ai else "false")
    from quire_server.config import get_settings

    get_settings.cache_clear()
    from quire_server.main import create_app

    return create_app()


async def _stamp(postgres_url: str, revision: str | tuple[str, ...]) -> None:
    """Forcibly set alembic_version to the given revision(s).

    Accepts a single revision or a tuple of revisions (Alembic stores one
    row per head, so multi-head DBs need a tuple). Bypasses Alembic so we
    can put the DB into states it wouldn't normally reach via legal
    upgrade paths.
    """
    revs = (revision,) if isinstance(revision, str) else tuple(revision)
    eng = create_async_engine(postgres_url, future=True)
    async with eng.begin() as conn:
        await conn.execute(text("DELETE FROM alembic_version"))
        for r in revs:
            await conn.execute(
                text("INSERT INTO alembic_version (version_num) VALUES (:r)"),
                {"r": r},
            )
    await eng.dispose()


async def _restore_to_0004(postgres_url: str) -> None:
    """Set alembic_version rows back to {0004, auth_001} — the canonical
    "backbone + always-on auth branch" state expected by the rest of the
    suite (which depends on the session-scoped alembic_upgrade fixture).
    """
    await _stamp(postgres_url, ("0004", "auth_001"))


@pytest.fixture
async def restore_after(postgres_url: str, alembic_upgrade):
    """Ensure each test ends with the DB stamped back to 0004."""
    yield
    await _restore_to_0004(postgres_url)


async def test_readyz_200_when_at_ai_head(monkeypatch, postgres_url, alembic_upgrade):
    """With ai@head (ai_007) + progress@head (progress_003) materialized,
    /readyz reports both heads. (ai_007 / progress_003 added by Phase 0
    task F-1: server identity-hash schema versioning.)
    """
    # Some earlier test in the session may have downgraded the DB
    # (test_migrate_script.py exercises rollback). Ensure both branches are
    # up to head before asserting on heads_applied. alembic_upgrade is
    # session-scoped, so it doesn't re-run between tests.
    cfg = AlembicConfig("alembic.ini")
    cfg.set_main_option("sqlalchemy.url", postgres_url)
    from scripts.migrate import run_migrations

    await asyncio.to_thread(run_migrations, cfg, progress_enabled=True, ai_enabled=True)

    app = _build_app(monkeypatch, postgres_url, progress=True, ai=True)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as c:
        r = await c.get("/readyz")
    assert r.status_code == 200
    body = r.json()
    assert body["ready"] is True
    # Phase 0, task S-1 added the always-materialized `auth` branch:
    # `auth_001` joins `ai_007` / `progress_003` in `heads_applied`.
    assert body["heads_applied"] == ["ai_007", "auth_001", "progress_003"]


async def test_readyz_503_when_db_below_backbone(
    monkeypatch, postgres_url, alembic_upgrade, restore_after
):
    """DB stamped below backbone; with both modes enabled, required heads
    include ai_007 (ai@head after Phase 0 / F-1) and auth_001 (auth@head
    after Phase 0 / S-1) — both should be reported missing.
    """
    await _stamp(postgres_url, "0003")
    app = _build_app(monkeypatch, postgres_url, progress=True, ai=True)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as c:
        r = await c.get("/readyz")
    assert r.status_code == 503
    body = r.json()
    assert body["ready"] is False
    assert "ai_007" in body["missing"]
    assert "auth_001" in body["missing"]


async def test_readyz_200_with_neither_mode_at_backbone(
    monkeypatch, postgres_url, alembic_upgrade, restore_after
):
    """With neither feature-mode enabled, the required heads collapse to
    {backbone, auth} — `auth` is unconditionally required (Phase 0 / S-1).

    Stamp the DB to ``(0004, auth_001)`` to exercise the "fresh sync-only
    deploy that never materialized the ai or progress branches" code path.
    """
    await _stamp(postgres_url, ("0004", "auth_001"))
    app = _build_app(monkeypatch, postgres_url, progress=False, ai=False)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as c:
        r = await c.get("/readyz")
    assert r.status_code == 200
    body = r.json()
    assert body["modes"] == []
    # ``heads_applied`` lists every row in ``alembic_version`` (sorted).
    # We stamped both to simulate a deploy that explicitly walked the
    # backbone + always-on ``auth`` branch.
    assert body["heads_applied"] == ["0004", "auth_001"]


async def test_readyz_503_with_neither_mode_below_backbone(
    monkeypatch, postgres_url, alembic_upgrade, restore_after
):
    await _stamp(postgres_url, "0002")
    app = _build_app(monkeypatch, postgres_url, progress=False, ai=False)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as c:
        r = await c.get("/readyz")
    assert r.status_code == 503
    body = r.json()
    # Both the backbone tip and the always-on `auth` head are missing.
    assert "auth_001" in body["missing"]
