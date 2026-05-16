"""Synthetic-graph unit tests for the migrate wrapper's pure logic.

Operates on stubs in lieu of a real Alembic ScriptDirectory + DB, so these
tests are fast and isolated. Integration tests in tests/integration verify
the wrapper against a real Postgres + the real migrations directory.
"""

from __future__ import annotations

import pytest

from scripts.migrate import _backbone_head, _existing_branch_labels


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


def test_truthy_parsing():
    from scripts.migrate import _is_truthy

    assert _is_truthy(None, default=True) is True
    assert _is_truthy(None, default=False) is False
    assert _is_truthy("true") is True
    assert _is_truthy("True") is True
    assert _is_truthy("1") is True
    assert _is_truthy("yes") is True
    assert _is_truthy("on") is True
    assert _is_truthy("false") is False
    assert _is_truthy("0") is False
    assert _is_truthy("") is False
    assert _is_truthy("nope") is False
