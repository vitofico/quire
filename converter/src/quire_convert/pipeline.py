"""The convert pipeline: run every stage inside a job attempt and publish its manifest."""

from __future__ import annotations

import hashlib
import json
import logging
import time
import traceback
import uuid
from collections.abc import Iterator
from contextlib import contextmanager
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

from .context import ConvertContext
from .job import Attempt, Job, atomic_write_bytes, sha256_file
from .models import Issue, Outcome, SourceInventory, StageError
from .report import Report, affected_pages, count_issues, decide_outcome, peak_memory, redact

log = logging.getLogger(__name__)

VARIANTS = ("baseline", "rules")
DEFAULT_TIMEOUTS = {"calibre": 900.0, "epubcheck": 300.0}
REQUIRED_TOOLS = ("ebook_convert", "java", "epubcheck_jar")
_EPOCH = datetime(2000, 1, 1, tzinfo=UTC)
_SPAN_SECONDS = 20 * 365 * 24 * 3600


class UsageError(ValueError):
    """The request cannot be run as given (maps to models.USAGE_EXIT_CODE)."""


@dataclass
class ConvertResult:
    outcome: Outcome
    exit_code: int
    report: Report
    epub_path: Path | None  # the published EPUB, only for Ready and Review
    job: Job
    attempt: Attempt


def deterministic_modified(input_sha256: str, settings: dict[str, Any]) -> str:
    """A ``dcterms:modified`` value fixed by the input and settings, so identical runs write
    identical bytes: a second offset into 2000-2019 taken from their hash."""
    payload = json.dumps({"input": input_sha256, "settings": settings}, sort_keys=True)
    offset = int(hashlib.sha256(payload.encode()).hexdigest()[:12], 16) % _SPAN_SECONDS
    return (_EPOCH + timedelta(seconds=offset)).strftime("%Y-%m-%dT%H:%M:%SZ")


def rendition_identifier(input_sha256: str, settings: dict[str, Any]) -> str:
    """Stable ``urn:uuid`` for this source under these settings: the EPUB's unique id."""
    payload = json.dumps({"input": input_sha256, "settings": settings}, sort_keys=True)
    return f"urn:uuid:{uuid.uuid5(uuid.NAMESPACE_URL, payload)}"


def _set_rendition_identifier(book: Any, identifier: str) -> None:
    """Put the stable id in the rendition slot (identifiers[0]) and drop Calibre's random
    ``uuid:`` entries. Every other identifier stays, including a catalog ``urn:uuid:``."""
    others = [i for i in book.metadata.identifiers if not i.lower().startswith("uuid:")]
    book.metadata.identifiers = [identifier, *others]


def effective_settings(
    variant: str, catalog_metadata: dict[str, Any] | None, diagnostics: bool, timeouts: dict
) -> dict[str, Any]:
    catalog = None
    if catalog_metadata is not None:
        blob = json.dumps(catalog_metadata, sort_keys=True).encode()
        catalog = hashlib.sha256(blob).hexdigest()
    return {
        "variant": variant,
        "model": "off",
        "diagnostics": diagnostics,
        "catalog_metadata_sha256": catalog,
        "timeouts": timeouts,
    }


@dataclass
class _Run:
    """What the stages produced so far; a failure can happen after any of them."""

    stage: str = "start"
    stage_seconds: dict[str, float] = field(default_factory=dict)
    toolchain: Any = None
    source: SourceInventory | None = None
    ctx: ConvertContext | None = None
    epub: Path | None = None
    semantic: list[Issue] = field(default_factory=list)

    @contextmanager
    def stage_of(self, name: str) -> Iterator[None]:
        self.stage = name
        start = time.perf_counter()
        try:
            yield
        finally:
            seconds = time.perf_counter() - start
            self.stage_seconds[name] = round(self.stage_seconds.get(name, 0.0) + seconds, 3)
            log.info("stage %s took %.2fs", name, seconds)


def convert(
    pdf: Path,
    *,
    job_root: Path,
    variant: str = "rules",
    catalog_metadata: dict[str, Any] | None = None,
    diagnostics: bool = False,
    timeouts: dict[str, float] | None = None,
    html_report_dir: Path | None = None,
) -> ConvertResult:
    """Convert ``pdf`` into a new attempt of its job and publish the attempt's manifest.

    Stage failures never raise: they become a Failed outcome with the stage name, and the
    manifest is still published. Only a request that cannot run at all raises UsageError.
    """
    if variant == "assisted":
        raise UsageError("the assisted variant needs the model, which this release lacks")
    if variant not in VARIANTS:
        raise UsageError(f"unknown variant {variant!r}")
    pdf = Path(pdf)
    timeouts = DEFAULT_TIMEOUTS | (timeouts or {})
    settings = effective_settings(variant, catalog_metadata, diagnostics, timeouts)
    input_sha256 = sha256_file(pdf)
    job = Job.open_or_create(job_root, input_sha256=input_sha256, settings=settings)
    attempt = job.new_attempt()
    log.info("job %s attempt %d variant %s", job.id, attempt.number, variant)

    run = _Run()
    failure = None
    try:
        outcome, reason = _stages(run, pdf, attempt, settings, catalog_metadata, input_sha256)
    except StageError as exc:
        failure = {"stage": exc.stage, "reason": exc.reason, "retryable": exc.retryable}
    except Exception as exc:  # any crash must still publish a Failed manifest
        failure = {"stage": run.stage, "reason": f"unexpected {type(exc).__name__}"}
        failure["retryable"] = isinstance(exc, (TimeoutError, OSError))
        log.error("stage %s crashed with %s", run.stage, type(exc).__name__)
        if diagnostics:
            attempt.path("diagnostics/traceback.txt").write_text(traceback.format_exc())
    if failure:
        outcome, reason = Outcome.FAILED, f"{failure['stage']}: {failure['reason']}"
    elif outcome is Outcome.FAILED:
        failure = {"stage": "validate", "reason": reason, "retryable": False}

    report = _build_report(run, input_sha256, settings, outcome, reason, failure, diagnostics)
    report_path = attempt.path("report.json")
    atomic_write_bytes(report_path, _json(redact(report).to_dict()))
    artifacts = {"report": report_path}
    published_epub = None
    if outcome in (Outcome.READY, Outcome.REVIEW):
        artifacts["epub"] = published_epub = run.epub
    summary = {
        "outcome": outcome.value,
        "reason": reason,
        "input_sha256": input_sha256,
        "epub_sha256": report.epub_sha256 if published_epub else None,
        "issue_counts": report.issue_counts,
        "variant": variant,
    }
    job.publish(attempt, outcome, artifacts, summary)
    log.info("job %s attempt %d published %s", job.id, attempt.number, outcome.value)
    if html_report_dir is not None and run.ctx is not None:
        _write_html_report(run.ctx, Path(html_report_dir))
    return ConvertResult(outcome, outcome.exit_code, report, published_epub, job, attempt)


def _stages(
    run: _Run,
    pdf: Path,
    attempt: Attempt,
    settings: dict[str, Any],
    catalog_metadata: dict[str, Any] | None,
    input_sha256: str,
) -> tuple[Outcome, str]:
    # Stage modules are imported inside their stage, so a missing or broken one fails
    # that stage by name instead of the whole command.
    timeouts = settings["timeouts"]
    with run.stage_of("tools"):
        from .tools import find_tools

        run.toolchain = find_tools()
        run.toolchain.require(*REQUIRED_TOOLS)
    with run.stage_of("preflight"):
        from .source.preflight import inspect_pdf

        run.source = inspect_pdf(pdf, attempt.work, toolchain=run.toolchain)
    if run.source.unsupported:
        return Outcome.UNSUPPORTED, "; ".join(run.source.unsupported)

    with run.stage_of("calibre"):
        from .epub.calibre import run_calibre

        calibre = run_calibre(
            pdf,
            attempt.path("work/calibre.epub"),
            attempt.work,
            run.toolchain,
            timeout=timeouts["calibre"],
            debug_dir=attempt.diagnostics if settings["diagnostics"] else None,
        )
    with run.stage_of("load"):
        from .epub.book import Book

        book = Book.load(calibre.epub_path)
        ctx = ConvertContext(
            pdf_path=pdf,
            workdir=attempt.work,
            source=run.source,
            book=book,
            toolchain=run.toolchain,
            variant=settings["variant"],
            settings=settings,
            catalog_metadata=catalog_metadata,
            diagnostics=settings["diagnostics"],
        )
        ctx.blocks = book.ensure_block_ids()
        run.ctx = ctx
    _detect_and_repair(run, ctx)

    epub = attempt.path("book.epub")
    with run.stage_of("package"):
        _set_rendition_identifier(book, rendition_identifier(input_sha256, settings))
        book.save(epub, modified=deterministic_modified(input_sha256, settings))
    run.epub = epub
    with run.stage_of("validate"):
        from .epub.validate import run_epubcheck, semantic_check

        checked = run_epubcheck(epub, run.toolchain, timeout=timeouts["epubcheck"])
        run.semantic = semantic_check(epub)
    return decide_outcome(ctx.issues, checked, run.semantic, [])


def _detect_and_repair(run: _Run, ctx: ConvertContext) -> None:
    # Detection runs for every variant, so a direct-Calibre baseline cannot report Ready
    # while it silently lost a drawing; only the repairs are variant-specific.
    with run.stage_of("align"):
        from .align.align import align_book

        align_book(ctx)
    if ctx.variant != "baseline":
        with run.stage_of("repair"):
            from .repair import REPAIRS
        for repair in REPAIRS:
            with run.stage_of(f"repair.{_repair_name(repair)}"):
                repair(ctx)
    with run.stage_of("coverage"):
        from .align.coverage import account

        account(ctx)


def _repair_name(repair) -> str:
    # Repair families expose ``apply`` in their own module; name the stage after the module.
    if repair.__name__ == "apply":
        return repair.__module__.rsplit(".", 1)[-1]
    return repair.__name__


def _build_report(
    run: _Run,
    input_sha256: str,
    settings: dict[str, Any],
    outcome: Outcome,
    reason: str,
    failure: dict[str, Any] | None,
    diagnostics: bool,
) -> Report:
    ctx = run.ctx
    issues = (list(ctx.issues) if ctx else []) + run.semantic
    toolchain = run.toolchain
    has_epub = run.epub is not None and run.epub.exists()
    return Report(
        input_sha256=input_sha256,
        outcome=outcome,
        reason=reason,
        settings=settings,
        dependencies=toolchain.versions() if toolchain else {},
        pin_mismatches=toolchain.pin_mismatches() if toolchain else [],
        language=run.source.language if run.source else None,
        stage_seconds=run.stage_seconds,
        peak_memory=peak_memory(),
        epub_sha256=sha256_file(run.epub) if has_epub else None,
        issue_counts=count_issues(issues),
        unsupported=list(run.source.unsupported) if run.source else [],
        failure=failure,
        affected_pages=affected_pages(issues, run.source),
        issues=issues,
        repairs=list(ctx.repairs) if ctx else [],
        metadata=list(ctx.metadata) if ctx else [],
        block_pages=_block_pages(ctx),
        coverage=ctx.alignment.coverage if ctx and ctx.alignment else {},
        diagnostics=diagnostics,
    )


def _block_pages(ctx: ConvertContext | None) -> dict[str, list[int]]:
    if ctx is None:
        return {}
    pages = {}
    for block in ctx.blocks:
        if block.spans:
            pages[f"{block.href}#{block.id}"] = sorted({s.page for s in block.spans})
    return pages


def _write_html_report(ctx: ConvertContext, out_dir: Path) -> None:
    """Local review aid only: a failure here never changes the conversion outcome."""
    try:
        from .report_html import write_html_report

        path = write_html_report(ctx, out_dir)
        log.info("html report written to %s", path)
    except Exception as exc:
        log.warning("html report failed: %s", type(exc).__name__)


def _json(data: Any) -> bytes:
    return json.dumps(data, indent=2, sort_keys=True, ensure_ascii=False).encode() + b"\n"
