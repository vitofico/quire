"""Unit tests for ``QUIRE_SERVER_AUTH_BACKEND`` wiring and the cross-config
guard between primary auth and AI auth.

These tests exercise the real ``create_app()`` factory but never touch the
DB (the engine is constructed lazily), so they belong with the other
``test_main_*_validation`` files in tests/unit/.
"""

from __future__ import annotations

import pytest

from quire_server.config import get_settings


@pytest.fixture(autouse=True)
def _isolate_env(monkeypatch):
    """Strip every auth-related env var so each test starts from defaults."""
    for var in (
        "QUIRE_SERVER_AUTH_BACKEND",
        "QUIRE_SERVER_AI_ENABLED",
        "QUIRE_SERVER_AI_AUTH_MODE",
        "QUIRE_SERVER_AI_TOKEN_SECRETS",
        "QUIRE_SERVER_AI_TOKEN_ISSUER",
        "QUIRE_SERVER_AI_TOKEN_AUDIENCE",
    ):
        monkeypatch.delenv(var, raising=False)
    monkeypatch.setenv("QUIRE_SERVER_DATABASE_URL", "postgresql+asyncpg://x/y")
    monkeypatch.setenv("QUIRE_SERVER_CWA_BASE_URL", "http://stub")
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


@pytest.mark.parametrize(
    "native,ai_mode", [(False, "basic"), (True, "off"), (True, "basic"), (True, "token")]
)
async def test_auth_routes_follow_selected_backend(monkeypatch, native, ai_mode):
    # Successful native bearer use also runs through the real primary and AI routes
    # in test_native_backend_ai_accepts_session_token.
    import httpx

    from quire_server.main import create_app

    if native:
        monkeypatch.setenv("QUIRE_SERVER_AUTH_BACKEND", "native")
    monkeypatch.setenv("QUIRE_SERVER_AI_ENABLED", str(ai_mode != "off").lower())
    if ai_mode == "token":
        monkeypatch.setenv("QUIRE_SERVER_AI_AUTH_MODE", "token")
        monkeypatch.setenv("QUIRE_SERVER_AI_TOKEN_SECRETS", '{"k1":"' + "x" * 32 + '"}')
        monkeypatch.setenv("QUIRE_SERVER_AI_TOKEN_ISSUER", "quire-cloud")
        monkeypatch.setenv("QUIRE_SERVER_AI_TOKEN_AUDIENCE", "quire-server")
    app = create_app()
    paths = set(app.openapi()["paths"])
    auth_paths = {path for path in paths if path.startswith("/auth/v1/")}
    if native:
        assert {
            "/auth/v1/login",
            "/auth/v1/logout",
            "/auth/v1/magic-link/request",
            "/auth/v1/magic-link/consume",
        } <= auth_paths
    else:
        assert not auth_paths
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app), base_url="http://fixture"
    ) as client:
        assert (await client.get("/auth/v1/me")).status_code == (401 if native else 404)
