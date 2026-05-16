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
