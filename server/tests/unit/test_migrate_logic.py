"""Synthetic-graph unit tests for the migrate wrapper's pure logic.

Operates on stubs in lieu of a real Alembic ScriptDirectory + DB, so these
tests are fast and isolated. Integration tests in tests/integration verify
the wrapper against a real Postgres + the real migrations directory.

The ``read_modes`` tests pin that the wrapper reads the two mode switches
exactly as the server does, so a spelling the server takes as "on" never
skips a migration branch.
"""

from __future__ import annotations

import logging
import os
from pathlib import Path

import pytest
from pydantic import ValidationError

from quire_server.config import Settings, get_settings
from scripts.migrate import _backbone_head, _existing_branch_labels, main, read_modes

SERVER_DIR = Path(__file__).resolve().parents[2]


class _StubRevision:
    def __init__(self, revision: str, branch_labels: tuple[str, ...] | None = None):
        self.revision = revision
        # Mirror Alembic: public `branch_labels` propagates from descendants,
        # but `_orig_branch_labels` is the per-revision declared set. The
        # migrate module reads `_orig_branch_labels`, so the stub matches.
        self.branch_labels = set(branch_labels or ())
        self._orig_branch_labels = tuple(branch_labels or ())


class _StubScriptDirectory:
    """walk_revisions() returns newest-to-oldest, mirroring Alembic."""

    def __init__(self, revisions_newest_first: list[_StubRevision]):
        self._revs = revisions_newest_first

    def walk_revisions(self):
        return iter(self._revs)


def test_existing_branch_labels_empty_when_no_labels():
    sd = _StubScriptDirectory(
        [
            _StubRevision("0004"),
            _StubRevision("0003"),
            _StubRevision("0002"),
            _StubRevision("0001"),
        ]
    )
    assert _existing_branch_labels(sd) == set()


def test_existing_branch_labels_collects_all_labels():
    sd = _StubScriptDirectory(
        [
            _StubRevision("ai_001", branch_labels=("ai",)),
            _StubRevision("progress_001", branch_labels=("progress",)),
            _StubRevision("0004"),
            _StubRevision("0003"),
        ]
    )
    assert _existing_branch_labels(sd) == {"ai", "progress"}


def test_backbone_head_returns_only_head_when_no_labels():
    sd = _StubScriptDirectory(
        [
            _StubRevision("0004"),
            _StubRevision("0003"),
            _StubRevision("0002"),
            _StubRevision("0001"),
        ]
    )
    assert _backbone_head(sd) == "0004"


def test_backbone_head_returns_unlabeled_tip_when_branches_exist():
    """Critical case: ai_001 exists as a child of 0004; backbone tip is still 0004."""
    sd = _StubScriptDirectory(
        [
            _StubRevision("ai_001", branch_labels=("ai",)),
            _StubRevision("0004"),  # unlabeled — backbone tip
            _StubRevision("0003"),
            _StubRevision("0002"),
            _StubRevision("0001"),
        ]
    )
    assert _backbone_head(sd) == "0004"


def test_backbone_head_stops_at_first_labeled_revision():
    """Multiple branches off 0004 → backbone tip is still 0004."""
    sd = _StubScriptDirectory(
        [
            _StubRevision("ai_002", branch_labels=()),
            _StubRevision("ai_001", branch_labels=("ai",)),
            _StubRevision("progress_001", branch_labels=("progress",)),
            _StubRevision("0004"),
            _StubRevision("0003"),
            _StubRevision("0002"),
            _StubRevision("0001"),
        ]
    )
    assert _backbone_head(sd) == "0004"


def test_backbone_head_raises_when_first_revision_is_labeled():
    """Defensive: if no unlabeled revisions exist, raise rather than silently mislabel."""
    sd = _StubScriptDirectory(
        [
            _StubRevision("labeled_001", branch_labels=("core",)),
        ]
    )
    with pytest.raises(RuntimeError, match="no unlabeled backbone"):
        _backbone_head(sd)


# ---------------------------------------------------------------------------
# read_modes: the two mode switches, read exactly as the server reads them
# ---------------------------------------------------------------------------


@pytest.fixture
def clean_env(monkeypatch):
    """No QUIRE_SERVER_* variable in any case, and no cached settings.

    The CI mode matrix exports both switches, so without this the "unset"
    case would read the job's values instead of the defaults.
    """
    for name in list(os.environ):
        if name.upper().startswith("QUIRE_SERVER_"):
            monkeypatch.delenv(name)
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


def _set_both(monkeypatch, value: str) -> None:
    monkeypatch.setenv("QUIRE_SERVER_PROGRESS_ENABLED", value)
    monkeypatch.setenv("QUIRE_SERVER_AI_ENABLED", value)


@pytest.mark.parametrize("value", ["y", "Y", "t", "on", "1", "true"])
def test_read_modes_on_spellings(clean_env, monkeypatch, value):
    _set_both(monkeypatch, value)
    assert read_modes() == (True, True)


@pytest.mark.parametrize("value", ["n", "off", "0", "false"])
def test_read_modes_off_spellings(clean_env, monkeypatch, value):
    _set_both(monkeypatch, value)
    assert read_modes() == (False, False)


def test_read_modes_defaults_to_on_when_unset(clean_env):
    assert read_modes() == (True, True)


def test_read_modes_honours_a_lowercase_name(clean_env, monkeypatch):
    monkeypatch.setenv("quire_server_ai_enabled", "false")
    assert read_modes() == (True, False)


def test_read_modes_reads_the_dotenv_file(clean_env, monkeypatch, tmp_path):
    env_file = tmp_path / ".env"
    env_file.write_text("QUIRE_SERVER_PROGRESS_ENABLED=off\n", encoding="utf-8")
    monkeypatch.setitem(Settings.model_config, "env_file", str(env_file))
    assert read_modes() == (False, True)


@pytest.mark.parametrize(
    ("name", "field"),
    [
        ("QUIRE_SERVER_PROGRESS_ENABLED", "progress_enabled"),
        ("QUIRE_SERVER_AI_ENABLED", "ai_enabled"),
    ],
)
def test_read_modes_rejects_a_value_the_server_refuses(clean_env, monkeypatch, name, field):
    monkeypatch.setenv(name, "nope")
    with pytest.raises(ValidationError, match=field):
        read_modes()


def test_main_stops_before_migrating_on_an_invalid_value(clean_env, monkeypatch, caplog):
    monkeypatch.setenv("ALEMBIC_INI", str(SERVER_DIR / "alembic.ini"))
    monkeypatch.setenv("QUIRE_SERVER_AI_ENABLED", "nope")
    with caplog.at_level(logging.ERROR, logger="migrate"):
        assert main() == 2
    assert "invalid server settings" in caplog.text
    assert "ai_enabled" in caplog.text
