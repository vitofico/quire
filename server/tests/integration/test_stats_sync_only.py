"""Regression test for issue #87 — stats endpoint in a sync-only deployment.

A sync-only server runs with ``QUIRE_SERVER_AI_ENABLED=false``, so the deploy
migrator never applies the ``ai`` alembic branch and ``book_insights`` /
``book_themes`` do not exist. The library router, however, mounts on
``progress_enabled`` (see ``main.py``), so ``GET /library/v1/stats`` is reachable
in that mode. Before the fix, ``get_stats`` unconditionally joined those AI-only
tables and Postgres raised ``relation "book_themes" does not exist`` → a blanket
HTTP 500. The endpoint must instead return 200 with ``top_themes=[]`` while still
reporting the non-AI stats.

This module deliberately provisions its OWN Postgres migrated with
``ai_enabled=False``. The shared session-scoped fixtures in ``conftest.py`` always
apply the ``ai`` branch (``run_migrations(..., ai_enabled=True)``), which is
exactly why the whole existing suite never reproduced this bug.
"""

from __future__ import annotations

import base64
import uuid
from collections.abc import Iterator
from datetime import UTC, datetime

import httpx
import pytest
from alembic.config import Config as AlembicConfig
from httpx import ASGITransport, AsyncClient
from sqlalchemy import text
from sqlalchemy.ext.asyncio import create_async_engine
from testcontainers.postgres import PostgresContainer


def _basic(user: str, pw: str) -> dict[str, str]:
    token = base64.b64encode(f"{user}:{pw}".encode()).decode("ascii")
    return {"Authorization": f"Basic {token}"}


def _put_body(**overrides) -> dict:
    base = {
        "metadata_id": "md-1",
        "content_hash": "ch-1",
        "title": "Ancillary Justice",
        "authors": ["Ann Leckie"],
        "series_name": None,
        "series_index": None,
        "isbn": None,
        "language": None,
        "subjects": [],
        "opds_href": None,
    }
    base.update(overrides)
    return {"item": base}


def _progress_body(content_hash: str, percent: float, finished: bool, when: str) -> dict:
    return {
        "items": [
            {
                "document": {"metadata_id": None, "content_hash": content_hash},
                "locator": "{}",
                "percent": percent,
                "client_updated_at": when,
                "finished_at": when if finished else None,
            }
        ]
    }


@pytest.fixture(scope="module")
def sync_only_db() -> Iterator[str]:
    """A fresh Postgres migrated WITHOUT the ``ai`` branch (sync-only deploy)."""
    with PostgresContainer("postgres:16-alpine") as pg:
        sync_url = pg.get_connection_url()
        async_url = sync_url.replace("postgresql+psycopg2://", "postgresql+asyncpg://")
        cfg = AlembicConfig("alembic.ini")
        cfg.set_main_option("sqlalchemy.url", async_url)
        from scripts.migrate import run_migrations

        # progress on (library router + library_items table), ai OFF: this is
        # the deployment shape that triggers issue #87.
        run_migrations(cfg, progress_enabled=True, ai_enabled=False)
        yield async_url


@pytest.fixture
def sync_only_app(sync_only_db, monkeypatch, cwa_transport):
    """A sync-only FastAPI app (AI disabled) wired to the ai-branch-less DB."""
    monkeypatch.setenv("QUIRE_SERVER_DATABASE_URL", sync_only_db)
    monkeypatch.setenv("QUIRE_SERVER_CWA_BASE_URL", "http://test-cwa")
    monkeypatch.setenv("QUIRE_SERVER_PROGRESS_ENABLED", "true")
    monkeypatch.setenv("QUIRE_SERVER_AI_ENABLED", "false")

    from quire_server.config import get_settings

    get_settings.cache_clear()

    from quire_server.core.auth import CalibreAuthValidator
    from quire_server.core.auth_backend import CalibreWebBasicAuth
    from quire_server.main import create_app

    app = create_app()
    test_client = httpx.AsyncClient(
        transport=cwa_transport, base_url="http://test-cwa", timeout=3.0
    )
    app.state.httpx_client = test_client
    app.state.auth_validator = CalibreAuthValidator(
        client=test_client,
        cwa_base_url="http://test-cwa",
    )
    app.state.auth_backend = CalibreWebBasicAuth(app.state.auth_validator)
    yield app
    # Don't leave the lru-cached sync-only settings (pointing at a container
    # that is about to be torn down) visible to later modules.
    get_settings.cache_clear()


async def test_stats_returns_200_without_ai_tables(sync_only_app, sync_only_db, cwa_users):
    """Issue #87: /library/v1/stats must not 500 when the ai branch is absent."""
    # Guard the premise: the ai-only tables genuinely do not exist here. If a
    # future change to the fixture applied the ai branch, this would mask the
    # regression, so fail loudly instead.
    engine = create_async_engine(sync_only_db, future=True)
    try:
        async with engine.connect() as conn:
            reg = await conn.scalar(text("SELECT to_regclass('public.book_themes')"))
    finally:
        await engine.dispose()
    assert reg is None, "premise broken: the ai branch was applied to the sync-only DB"

    user = f"user-{uuid.uuid4().hex[:8]}"
    cwa_users[user] = "pw"
    headers = _basic(user, "pw")

    transport = ASGITransport(app=sync_only_app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        # Seed a book + finished progress so the NON-AI stats compute real
        # values — proving the fix skips only the theme block, not the rest.
        await c.put("/library/v1/items", json=_put_body(content_hash="a"), headers=headers)
        now = datetime.now(UTC).isoformat()
        await c.post("/sync/v1/progress", json=_progress_body("a", 1.0, True, now), headers=headers)
        r = await c.get("/library/v1/stats", headers=headers)

    assert r.status_code == 200, r.text
    data = r.json()
    assert data["total_books"] == 1
    assert data["finished_count"] == 1
    assert data["top_authors"] == [{"name": "Ann Leckie", "count": 1}]
    # The load-bearing assertion: themes are gated off, not queried.
    assert data["top_themes"] == []
