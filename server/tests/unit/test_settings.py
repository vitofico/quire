"""Tests for opds_sync.config.Settings defaults.

PR-A flips ai_enabled default to True and adds progress_enabled +
max_request_bytes. Primary cases use the new QUIRE_SERVER_ prefix;
one back-compat test exercises the legacy OPDS_SYNC_ prefix (the
dual-prefix logic is covered in detail by tests/unit/test_settings_env_compat.py).
"""

from __future__ import annotations

import pytest

from opds_sync._env_compat import reset_log_state_for_testing
from opds_sync.config import Settings, get_settings


@pytest.fixture(autouse=True)
def _clear_settings_cache(monkeypatch):
    """Ensure each test sees a fresh settings instance (no env-bleed)."""
    for var in (
        "QUIRE_SERVER_AI_ENABLED",
        "OPDS_SYNC_AI_ENABLED",
        "QUIRE_SERVER_PROGRESS_ENABLED",
        "OPDS_SYNC_PROGRESS_ENABLED",
        "QUIRE_SERVER_MAX_REQUEST_BYTES",
        "OPDS_SYNC_MAX_REQUEST_BYTES",
        "QUIRE_SERVER_AI_AUTH_MODE",
        "OPDS_SYNC_AI_AUTH_MODE",
        "QUIRE_SERVER_AI_TOKEN_SECRETS",
        "OPDS_SYNC_AI_TOKEN_SECRETS",
        "QUIRE_SERVER_AI_TOKEN_ISSUER",
        "OPDS_SYNC_AI_TOKEN_ISSUER",
        "QUIRE_SERVER_AI_TOKEN_AUDIENCE",
        "OPDS_SYNC_AI_TOKEN_AUDIENCE",
    ):
        monkeypatch.delenv(var, raising=False)
    reset_log_state_for_testing()
    get_settings.cache_clear()
    yield
    reset_log_state_for_testing()
    get_settings.cache_clear()


def test_defaults_match_pr_a_contract(monkeypatch):
    s = Settings()
    assert s.ai_enabled is True
    assert s.progress_enabled is True
    assert s.max_request_bytes == 1_048_576


def test_progress_enabled_env_override(monkeypatch):
    monkeypatch.setenv("QUIRE_SERVER_PROGRESS_ENABLED", "false")
    s = Settings()
    assert s.progress_enabled is False


def test_ai_enabled_env_override(monkeypatch):
    monkeypatch.setenv("QUIRE_SERVER_AI_ENABLED", "false")
    s = Settings()
    assert s.ai_enabled is False


def test_legacy_prefix_back_compat(monkeypatch):
    """One back-compat test under the legacy prefix; full matrix in test_settings_env_compat."""
    monkeypatch.setenv("OPDS_SYNC_AI_ENABLED", "false")
    s = Settings()
    assert s.ai_enabled is False


def test_max_request_bytes_env_override(monkeypatch):
    monkeypatch.setenv("QUIRE_SERVER_MAX_REQUEST_BYTES", "2048")
    s = Settings()
    assert s.max_request_bytes == 2048


# --- PR-B: AI auth abstraction --------------------------------------------


def test_ai_auth_mode_defaults_to_basic(monkeypatch):
    s = Settings()
    assert s.ai_auth_mode == "basic"
    assert s.ai_token_secrets is None
    assert s.ai_token_issuer is None
    assert s.ai_token_audience is None


def test_ai_auth_mode_env_override(monkeypatch):
    monkeypatch.setenv("QUIRE_SERVER_AI_AUTH_MODE", "token")
    s = Settings()
    assert s.ai_auth_mode == "token"


def test_ai_token_secrets_parses_json_object(monkeypatch):
    monkeypatch.setenv("QUIRE_SERVER_AI_TOKEN_SECRETS", '{"k1": "a" , "k2": "b"}')
    s = Settings()
    assert s.ai_token_secrets == {"k1": "a", "k2": "b"}


def test_ai_token_issuer_audience_env_override(monkeypatch):
    monkeypatch.setenv("QUIRE_SERVER_AI_TOKEN_ISSUER", "quire-cloud")
    monkeypatch.setenv("QUIRE_SERVER_AI_TOKEN_AUDIENCE", "quire-server")
    s = Settings()
    assert s.ai_token_issuer == "quire-cloud"
    assert s.ai_token_audience == "quire-server"
