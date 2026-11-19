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


@pytest.mark.parametrize(
    "overrides,message",
    [
        pytest.param({"secrets": None}, "AI_TOKEN_SECRETS", id="missing-secrets"),
        pytest.param({"secrets": "{}"}, "AI_TOKEN_SECRETS", id="empty-secret-map"),
        pytest.param({"secrets": '{"k1": "short"}'}, "32 bytes", id="short-secret"),
        pytest.param({"secrets": '{"": "' + "x" * 32 + '"}'}, "kid", id="empty-kid"),
        pytest.param({"iss": None}, "AI_TOKEN_ISSUER", id="missing-issuer"),
        pytest.param({"iss": "   "}, "AI_TOKEN_ISSUER", id="blank-issuer"),
        pytest.param({"aud": None}, "AI_TOKEN_AUDIENCE", id="missing-audience"),
        pytest.param({"aud": " \t"}, "AI_TOKEN_AUDIENCE", id="blank-audience"),
    ],
)
def test_token_mode_rejects_invalid_configuration(monkeypatch, overrides, message):
    _token_env(monkeypatch, **overrides)
    with pytest.raises(RuntimeError, match=message):
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


@pytest.mark.parametrize(
    "mode",
    [
        pytest.param("token", id="token-enabled"),
        pytest.param("basic", id="default-basic"),
        pytest.param("disabled", id="ai-disabled-with-token"),
    ],
)
def test_token_mode_startup_deprecation_notice(monkeypatch, caplog, mode):
    if mode == "token":
        _token_env(monkeypatch)
    elif mode == "disabled":
        monkeypatch.setenv("QUIRE_SERVER_AI_ENABLED", "false")
        monkeypatch.setenv("QUIRE_SERVER_AI_AUTH_MODE", "token")

    notice = "AI_AUTH_MODE=token is deprecated"
    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always")
        with caplog.at_level("WARNING", logger="quire_server.main"):
            _create_app()

    messages = [
        str(warning.message)
        for warning in caught
        if issubclass(warning.category, DeprecationWarning) and notice in str(warning.message)
    ]
    logs = [
        record
        for record in caplog.records
        if record.levelname == "WARNING" and notice in record.getMessage()
    ]
    if mode == "token":
        assert messages, f"expected DeprecationWarning naming {notice}"
        for part in (
            "event=config.deprecated",
            "setting=QUIRE_SERVER_AI_AUTH_MODE",
            "value=token",
            "QUIRE_SERVER_AUTH_BACKEND=native",
            "2 minor releases",
        ):
            assert part in messages[0]
        assert logs
    else:
        assert not messages
        assert not logs
