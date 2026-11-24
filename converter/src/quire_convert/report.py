"""The conversion report: what was done, how sure we are, and why the outcome is what it is."""

from __future__ import annotations

import dataclasses
import re
import resource
import sys
from collections import Counter
from dataclasses import dataclass, field
from typing import Any

from . import __version__
from .models import (
    JOB_FORMAT_VERSION,
    Issue,
    MetadataField,
    Outcome,
    Repair,
    Severity,
    SourceInventory,
    to_dict,
)

REDACTED = "[redacted]"


@dataclass
class Report:
    input_sha256: str
    outcome: Outcome
    reason: str
    settings: dict[str, Any]
    converter_version: str = __version__
    job_format_version: int = JOB_FORMAT_VERSION
    dependencies: dict[str, str] = field(default_factory=dict)
    pin_mismatches: list[str] = field(default_factory=list)
    language: str | None = None
    language_packs: list[str] = field(default_factory=list)
    model: dict[str, Any] = field(
        default_factory=lambda: {"mode": "off", "revision": None, "prompt_version": None}
    )
    stage_seconds: dict[str, float] = field(default_factory=dict)
    peak_memory: dict[str, int] = field(default_factory=dict)
    epub_sha256: str | None = None
    issue_counts: dict[str, int] = field(default_factory=dict)
    unsupported: list[str] = field(default_factory=list)
    # Set only for FAILED: {"stage": ..., "reason": ..., "retryable": bool}.
    failure: dict[str, Any] | None = None
    affected_pages: list[dict[str, Any]] = field(default_factory=list)
    issues: list[Issue] = field(default_factory=list)
    repairs: list[Repair] = field(default_factory=list)
    metadata: list[MetadataField] = field(default_factory=list)
    # "href#id" -> source pages of each aligned output block, in reading order. Ids and
    # page numbers only, so evaluation can map navigation targets back to the PDF.
    block_pages: dict[str, list[int]] = field(default_factory=dict)
    diagnostics: bool = False

    def to_dict(self) -> dict[str, Any]:
        return to_dict(self)


def count_issues(issues: list[Issue]) -> dict[str, int]:
    counts = Counter(i.severity.value for i in issues)
    return {s.value: counts.get(s.value, 0) for s in Severity}


def affected_pages(issues: list[Issue], source: SourceInventory | None) -> list[dict[str, Any]]:
    """Physical pages (with printed labels) of every issue that keeps the book from Ready."""
    labels = {p.index: p.label for p in source.pages} if source else {}
    pages: dict[int, str | None] = {}
    for issue in issues:
        if issue.blocks_ready and issue.page is not None:
            pages[issue.page] = issue.page_label or pages.get(issue.page) or labels.get(issue.page)
    return [{"page": p, "label": pages[p]} for p in sorted(pages)]


def peak_memory() -> dict[str, int]:
    """Peak resident memory of this process and of its largest finished child, in bytes.
    ``ru_maxrss`` is bytes on macOS and KiB on Linux."""
    scale = 1 if sys.platform == "darwin" else 1024
    own = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss * scale
    children = resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss * scale
    return {"self_bytes": own, "children_bytes": children, "peak_bytes": max(own, children)}


def _count(value: Any) -> int:
    if isinstance(value, (list, tuple)):
        return len(value)
    return int(value or 0)


def decide_outcome(
    issues: list[Issue],
    epubcheck: Any,
    semantic_issues: list[Issue],
    unsupported: list[str],
) -> tuple[Outcome, str]:
    """Map validation results and open issues to an outcome and a reason without book text.

    Ready needs a clean EPUBCheck run, no semantic problem and no issue that blocks Ready.
    Anything else that EPUBCheck still accepts as structurally valid goes to Review.
    """
    if unsupported:
        return Outcome.UNSUPPORTED, "; ".join(unsupported)
    fatal, errors = _count(epubcheck.fatal), _count(epubcheck.errors)
    if fatal or errors:
        return Outcome.FAILED, f"epubcheck reported {fatal} fatal and {errors} errors"
    blocking = [i for i in issues if i.blocks_ready]
    if epubcheck.ok and not blocking and not semantic_issues:
        return Outcome.READY, "all checks passed"
    parts = [] if epubcheck.ok else ["epubcheck did not pass"]
    if blocking:
        parts.append(f"{len(blocking)} issues need review")
    if semantic_issues:
        parts.append(f"{len(semantic_issues)} internal target or asset problems")
    return Outcome.REVIEW, "; ".join(parts)


# --------------------------------------------------------------------------- redaction

# Evidence survives an ordinary (non-diagnostics) report only when every token is a
# number, an id, or ``key=value`` of those. An id has no spaces and carries a digit or one
# of ``-_:#/.``, so "s3-b4", "qc-12", "OEBPS/a.html#x" and "ratio=0.42" survive, while
# any plain word ("river", "Chapter") marks the evidence as possible book text.
_NUMBER = r"-?\d+(?:\.\d+)?%?"
_ID = r"(?=[\w:#/.\-]*[\d_:#/.\-])[\w:#/.\-]+"
_ATOM = rf"(?:{_NUMBER}|{_ID})"
_TOKEN = re.compile(rf"(?:[a-z_][a-z0-9_]*=)?{_ATOM}(?:,{_ATOM})*")
_SEPARATORS = re.compile(r"[\s;]+|,\s+")


def is_safe_evidence(text: str) -> bool:
    tokens = [t for t in _SEPARATORS.split(text.strip()) if t]
    return all(_TOKEN.fullmatch(t) for t in tokens)


def _scrub(text: str) -> str:
    return text if is_safe_evidence(text) else REDACTED


def redact(report: Report) -> Report:
    """Return a copy of ``report`` with anything that may be book text removed.

    Issue and metadata evidence is kept only when it is ids and measurements (see
    ``is_safe_evidence``); repair evidence entries likewise; repair before/after values are
    always dropped. Diagnostics reports are returned unchanged.
    """
    if report.diagnostics:
        return report
    return dataclasses.replace(
        report,
        issues=[dataclasses.replace(i, evidence=_scrub(i.evidence)) for i in report.issues],
        repairs=[
            dataclasses.replace(
                r, evidence=[_scrub(e) for e in r.evidence], before=None, after=None
            )
            for r in report.repairs
        ],
        metadata=[dataclasses.replace(m, evidence=_scrub(m.evidence)) for m in report.metadata],
    )
