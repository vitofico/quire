"""Tests for opds_sync.config.Settings defaults.

PR-A flips ai_enabled default to True and adds progress_enabled + max_request_bytes.
"""

from __future__ import annotations

import pytest

from opds_sync.config import Settings, get_settings


@pytest.fixture(autouse=True)
def _clear_settings_cache():
    """Ensure each test sees a fresh settings instance (no env-bleed)."""
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


def test_defaults_match_pr_a_contract(monkeypatch):
    # Strip any inherited env so we observe code defaults only.
    for var in (
        "OPDS_SYNC_AI_ENABLED",
        "OPDS_SYNC_PROGRESS_ENABLED",
        "OPDS_SYNC_MAX_REQUEST_BYTES",
    ):
        monkeypatch.delenv(var, raising=False)

    s = Settings()
    assert s.ai_enabled is True
    assert s.progress_enabled is True
    assert s.max_request_bytes == 1_048_576


def test_progress_enabled_env_override(monkeypatch):
    monkeypatch.setenv("OPDS_SYNC_PROGRESS_ENABLED", "false")
    s = Settings()
    assert s.progress_enabled is False


def test_ai_enabled_env_override(monkeypatch):
    monkeypatch.setenv("OPDS_SYNC_AI_ENABLED", "false")
    s = Settings()
    assert s.ai_enabled is False


def test_max_request_bytes_env_override(monkeypatch):
    monkeypatch.setenv("OPDS_SYNC_MAX_REQUEST_BYTES", "2048")
    s = Settings()
    assert s.max_request_bytes == 2048


# --- PR-B: AI auth abstraction --------------------------------------------


def test_ai_auth_mode_defaults_to_basic(monkeypatch):
    for var in (
        "OPDS_SYNC_AI_AUTH_MODE",
        "OPDS_SYNC_AI_TOKEN_SECRETS",
        "OPDS_SYNC_AI_TOKEN_ISSUER",
        "OPDS_SYNC_AI_TOKEN_AUDIENCE",
    ):
        monkeypatch.delenv(var, raising=False)
    s = Settings()
    assert s.ai_auth_mode == "basic"
    assert s.ai_token_secrets is None
    assert s.ai_token_issuer is None
    assert s.ai_token_audience is None


def test_ai_auth_mode_env_override(monkeypatch):
    monkeypatch.setenv("OPDS_SYNC_AI_AUTH_MODE", "token")
    s = Settings()
    assert s.ai_auth_mode == "token"


def test_ai_token_secrets_parses_json_object(monkeypatch):
    monkeypatch.setenv(
        "OPDS_SYNC_AI_TOKEN_SECRETS", '{"k1": "a" , "k2": "b"}'
    )
    s = Settings()
    assert s.ai_token_secrets == {"k1": "a", "k2": "b"}


def test_ai_token_issuer_audience_env_override(monkeypatch):
    monkeypatch.setenv("OPDS_SYNC_AI_TOKEN_ISSUER", "quire-cloud")
    monkeypatch.setenv("OPDS_SYNC_AI_TOKEN_AUDIENCE", "opds-sync")
    s = Settings()
    assert s.ai_token_issuer == "quire-cloud"
    assert s.ai_token_audience == "opds-sync"
