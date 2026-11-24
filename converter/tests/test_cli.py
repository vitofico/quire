import json
import sys
import types
from types import SimpleNamespace

import pytest

from quire_convert import cli, pipeline
from quire_convert.models import USAGE_EXIT_CODE, Outcome


@pytest.fixture
def pdf(tmp_path):
    path = tmp_path / "in.pdf"
    path.write_bytes(b"%PDF fake")
    return path


@pytest.fixture
def fake_convert(monkeypatch, tmp_path):
    """Replace pipeline.convert with one that returns the outcome in ``state.outcome``."""
    state = SimpleNamespace(outcome=Outcome.READY, kwargs=None)

    def convert(pdf, **kwargs):
        state.kwargs = kwargs
        attempt_dir = tmp_path / "attempt"
        attempt_dir.mkdir(exist_ok=True)
        (attempt_dir / "report.json").write_text(json.dumps({"outcome": state.outcome.value}))
        epub = None
        if state.outcome in (Outcome.READY, Outcome.REVIEW):
            epub = attempt_dir / "book.epub"
            epub.write_bytes(b"new epub")
        return SimpleNamespace(
            outcome=state.outcome,
            exit_code=state.outcome.exit_code,
            report=SimpleNamespace(reason="because"),
            epub_path=epub,
            job=SimpleNamespace(dir=tmp_path / "job"),
            attempt=SimpleNamespace(number=1, path=lambda name: attempt_dir / name),
        )

    monkeypatch.setattr(pipeline, "convert", convert)
    return state


def _run_convert(pdf, tmp_path, *extra):
    out, report = tmp_path / "out" / "book.epub", tmp_path / "out" / "report.json"
    argv = ["convert", str(pdf), "--out", str(out), "--report", str(report), "--model", "off"]
    code = cli.main([*argv, "--job-root", str(tmp_path / "jobs"), *extra])
    return code, out, report


@pytest.mark.parametrize(
    ("outcome", "extra", "code", "written"),
    [
        (Outcome.READY, [], 0, True),
        (Outcome.REVIEW, [], 2, False),
        (Outcome.REVIEW, ["--allow-review"], 2, True),
        (Outcome.UNSUPPORTED, [], 3, False),
        (Outcome.FAILED, [], 1, False),
    ],
)
def test_convert_exit_codes_and_outputs(fake_convert, pdf, tmp_path, outcome, extra, code, written):
    fake_convert.outcome = outcome
    result, out, report = _run_convert(pdf, tmp_path, *extra)
    assert result == code
    assert json.loads(report.read_text())["outcome"] == outcome.value
    assert out.exists() is written


def test_convert_passes_options(fake_convert, pdf, tmp_path):
    meta = tmp_path / "catalog.json"
    meta.write_text(json.dumps({"title": "T"}))
    extra = ["--variant", "baseline", "--metadata", str(meta), "--diagnostics"]
    _run_convert(pdf, tmp_path, *extra, "--html-report", str(tmp_path / "html"))
    kw = fake_convert.kwargs
    assert kw["variant"] == "baseline" and kw["diagnostics"] is True
    assert kw["catalog_metadata"] == {"title": "T"}
    assert kw["html_report_dir"] == tmp_path / "html"
    assert kw["job_root"] == tmp_path / "jobs"


def _usage(argv):
    with pytest.raises(SystemExit) as exc:
        cli.main(argv)
    return exc.value.code


@pytest.mark.parametrize(
    "argv",
    [
        [],
        ["convert"],
        ["convert", "in.pdf", "--out", "a.epub"],
        ["convert", "in.pdf", "--out", "a.epub", "--report", "r.json", "--model", "on"],
        ["convert", "in.pdf", "--out", "a.epub", "--report", "r.json", "--variant", "assisted"],
        ["evaluate", "corpus.toml", "--variant", "magic"],
        ["frobnicate"],
    ],
)
def test_usage_errors_exit_64(argv):
    assert _usage(argv) == USAGE_EXIT_CODE


def test_missing_input_and_bad_metadata_are_usage_errors(fake_convert, pdf, tmp_path):
    assert _usage(["inspect", str(tmp_path / "missing.pdf")]) == USAGE_EXIT_CODE
    bad = tmp_path / "bad.json"
    bad.write_text("[1, 2")
    out = ["--out", str(tmp_path / "o.epub"), "--report", str(tmp_path / "r.json")]
    assert _usage(["convert", str(pdf), *out, "--metadata", str(bad)]) == USAGE_EXIT_CODE


def test_evaluate_needs_a_variant(tmp_path):
    corpus = tmp_path / "corpus.toml"
    corpus.write_text('[corpus]\nname = "x"\n')
    assert _usage(["evaluate", str(corpus)]) == USAGE_EXIT_CODE
    assert _usage(["evaluate", str(corpus), "--variant", "assisted"]) == USAGE_EXIT_CODE


SECRET = "Mara could see the river"  # pragma: allowlist secret


def _inventory():
    from quire_convert.models import Box, Line, PageInventory, SourceInventory, TextBlock, Word

    def page(i, label, kind):
        word = Word(SECRET, Box(0, 0, 1, 1))
        block = TextBlock(f"s{i}-b0", i, [Line([word], Box(0, 0, 1, 1))], Box(0, 0, 1, 1))
        return PageInventory(
            i, label, [], 420, 600, 0, (0, 0, 420, 600), (0, 0, 420, 600),
            [block], [], "native", 0.1, 0.9, kind,
        )  # fmt: skip

    labels = [("i", "text"), ("ii", "blank"), ("1", "text"), ("2", "text"), (None, "image")]
    return SourceInventory(
        path="in.pdf",
        sha256="ab" * 32,
        page_count=5,
        encrypted=False,
        needs_password=False,
        info={"title": SECRET},
        xmp={},
        language="en",
        pages=[page(i, label, kind) for i, (label, kind) in enumerate(labels)],
        bookmarks=[],
        named_dests={},
        complexity={"columns": 1},
        unsupported=[],
        resource={"pages": 5, "max_pages": 2000},
    )


@pytest.fixture
def fake_inspect(monkeypatch):
    for name, attrs in {
        "quire_convert.source.preflight": {"inspect_pdf": lambda *a, **k: _inventory()},
        "quire_convert.tools": {"find_tools": lambda: None},
    }.items():
        mod = types.ModuleType(name)
        mod.__dict__.update(attrs)
        monkeypatch.setitem(sys.modules, name, mod)


def test_inspect_summary_has_no_book_text(fake_inspect, pdf, capsys):
    assert cli.main(["inspect", str(pdf)]) == 0
    human = capsys.readouterr().out
    assert "pages: 5" in human and "text 3" in human and "roman i-ii" in human
    assert SECRET not in human
    assert cli.main(["inspect", str(pdf), "--json"]) == 0
    data = json.loads(capsys.readouterr().out)
    assert SECRET not in json.dumps(data)
    assert data["page_types"] == {"blank": 1, "image": 1, "text": 3}


def test_label_runs():
    runs = cli.label_runs(["i", "ii", "iii", "1", "2", "4", None, None])
    assert [(r["style"], r["start"], r["end"], r["first"], r["last"]) for r in runs] == [
        ("roman", 0, 2, "i", "iii"),
        ("arabic", 3, 4, "1", "2"),
        ("arabic", 5, 5, "4", "4"),
        ("none", 6, 7, None, None),
    ]
