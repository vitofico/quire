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


def _create_app():
    from quire_server.main import create_app

    return create_app()


def _mounted_paths(app) -> set[str]:
    """Registered route paths, read from the OpenAPI schema.

    Robust across FastAPI/Starlette versions: newer FastAPI no longer flattens
    ``include_router`` children into ``app.routes`` (it wraps them in an internal
    ``_IncludedRouter``), so scanning ``app.routes[].path`` misses every mounted
    router. The OpenAPI schema lists every in-schema route regardless of how the
    router tree is represented.
    """
    return set(app.openapi()["paths"])


# ----------------------------------------------------------------------------
# Default: CalibreWeb Basic
# ----------------------------------------------------------------------------


def test_default_backend_is_calibreweb():
    app = _create_app()
    from quire_server.core.auth_backend import CalibreWebBasicAuth

    assert isinstance(app.state.auth_backend, CalibreWebBasicAuth)


def test_calibreweb_backend_does_not_mount_auth_router():
    """OSS deployments should not surface ``/auth/v1/*`` at all."""
    app = _create_app()
    paths = _mounted_paths(app)
    assert not any(p.startswith("/auth/v1") for p in paths), paths


# ----------------------------------------------------------------------------
# Native backend opt-in
# ----------------------------------------------------------------------------


def test_native_backend_via_env(monkeypatch):
    monkeypatch.setenv("QUIRE_SERVER_AUTH_BACKEND", "native")
    # Native + AI-enabled-basic would trigger the guard; pair with AI off.
    monkeypatch.setenv("QUIRE_SERVER_AI_ENABLED", "false")
    app = _create_app()
    from quire_server.core.auth_backend import NativeAuth

    assert isinstance(app.state.auth_backend, NativeAuth)


def test_native_backend_mounts_auth_router(monkeypatch):
    monkeypatch.setenv("QUIRE_SERVER_AUTH_BACKEND", "native")
    monkeypatch.setenv("QUIRE_SERVER_AI_ENABLED", "false")
    app = _create_app()
    paths = _mounted_paths(app)
    assert "/auth/v1/login" in paths, paths
    assert "/auth/v1/logout" in paths, paths
    assert "/auth/v1/magic-link/request" in paths, paths
    assert "/auth/v1/magic-link/consume" in paths, paths


# ----------------------------------------------------------------------------
# Native primary auth + AI-on + basic AI auth → AI delegates to NativeAuth
# (the X-2 replacement for the deprecated token mode; formerly a crashloop).
# ----------------------------------------------------------------------------


def test_native_with_ai_basic_delegates_to_backend(monkeypatch):
    """Native primary auth + AI on + ``ai_auth_mode=basic`` now routes AI
    requests through the primary ``NativeAuth`` backend via
    ``BackendAiAuthenticator`` — the same session-token identity layer as
    ``/auth/v1`` / ``/sync/v1`` / ``/library/v1``. This is the long-term
    replacement for ``AI_AUTH_MODE=token`` and used to crashloop.
    """
    monkeypatch.setenv("QUIRE_SERVER_AUTH_BACKEND", "native")
    monkeypatch.setenv("QUIRE_SERVER_AI_ENABLED", "true")
    # ai_auth_mode defaults to "basic"; leave unset.
    app = _create_app()
    from quire_server.api.ai_auth import BackendAiAuthenticator
    from quire_server.core.auth_backend import NativeAuth

    assert isinstance(app.state.ai_authenticator, BackendAiAuthenticator)
    # The AI authenticator delegates to the SAME backend instance the primary
    # routes use — not a second, parallel auth object.
    assert isinstance(app.state.auth_backend, NativeAuth)
    assert app.state.ai_authenticator._backend is app.state.auth_backend


def test_native_with_ai_token_auth_is_ok(monkeypatch):
    """Token-mode AI auth is independent from primary auth → no guard hit."""
    monkeypatch.setenv("QUIRE_SERVER_AUTH_BACKEND", "native")
    monkeypatch.setenv("QUIRE_SERVER_AI_ENABLED", "true")
    monkeypatch.setenv("QUIRE_SERVER_AI_AUTH_MODE", "token")
    monkeypatch.setenv("QUIRE_SERVER_AI_TOKEN_SECRETS", '{"k1": "' + "x" * 32 + '"}')
    monkeypatch.setenv("QUIRE_SERVER_AI_TOKEN_ISSUER", "quire-cloud")
    monkeypatch.setenv("QUIRE_SERVER_AI_TOKEN_AUDIENCE", "quire-server")
    app = _create_app()
    from quire_server.core.auth_backend import NativeAuth

    assert isinstance(app.state.auth_backend, NativeAuth)


def test_native_with_ai_off_is_ok(monkeypatch):
    """AI disabled → guard does not fire regardless of ai_auth_mode default."""
    monkeypatch.setenv("QUIRE_SERVER_AUTH_BACKEND", "native")
    monkeypatch.setenv("QUIRE_SERVER_AI_ENABLED", "false")
    app = _create_app()
    from quire_server.core.auth_backend import NativeAuth

    assert isinstance(app.state.auth_backend, NativeAuth)


def test_calibreweb_with_ai_basic_is_ok():
    """The pre-S-1 default combination must keep working byte-for-byte."""
    # All env stripped → defaults apply.
    app = _create_app()
    from quire_server.core.auth_backend import CalibreWebBasicAuth

    assert isinstance(app.state.auth_backend, CalibreWebBasicAuth)


# ----------------------------------------------------------------------------
# Settings exposure
# ----------------------------------------------------------------------------


def test_settings_auth_backend_default():
    from quire_server.config import Settings

    s = Settings()
    assert s.auth_backend == "calibreweb"


def test_settings_auth_backend_env_override(monkeypatch):
    monkeypatch.setenv("QUIRE_SERVER_AUTH_BACKEND", "native")
    from quire_server.config import Settings

    s = Settings()
    assert s.auth_backend == "native"


def test_settings_native_session_ttl_default():
    from quire_server.config import Settings

    s = Settings()
    assert s.native_session_ttl_s == 30 * 24 * 3600
