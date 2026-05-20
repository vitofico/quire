"""Tests for `quire_server._env_compat.resolve_env_prefix_value`.

Back-compat helper for the OPDS_SYNC_ -> QUIRE_SERVER_ prefix rename.
Written TDD-first against the helper module before consumer code (Settings,
migrate.py) is wired.
"""

from __future__ import annotations

import logging

import pytest

from quire_server._env_compat import (
    _BOTH_LOGGED,
    _LEGACY_LOGGED,
    reset_log_state_for_testing,
    resolve_env_prefix_value,
)


@pytest.fixture(autouse=True)
def _clean_env(monkeypatch: pytest.MonkeyPatch):
    """Per-test isolation: clear residual prefixed env + one-shot WARNING state."""
    for var in (
        "QUIRE_SERVER_FOO",
        "OPDS_SYNC_FOO",
        "QUIRE_SERVER_BAR",
        "OPDS_SYNC_BAR",
        "FOO_NEW",
        "FOO_OLD",
    ):
        monkeypatch.delenv(var, raising=False)
    reset_log_state_for_testing()
    yield
    reset_log_state_for_testing()


def test_new_only_wins(monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture):
    monkeypatch.setenv("QUIRE_SERVER_FOO", "new")
    with caplog.at_level(logging.WARNING, logger="quire_server._env_compat"):
        val = resolve_env_prefix_value("QUIRE_SERVER_FOO")
    assert val == "new"
    assert caplog.records == []


def test_legacy_only_returns_with_warning(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
):
    monkeypatch.setenv("OPDS_SYNC_FOO", "legacy")
    with caplog.at_level(logging.WARNING, logger="quire_server._env_compat"):
        val1 = resolve_env_prefix_value("QUIRE_SERVER_FOO")
        val2 = resolve_env_prefix_value("QUIRE_SERVER_FOO")
    assert val1 == "legacy"
    assert val2 == "legacy"
    # Exactly one WARNING for the legacy key across both calls (per-process one-shot).
    legacy_warnings = [r for r in caplog.records if "env.prefix.legacy_used" in r.getMessage()]
    assert len(legacy_warnings) == 1
    assert "OPDS_SYNC_FOO" in legacy_warnings[0].getMessage()
    assert "QUIRE_SERVER_FOO" in legacy_warnings[0].getMessage()


def test_both_set_new_wins_with_warning(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
):
    monkeypatch.setenv("QUIRE_SERVER_FOO", "new")
    monkeypatch.setenv("OPDS_SYNC_FOO", "legacy")
    with caplog.at_level(logging.WARNING, logger="quire_server._env_compat"):
        val = resolve_env_prefix_value("QUIRE_SERVER_FOO")
    assert val == "new"
    both_warnings = [r for r in caplog.records if "env.prefix.both_set" in r.getMessage()]
    assert len(both_warnings) == 1


def test_neither_set_returns_default(monkeypatch: pytest.MonkeyPatch):
    val = resolve_env_prefix_value("QUIRE_SERVER_FOO", default="d")
    assert val == "d"


def test_neither_set_returns_none_when_no_default():
    val = resolve_env_prefix_value("QUIRE_SERVER_FOO")
    assert val is None


def test_explicit_legacy_key_argument(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setenv("FOO_OLD", "from-legacy")
    val = resolve_env_prefix_value("FOO_NEW", legacy_key="FOO_OLD")
    assert val == "from-legacy"


def test_new_key_must_start_with_new_prefix_when_no_legacy_given():
    with pytest.raises(ValueError, match="QUIRE_SERVER_"):
        resolve_env_prefix_value("FOO_NEW")


def test_log_dedup_per_key(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
):
    monkeypatch.setenv("OPDS_SYNC_FOO", "a")
    monkeypatch.setenv("OPDS_SYNC_BAR", "b")
    with caplog.at_level(logging.WARNING, logger="quire_server._env_compat"):
        resolve_env_prefix_value("QUIRE_SERVER_FOO")
        resolve_env_prefix_value("QUIRE_SERVER_BAR")
        resolve_env_prefix_value("QUIRE_SERVER_FOO")  # dedup'd
        resolve_env_prefix_value("QUIRE_SERVER_BAR")  # dedup'd
    legacy_warnings = [r for r in caplog.records if "env.prefix.legacy_used" in r.getMessage()]
    assert len(legacy_warnings) == 2


def test_reset_log_state_clears_dedup(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
):
    monkeypatch.setenv("OPDS_SYNC_FOO", "legacy")
    with caplog.at_level(logging.WARNING, logger="quire_server._env_compat"):
        resolve_env_prefix_value("QUIRE_SERVER_FOO")
        assert len(_LEGACY_LOGGED) == 1
        reset_log_state_for_testing()
        assert len(_LEGACY_LOGGED) == 0
        assert len(_BOTH_LOGGED) == 0
        resolve_env_prefix_value("QUIRE_SERVER_FOO")
    legacy_warnings = [r for r in caplog.records if "env.prefix.legacy_used" in r.getMessage()]
    assert len(legacy_warnings) == 2
