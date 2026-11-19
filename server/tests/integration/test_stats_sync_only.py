"""Regression test for issue #87 — stats endpoint in a sync-only deployment.

A sync-only server runs with ``QUIRE_SERVER_AI_ENABLED=false``, so the deploy
migrator never applies the ``ai`` alembic branch and ``book_insights`` /
``book_themes`` do not exist. The library router, however, mounts on
``progress_enabled`` (see ``main.py``), so ``GET /library/v1/stats`` is reachable
in that mode. Before the fix, ``get_stats`` unconditionally joined those AI-only
tables and Postgres raised ``relation "book_themes" does not exist`` → a blanket
HTTP 500. The endpoint must instead return 200 with ``top_themes=[]`` while still
reporting the non-AI stats.

In CI this runs on the same branch-limited database as the sync-only lane.
Standalone pytest runs provision a separate partial deployment.
"""

from __future__ import annotations

import base64
import uuid
from datetime import UTC, datetime

import httpx
import pytest
from httpx import ASGITransport, AsyncClient

from .deployment_shapes import assert_partial_schema

pytestmark = pytest.mark.ci_lane("sync_only")


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


@pytest.fixture
def sync_only_app(partial_postgres_url, monkeypatch, cwa_transport):
    """A sync-only FastAPI app (AI disabled) wired to the ai-branch-less DB."""
    monkeypatch.setenv("QUIRE_SERVER_DATABASE_URL", partial_postgres_url)
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


async def test_stats_returns_200_without_ai_tables(sync_only_app, partial_postgres_url, cwa_users):
    """Issue #87: /library/v1/stats must not 500 when the ai branch is absent."""
    heads = await assert_partial_schema(partial_postgres_url, enabled="progress", disabled="ai")

    user = f"user-{uuid.uuid4().hex[:8]}"
    cwa_users[user] = "pw"
    headers = _basic(user, "pw")

    transport = ASGITransport(app=sync_only_app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        # Seed a book + finished progress so the NON-AI stats compute real
        # values — proving the fix skips only the theme block, not the rest.
        item = await c.put("/library/v1/items", json=_put_body(content_hash="a"), headers=headers)
        assert item.status_code == 200, item.text
        now = datetime.now(UTC).isoformat()
        progress = await c.post(
            "/sync/v1/progress", json=_progress_body("a", 1.0, True, now), headers=headers
        )
        assert progress.status_code == 200, progress.text
        r = await c.get("/library/v1/stats", headers=headers)

        health = await c.get("/health")
        assert health.status_code == 200
        assert health.json()["modes"] == ["progress"]
        ready = await c.get("/readyz")
        assert ready.status_code == 200, ready.text
        assert ready.json()["heads_applied"] == heads
        assert (await c.get("/ai/v1/config", headers=headers)).status_code == 404

    assert r.status_code == 200, r.text
    data = r.json()
    assert data["total_books"] == 1
    assert data["finished_count"] == 1
    assert data["top_authors"] == [{"name": "Ann Leckie", "count": 1}]
    # The load-bearing assertion: themes are gated off, not queried.
    assert data["top_themes"] == []
