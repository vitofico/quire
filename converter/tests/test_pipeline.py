import json
import sys
import types
from types import SimpleNamespace

import pytest

from quire_convert import pipeline
from quire_convert.models import (
    Box,
    Outcome,
    OutputBlock,
    Severity,
    SourceInventory,
    SourceSpan,
    StageError,
)

SECRET = "the white post stood alone"


class FakeToolchain:
    def versions(self):
        return {"ebook_convert": "9.18.0"}

    def pin_mismatches(self):
        return []

    def require(self, *names):
        pass


class FakeBook:
    def __init__(self, path):
        self.path = path
        ids = [f"uuid:{id(self)}", "isbn:9780000000002", "urn:uuid:catalog-1"]
        self.metadata = SimpleNamespace(identifiers=ids)

    @classmethod
    def load(cls, path):
        return cls(path)

    def ensure_block_ids(self):
        span = SourceSpan(page=1, box=Box(0, 0, 1, 1), text=SECRET)
        return [OutputBlock("qc-1", "OEBPS/a.html", "p", SECRET, spans=[span])]

    def save(self, path, *, modified):
        path.write_bytes(f"epub {modified} {self.metadata.identifiers}".encode())


def _source(unsupported=()):
    return SourceInventory(
        path="in.pdf",
        sha256="aa",
        page_count=3,
        encrypted=False,
        needs_password=False,
        info={},
        xmp={},
        language="en",
        pages=[SimpleNamespace(index=i, label=["i", "1", "2"][i]) for i in range(3)],
        bookmarks=[],
        named_dests={},
        complexity={},
        unsupported=list(unsupported),
        resource={},
    )


@pytest.fixture
def stages(monkeypatch):
    """Install fake stage modules; tests tweak ``calls``/hooks to steer the outcome."""
    state = SimpleNamespace(
        calls=[],
        unsupported=[],
        check=SimpleNamespace(ok=True, fatal=[], errors=[], warnings=[], version="5.4.0"),
        semantic=[],
        coverage_issue=None,
        calibre_error=None,
        repairs=[],
    )

    def module(name, **attrs):
        mod = types.ModuleType(name)
        mod.__dict__.update(attrs)
        monkeypatch.setitem(sys.modules, name, mod)

    def track(name, result=None):
        def fn(*args, **kwargs):
            state.calls.append(name)
            return result(*args, **kwargs) if callable(result) else result

        return fn

    def calibre(pdf, out_epub, workdir, toolchain, *, timeout, debug_dir=None):
        state.calls.append("calibre")
        if state.calibre_error:
            raise state.calibre_error
        out_epub.write_bytes(b"calibre")
        return SimpleNamespace(epub_path=out_epub)

    def account(ctx):
        state.calls.append("coverage")
        if state.coverage_issue:
            ctx.issue("align.omitted_span", state.coverage_issue, "lost text", page=2)

    def rename(ctx):
        state.calls.append("repair")
        ctx.record("chapters", "promote_heading", "a.html#qc-1", ["s1-b0"], before=SECRET)

    module("quire_convert.tools", find_tools=track("tools", FakeToolchain()))
    module(
        "quire_convert.source.preflight",
        inspect_pdf=track("preflight", lambda *a, **k: _source(state.unsupported)),
    )
    module("quire_convert.epub.calibre", run_calibre=calibre)
    module("quire_convert.epub.book", Book=FakeBook)
    module("quire_convert.align.align", align_book=track("align"))
    module("quire_convert.align.coverage", account=account)
    module("quire_convert.repair", REPAIRS=state.repairs)
    state.repairs.append(rename)
    module(
        "quire_convert.epub.validate",
        run_epubcheck=track("epubcheck", lambda *a, **k: state.check),
        semantic_check=track("semantic", lambda *a: state.semantic),
    )
    return state


@pytest.fixture
def pdf(tmp_path):
    path = tmp_path / "in.pdf"
    path.write_bytes(b"%PDF-1.7 fake")
    return path


def _convert(pdf, tmp_path, **kw):
    return pipeline.convert(pdf, job_root=tmp_path / "jobs", **kw)


def test_ready_publishes_epub_and_redacted_report(stages, pdf, tmp_path):
    result = _convert(pdf, tmp_path)
    assert (result.outcome, result.exit_code) == (Outcome.READY, 0)
    manifest = result.job.read_manifest()
    assert manifest["outcome"] == "ready"
    assert result.epub_path == result.attempt.path("book.epub") == result.job.artifact("epub")
    report_text = result.attempt.path("report.json").read_text()
    assert SECRET not in report_text
    report = json.loads(report_text)
    assert report["epub_sha256"] == manifest["artifacts"]["epub"]["sha256"]
    assert report["block_pages"] == {"OEBPS/a.html#qc-1": [1]}
    assert report["dependencies"] == {"ebook_convert": "9.18.0"}
    assert report["repairs"][0]["before"] is None
    assert list(result.report.stage_seconds) == [
        "tools",
        "preflight",
        "calibre",
        "load",
        "align",
        "repair",
        "repair.rename",
        "coverage",
        "package",
        "validate",
    ]


def test_diagnostics_keeps_text_evidence(stages, pdf, tmp_path):
    result = _convert(pdf, tmp_path, diagnostics=True)
    report = json.loads(result.attempt.path("report.json").read_text())
    assert report["repairs"][0]["before"] == SECRET


def test_review_lists_affected_pages(stages, pdf, tmp_path):
    stages.coverage_issue = Severity.MAJOR
    result = _convert(pdf, tmp_path)
    assert (result.outcome, result.exit_code) == (Outcome.REVIEW, 2)
    assert result.report.affected_pages == [{"page": 2, "label": "2"}]
    assert "epub" in result.job.read_manifest()["artifacts"]


def test_semantic_problems_send_to_review(stages, pdf, tmp_path):
    from quire_convert.models import Issue

    stages.semantic = [Issue("epub.broken_link", Severity.MAJOR, "dangling href")]
    result = _convert(pdf, tmp_path)
    assert result.outcome is Outcome.REVIEW
    assert result.report.issue_counts["major"] == 1


def test_unsupported_stops_before_calibre(stages, pdf, tmp_path):
    stages.unsupported = ["scanned book"]
    result = _convert(pdf, tmp_path)
    assert (result.outcome, result.exit_code) == (Outcome.UNSUPPORTED, 3)
    assert "calibre" not in stages.calls
    assert result.epub_path is None
    manifest = result.job.read_manifest()
    assert manifest["outcome"] == "unsupported" and "epub" not in manifest["artifacts"]
    assert result.report.unsupported == ["scanned book"]


def test_stage_error_fails_with_stage_and_retryability(stages, pdf, tmp_path):
    stages.calibre_error = StageError("calibre", "timed out after 900s", retryable=True)
    result = _convert(pdf, tmp_path)
    assert (result.outcome, result.exit_code) == (Outcome.FAILED, 1)
    assert result.report.failure == {
        "stage": "calibre",
        "reason": "timed out after 900s",
        "retryable": True,
    }
    assert "epub" not in result.job.read_manifest()["artifacts"]


def test_crash_names_the_running_stage_without_its_message(stages, pdf, tmp_path):
    def broken(ctx):
        raise ValueError(SECRET)

    stages.repairs.append(broken)
    result = _convert(pdf, tmp_path)
    assert result.outcome is Outcome.FAILED
    assert result.report.failure["stage"] == "repair.broken"
    assert SECRET not in result.attempt.path("report.json").read_text()


def test_missing_stage_module_fails_that_stage(stages, pdf, tmp_path, monkeypatch):
    monkeypatch.setitem(sys.modules, "quire_convert.align.align", None)
    result = _convert(pdf, tmp_path)
    assert result.outcome is Outcome.FAILED
    assert result.report.failure["stage"] == "align"


def test_invalid_epub_fails_validate_and_rerun_hides_older_epub(stages, pdf, tmp_path):
    first = _convert(pdf, tmp_path)
    assert first.outcome is Outcome.READY
    stages.check = SimpleNamespace(ok=False, fatal=[], errors=[{"id": "RSC-005"}], warnings=[])
    second = _convert(pdf, tmp_path)
    assert second.job.dir == first.job.dir and second.attempt.number == 2
    assert second.outcome is Outcome.FAILED
    assert second.report.failure["stage"] == "validate"
    assert second.attempt.path("book.epub").exists()
    manifest = second.job.read_manifest()
    assert manifest["attempt"] == 2 and "epub" not in manifest["artifacts"]


def test_baseline_detects_but_skips_repairs(stages, pdf, tmp_path):
    result = _convert(pdf, tmp_path, variant="baseline")
    assert result.outcome is Outcome.READY
    assert {"align", "coverage"} <= set(stages.calls)
    assert "repair" not in stages.calls


def test_identical_inputs_give_identical_bytes(stages, pdf, tmp_path):
    a = _convert(pdf, tmp_path)
    b = _convert(pdf, tmp_path)
    assert a.epub_path.read_bytes() == b.epub_path.read_bytes()
    text = a.epub_path.read_text()
    assert "'uuid:" not in text  # Calibre's random id is gone
    assert text.count("urn:uuid:") == 2  # the rendition id plus the catalog one
    assert "isbn:9780000000002" in text and "urn:uuid:catalog-1" in text
    other = _convert(pdf, tmp_path, variant="baseline")
    assert other.job.id != a.job.id


def test_deterministic_modified_format():
    value = pipeline.deterministic_modified("aa", {"variant": "rules"})
    assert value == pipeline.deterministic_modified("aa", {"variant": "rules"})
    assert value != pipeline.deterministic_modified("bb", {"variant": "rules"})
    assert len(value) == 20 and value.endswith("Z") and "2000" <= value[:4] < "2020"


@pytest.mark.parametrize("variant", ["assisted", "magic"])
def test_unknown_or_unreleased_variant_is_a_usage_error(pdf, tmp_path, variant):
    with pytest.raises(pipeline.UsageError):
        _convert(pdf, tmp_path, variant=variant)
