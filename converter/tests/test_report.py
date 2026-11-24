from types import SimpleNamespace

import pytest

from quire_convert.models import Issue, MetadataField, Outcome, Repair, Severity
from quire_convert.report import (
    REDACTED,
    Report,
    affected_pages,
    count_issues,
    decide_outcome,
    is_safe_evidence,
    peak_memory,
    redact,
)


def _check(ok=True, fatal=(), errors=()):
    return SimpleNamespace(ok=ok, fatal=list(fatal), errors=list(errors), warnings=[])


def _issue(severity=Severity.MINOR, material=False, page=None, evidence=""):
    return Issue("x.y", severity, "summary", page=page, material=material, evidence=evidence)


def test_unsupported_wins():
    outcome, reason = decide_outcome([], _check(), [], ["scanned book", "right-to-left"])
    assert outcome is Outcome.UNSUPPORTED
    assert reason == "scanned book; right-to-left"


def test_ready_needs_clean_checks_and_no_blocking_issue():
    issues = [_issue(Severity.MINOR), _issue(Severity.INFO)]
    assert decide_outcome(issues, _check(), [], []) == (Outcome.READY, "all checks passed")


@pytest.mark.parametrize(
    ("issues", "check", "semantic"),
    [
        ([_issue(Severity.MAJOR)], _check(), []),
        ([_issue(Severity.CRITICAL)], _check(), []),
        ([_issue(Severity.MINOR, material=True)], _check(), []),
        ([], _check(), [_issue()]),
        ([], _check(ok=False), []),
    ],
)
def test_review_when_structurally_valid(issues, check, semantic):
    outcome, reason = decide_outcome(issues, check, semantic, [])
    assert outcome is Outcome.REVIEW
    assert reason


@pytest.mark.parametrize("check", [_check(ok=False, errors=[{}]), _check(fatal=[{}, {}])])
def test_failed_when_epubcheck_reports_errors(check):
    outcome, reason = decide_outcome([], check, [], [])
    assert outcome is Outcome.FAILED
    assert "epubcheck" in reason


def test_epubcheck_counts_may_be_ints():
    check = SimpleNamespace(ok=False, fatal=0, errors=3, warnings=0)
    assert decide_outcome([], check, [], [])[0] is Outcome.FAILED


def test_counts_and_affected_pages():
    issues = [
        _issue(Severity.MAJOR, page=4),
        _issue(Severity.MINOR, page=7),
        _issue(Severity.MINOR, material=True, page=2),
    ]
    source = SimpleNamespace(pages=[SimpleNamespace(index=i, label=f"p{i}") for i in range(9)])
    assert count_issues(issues) == {"critical": 0, "major": 1, "minor": 2, "info": 0}
    assert affected_pages(issues, source) == [
        {"page": 2, "label": "p2"},
        {"page": 4, "label": "p4"},
    ]


@pytest.mark.parametrize(
    "text",
    ["", "s3-b4", "s3-b4 s3-b5", "qc-12, qc-13", "ratio=0.42 pages=4,5", "OEBPS/a.html#x", "12%"],
)
def test_ids_and_measurements_are_safe(text):
    assert is_safe_evidence(text)


@pytest.mark.parametrize(
    "text", ["The river", "page 3", "ratio=low", "Figure 1. The old bridge", "s3-b4 Mara"]
)
def test_words_count_as_book_text(text):
    assert not is_safe_evidence(text)


def _report(diagnostics):
    return Report(
        input_sha256="aa",
        outcome=Outcome.READY,
        reason="",
        settings={},
        issues=[_issue(evidence="the white post"), _issue(evidence="s1-b2 ratio=0.3")],
        repairs=[
            Repair("characters", "fix", "qc-1", ["s1-b2", "the river"], "rivr", "river"),
        ],
        metadata=[MetadataField("title", "The River", "title_page", evidence="Mara's river")],
        diagnostics=diagnostics,
    )


def test_redact_drops_text_but_keeps_ids_and_metadata_values():
    report = redact(_report(diagnostics=False))
    assert [i.evidence for i in report.issues] == [REDACTED, "s1-b2 ratio=0.3"]
    repair = report.repairs[0]
    assert repair.evidence == ["s1-b2", REDACTED]
    assert (repair.before, repair.after) == (None, None)
    assert report.metadata[0].value == "The River"
    assert report.metadata[0].evidence == REDACTED


def test_redact_keeps_everything_for_diagnostics_and_never_mutates():
    original = _report(diagnostics=True)
    assert redact(original) is original
    plain = _report(diagnostics=False)
    redact(plain)
    assert plain.repairs[0].before == "rivr"


def test_report_serializes():
    data = _report(diagnostics=False).to_dict()
    assert data["outcome"] == "ready"
    assert data["model"] == {"mode": "off", "revision": None, "prompt_version": None}
    assert data["issues"][0]["severity"] == "minor"


def test_peak_memory_is_in_bytes():
    memory = peak_memory()
    # A running Python interpreter needs more than 4 MiB; KiB would look far smaller.
    assert memory["self_bytes"] > 4 * 2**20
    assert memory["peak_bytes"] >= memory["self_bytes"]
