"""Settings integration tests for env-prefix back-compat (PR-rename, §4.2).

Settings is configured with `env_prefix="QUIRE_SERVER_"` plus a custom
LegacyEnvSettingsSource that also resolves OPDS_SYNC_* names. Complex
JSON-parsed fields (e.g. `ai_token_secrets`) must round-trip under both
prefixes.
"""

from __future__ import annotations

import logging

import pytest

from quire_server._env_compat import reset_log_state_for_testing
from quire_server.config import Settings, get_settings


@pytest.fixture(autouse=True)
def _isolate(monkeypatch: pytest.MonkeyPatch):
    """Per-test isolation: clear settings cache + helper one-shot state."""
    for var in (
        "QUIRE_SERVER_AI_ENABLED",
        "OPDS_SYNC_AI_ENABLED",
        "QUIRE_SERVER_PROGRESS_ENABLED",
        "OPDS_SYNC_PROGRESS_ENABLED",
        "QUIRE_SERVER_AI_TOKEN_SECRETS",
        "OPDS_SYNC_AI_TOKEN_SECRETS",
        "QUIRE_SERVER_MAX_REQUEST_BYTES",
        "OPDS_SYNC_MAX_REQUEST_BYTES",
    ):
        monkeypatch.delenv(var, raising=False)
    reset_log_state_for_testing()
    get_settings.cache_clear()
    yield
    reset_log_state_for_testing()
    get_settings.cache_clear()


def test_settings_reads_new_prefix_only(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setenv("QUIRE_SERVER_AI_ENABLED", "false")
    s = Settings()
    assert s.ai_enabled is False


def test_settings_reads_legacy_prefix_only_with_warning(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
):
    monkeypatch.setenv("OPDS_SYNC_AI_ENABLED", "false")
    with caplog.at_level(logging.WARNING, logger="quire_server._env_compat"):
        s = Settings()
    assert s.ai_enabled is False
    legacy_warnings = [r for r in caplog.records if "env.prefix.legacy_used" in r.getMessage()]
    assert legacy_warnings, "expected legacy-prefix WARNING"


def test_settings_both_prefix_new_wins(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setenv("QUIRE_SERVER_AI_ENABLED", "true")
    monkeypatch.setenv("OPDS_SYNC_AI_ENABLED", "false")
    s = Settings()
    assert s.ai_enabled is True


def test_complex_field_under_new_prefix(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setenv("QUIRE_SERVER_AI_TOKEN_SECRETS", '{"a":"abcdefghijklmnopqrstuvwxyz012345"}')
    s = Settings()
    assert s.ai_token_secrets == {"a": "abcdefghijklmnopqrstuvwxyz012345"}


def test_complex_field_under_legacy_prefix(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setenv("OPDS_SYNC_AI_TOKEN_SECRETS", '{"a":"abcdefghijklmnopqrstuvwxyz012345"}')
    s = Settings()
    assert s.ai_token_secrets == {"a": "abcdefghijklmnopqrstuvwxyz012345"}


def test_complex_field_both_set_new_wins(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setenv(
        "QUIRE_SERVER_AI_TOKEN_SECRETS",
        '{"new":"abcdefghijklmnopqrstuvwxyz012345"}',
    )
    monkeypatch.setenv(
        "OPDS_SYNC_AI_TOKEN_SECRETS",
        '{"legacy":"abcdefghijklmnopqrstuvwxyz012345"}',
    )
    s = Settings()
    assert s.ai_token_secrets == {"new": "abcdefghijklmnopqrstuvwxyz012345"}
