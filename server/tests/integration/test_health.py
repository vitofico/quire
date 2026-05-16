"""Integration tests for the always-on /health and /readyz endpoints.

PR-A moves these to root paths (no /sync/v1 prefix) and adds a `modes` payload
plus a heads-check on /readyz.
"""

from __future__ import annotations

from httpx import ASGITransport, AsyncClient


async def test_health_returns_200_with_modes(app_under_test):
    transport = ASGITransport(app=app_under_test)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        r = await client.get("/health")
    assert r.status_code == 200
    body = r.json()
    assert body["ready"] is True
    # `modes` reflects whichever flags the test env set. We don't pin the
    # exact contents here — that's covered by test_modes.py — only that the
    # endpoint responds with a list.
    assert isinstance(body["modes"], list)


async def test_readyz_returns_200_when_db_reachable_and_heads_applied(app_under_test):
    transport = ASGITransport(app=app_under_test)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        r = await client.get("/readyz")
    assert r.status_code == 200
    body = r.json()
    assert body["ready"] is True
    # ai branch is now at ai_004 (PR-C → PR4 → PR2 → PR3 chain); PR1
    # materialized the `progress` branch with `progress_001`. With the
    # default-true mode flags, both branch heads are reported, sorted.
    assert body["heads_applied"] == ["ai_004", "progress_001"]


async def test_old_sync_health_path_is_404(app_under_test):
    """We deliberately drop /sync/v1/healthz to force cluster manifests to bump."""
    transport = ASGITransport(app=app_under_test)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        r = await client.get("/sync/v1/healthz")
    assert r.status_code == 404
