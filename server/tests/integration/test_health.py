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
    # Phase 0 branch heads, sorted alphabetically:
    #   * ai_007 (F-1, added `book_insights.identity_hash_version`)
    #   * auth_001 (S-1, added `native_users` + `native_sessions`; the
    #     ``auth`` branch is unconditionally upgraded — see
    #     `scripts/migrate.py` and `health._required_heads`)
    #   * progress_003 (F-1, added `identity_hash_version` to
    #     `documents` + `library_items`)
    assert body["heads_applied"] == ["ai_007", "auth_001", "progress_003"]


async def test_old_sync_health_path_is_404(app_under_test):
    """We deliberately drop /sync/v1/healthz to force cluster manifests to bump."""
    transport = ASGITransport(app=app_under_test)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        r = await client.get("/sync/v1/healthz")
    assert r.status_code == 404
