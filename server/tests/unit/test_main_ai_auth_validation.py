"""Startup validation of AI auth settings (PR-B).

Verifies that token-mode misconfigurations crash `create_app()` loudly,
that basic mode is unaffected, and that AI-disabled deploys never check
auth settings at all.

The tests build the app via `quire_server.main.create_app()` so they exercise
the actual production wiring, including `_validate_ai_auth_settings` and
`_build_ai_authenticator`.
"""

from __future__ import annotations

import warnings

import pytest

from quire_server.config import get_settings


@pytest.fixture(autouse=True)
def _isolate_env(monkeypatch):
    # Strip every AI-related env var so each test starts from the documented
    # defaults. Tests then opt back in via monkeypatch.setenv.
    for var in (
        "QUIRE_SERVER_AI_ENABLED",
        "QUIRE_SERVER_AI_AUTH_MODE",
        "QUIRE_SERVER_AI_TOKEN_SECRETS",
        "QUIRE_SERVER_AI_TOKEN_ISSUER",
        "QUIRE_SERVER_AI_TOKEN_AUDIENCE",
        "QUIRE_SERVER_AI_BASE_URL",
        "QUIRE_SERVER_AI_MODEL",
        "QUIRE_SERVER_AI_API_KEY",
        # Phase 0, task S-1: keep these tests focused on AI auth wiring;
        # the primary auth backend must stay at its default ``calibreweb``.
        "QUIRE_SERVER_AUTH_BACKEND",
    ):
        monkeypatch.delenv(var, raising=False)
    # Avoid the real DB / CWA wiring touching the network in create_app.
    monkeypatch.setenv("QUIRE_SERVER_DATABASE_URL", "postgresql+asyncpg://x/y")
    monkeypatch.setenv("QUIRE_SERVER_CWA_BASE_URL", "http://stub")
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


def _create_app():
    # Re-import to ensure the function captures the freshly-cleared settings.
    from quire_server.main import create_app

    return create_app()


# ---------------------------------------------------------------------------
# Basic mode: untouched
# ---------------------------------------------------------------------------


def test_basic_mode_no_token_settings_required():
    # Default: ai_enabled=true, ai_auth_mode=basic, nothing token-related set.
    app = _create_app()
    assert getattr(app.state, "ai_authenticator", None) is not None
    # Type sanity: basic implementation wraps the calibre validator.
    from quire_server.api.ai_auth import BasicAuthAiAuthenticator

    assert isinstance(app.state.ai_authenticator, BasicAuthAiAuthenticator)


def test_basic_mode_ignores_stray_token_settings(monkeypatch):
    # Operator left token settings around but kept mode=basic — must not raise.
    monkeypatch.setenv("QUIRE_SERVER_AI_TOKEN_SECRETS", '{"k1": "short"}')
    app = _create_app()
    from quire_server.api.ai_auth import BasicAuthAiAuthenticator

    assert isinstance(app.state.ai_authenticator, BasicAuthAiAuthenticator)


# ---------------------------------------------------------------------------
# AI disabled: skip auth wiring entirely
# ---------------------------------------------------------------------------


def test_ai_disabled_skips_token_validation(monkeypatch):
    # Token mode + missing secrets would normally crash, but ai_enabled=false
    # bypasses the entire AI block.
    monkeypatch.setenv("QUIRE_SERVER_AI_ENABLED", "false")
    monkeypatch.setenv("QUIRE_SERVER_AI_AUTH_MODE", "token")
    app = _create_app()
    assert getattr(app.state, "ai_authenticator", None) is None


# ---------------------------------------------------------------------------
# Token mode: every misconfiguration raises
# ---------------------------------------------------------------------------


def _token_env(monkeypatch, *, secrets='{"k1":"' + "x" * 32 + '"}', iss="quire", aud="opds"):
    monkeypatch.setenv("QUIRE_SERVER_AI_AUTH_MODE", "token")
    if secrets is not None:
        monkeypatch.setenv("QUIRE_SERVER_AI_TOKEN_SECRETS", secrets)
    if iss is not None:
        monkeypatch.setenv("QUIRE_SERVER_AI_TOKEN_ISSUER", iss)
    if aud is not None:
        monkeypatch.setenv("QUIRE_SERVER_AI_TOKEN_AUDIENCE", aud)


def test_token_mode_secrets_missing_raises(monkeypatch):
    _token_env(monkeypatch, secrets=None)
    with pytest.raises(RuntimeError, match="AI_TOKEN_SECRETS"):
        _create_app()


def test_token_mode_secrets_empty_raises(monkeypatch):
    _token_env(monkeypatch, secrets="{}")
    with pytest.raises(RuntimeError, match="AI_TOKEN_SECRETS"):
        _create_app()


def test_token_mode_secret_too_short_raises(monkeypatch):
    _token_env(monkeypatch, secrets='{"k1": "short"}')
    with pytest.raises(RuntimeError, match="32 bytes"):
        _create_app()


def test_token_mode_empty_kid_raises(monkeypatch):
    _token_env(monkeypatch, secrets='{"": "' + "x" * 32 + '"}')
    with pytest.raises(RuntimeError, match="kid"):
        _create_app()


def test_token_mode_issuer_missing_raises(monkeypatch):
    _token_env(monkeypatch, iss=None)
    with pytest.raises(RuntimeError, match="AI_TOKEN_ISSUER"):
        _create_app()


def test_token_mode_issuer_whitespace_raises(monkeypatch):
    _token_env(monkeypatch, iss="   ")
    with pytest.raises(RuntimeError, match="AI_TOKEN_ISSUER"):
        _create_app()


def test_token_mode_audience_missing_raises(monkeypatch):
    _token_env(monkeypatch, aud=None)
    with pytest.raises(RuntimeError, match="AI_TOKEN_AUDIENCE"):
        _create_app()


def test_token_mode_audience_whitespace_raises(monkeypatch):
    _token_env(monkeypatch, aud=" \t")
    with pytest.raises(RuntimeError, match="AI_TOKEN_AUDIENCE"):
        _create_app()


def test_token_mode_unconfigured_provider_still_wires_token_auth(monkeypatch):
    """AI provider missing but token mode fully configured → /ai/v1/config
    mounts AND requires a Bearer token. The authenticator must be the token
    impl, not silently downgraded to basic.
    """
    _token_env(monkeypatch)
    # ai_base_url and ai_model intentionally not set.
    app = _create_app()
    from quire_server.api.ai_auth import TokenAiAuthenticator

    assert isinstance(app.state.ai_authenticator, TokenAiAuthenticator)


def test_token_mode_fully_configured(monkeypatch):
    _token_env(monkeypatch)
    monkeypatch.setenv("QUIRE_SERVER_AI_BASE_URL", "http://ollama.lan:11434/v1")
    monkeypatch.setenv("QUIRE_SERVER_AI_MODEL", "llama3:8b")
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", DeprecationWarning)
        app = _create_app()
    from quire_server.api.ai_auth import TokenAiAuthenticator

    assert isinstance(app.state.ai_authenticator, TokenAiAuthenticator)


# ---------------------------------------------------------------------------
# Phase 0, task X-2: deprecation warning for AI_AUTH_MODE=token
# ---------------------------------------------------------------------------


def _assert_no_ai_auth_mode_deprecation(records: list[warnings.WarningMessage]) -> None:
    """Assert no deprecation warning about ``AI_AUTH_MODE=token`` was emitted.

    Tolerates unrelated ``DeprecationWarning``s from third-party libraries
    (pydantic, sqlalchemy, starlette, etc.) — only matches on the specific
    Quire deprecation message body.
    """
    matches = [
        r
        for r in records
        if issubclass(r.category, DeprecationWarning)
        and "AI_AUTH_MODE=token is deprecated" in str(r.message)
    ]
    assert matches == [], f"unexpected AI_AUTH_MODE deprecation warning: {matches}"


def test_token_mode_emits_deprecation_warning(monkeypatch, caplog):
    """Fully-valid token mode must surface the deprecation as both a
    ``DeprecationWarning`` (for developers / pytest) and a ``WARNING`` log
    record (operator-facing signal) at startup.
    """
    _token_env(monkeypatch)
    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always")
        with caplog.at_level("WARNING", logger="quire_server.main"):
            _create_app()

    deprecation_messages = [
        str(w.message)
        for w in caught
        if issubclass(w.category, DeprecationWarning)
        and "AI_AUTH_MODE=token is deprecated" in str(w.message)
    ]
    assert deprecation_messages, "expected DeprecationWarning naming AI_AUTH_MODE=token"
    # Operator-facing log record carries the structured-event suffix so log
    # scrapers can match on a stable key. The literal message body is the
    # same as the DeprecationWarning's, by design.
    body = deprecation_messages[0]
    assert "event=config.deprecated" in body
    assert "setting=QUIRE_SERVER_AI_AUTH_MODE" in body
    assert "value=token" in body
    assert "QUIRE_SERVER_AUTH_BACKEND=native" in body
    assert "2 minor releases" in body

    log_matches = [
        rec
        for rec in caplog.records
        if rec.levelname == "WARNING" and "AI_AUTH_MODE=token is deprecated" in rec.getMessage()
    ]
    assert log_matches, "expected WARNING log record naming AI_AUTH_MODE=token"


def test_basic_mode_does_not_emit_deprecation_warning(monkeypatch):
    """Default basic mode must not fire the X-2 deprecation."""
    # _isolate_env already strips AI_AUTH_MODE; defaults to basic.
    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always")
        _create_app()
    _assert_no_ai_auth_mode_deprecation(caught)


def test_ai_disabled_token_mode_does_not_emit_deprecation_warning(monkeypatch):
    """``ai_enabled=false`` short-circuits before the deprecation check.

    Operators with a stale ``AI_AUTH_MODE=token`` in a sync-only ``.env``
    should not be nagged: the setting is inert when the AI block doesn't
    run. The DeprecationWarning is reserved for deploys where token mode
    is actually being used to authenticate AI requests.
    """
    monkeypatch.setenv("QUIRE_SERVER_AI_ENABLED", "false")
    monkeypatch.setenv("QUIRE_SERVER_AI_AUTH_MODE", "token")
    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always")
        _create_app()
    _assert_no_ai_auth_mode_deprecation(caught)
