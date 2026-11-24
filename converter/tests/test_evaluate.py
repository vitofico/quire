import csv
import json
import shutil
import zipfile
from types import SimpleNamespace

import pytest

from quire_convert import evaluate
from quire_convert.models import Outcome
from quire_convert.pipeline import UsageError

XHTML = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">'
    "<head><title>t</title></head><body>{}</body></html>"
)
OPF = """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
<manifest>
<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
<item id="c1" href="text/c1.xhtml" media-type="application/xhtml+xml"/>
<item id="c2" href="text/c2.xhtml" media-type="application/xhtml+xml"/>
</manifest>
<spine><itemref idref="c1"/><itemref idref="c2"/></spine>
</package>"""
CONTAINER = """<?xml version="1.0"?>
<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
<rootfiles><rootfile full-path="OEBPS/content.opf"
 media-type="application/oebps-package+xml"/></rootfiles></container>"""
NAV = (
    '<nav epub:type="toc"><ol>'
    '<li><a href="text/c1.xhtml#ch1">Chapter One: The River</a></li>'
    '<li><a href="text/c2.xhtml">Chapter Two</a><ol>'
    '<li><a href="text/c2.xhtml#dup">Chapter One &#8211; the river</a></li></ol></li>'
    '<li><a href="text/c2.xhtml#idx">Index</a></li>'
    "</ol></nav>"
)


def build_epub(path, anchors=True):
    def page(n):
        return f'<span id="qc-page-{n}"/>' if anchors else ""

    c1 = (
        f"{page(0)}<p>Front matter</p>{page(1)}"
        '<h1 id="ch1">Chapter One: The River</h1>'
        '<p>From the window Mara could see 1847 the river<a href="#n1"><sup>1</sup></a></p>'
        f'{page(2)}<figure><img src="../x.png" alt=""/>'
        "<figcaption>Figure 1. The old bridge at dawn.</figcaption></figure>"
        '<p id="n1">1 First words of the note, and more.</p>'
    )
    c2 = (
        f'{page(3)}<h1 id="qc-5">Chapter Two</h1><p id="dup">Chapter One - the river</p>'
        '<p id="idx">bridge, <a href="c2.xhtml#qc-page-3">3</a></p>'
        '<p>river, <a href="c1.xhtml#ch1">2</a></p>'
    )
    with zipfile.ZipFile(path, "w") as zf:
        zf.writestr("mimetype", "application/epub+zip")
        zf.writestr("META-INF/container.xml", CONTAINER)
        zf.writestr("OEBPS/content.opf", OPF)
        zf.writestr("OEBPS/nav.xhtml", XHTML.format(NAV))
        zf.writestr("OEBPS/text/c1.xhtml", XHTML.format(c1))
        zf.writestr("OEBPS/text/c2.xhtml", XHTML.format(c2))
    return path


LABELS = {
    "pages": 4,
    "page_labels": ["i", "1", "2", "3"],
    "chapters": [
        {"title": "Chapter One: The River", "level": 1, "page": 1},
        {"title": "Chapter Two", "level": 1, "page": 3},
        {"title": "Epilogue", "level": 1, "page": 3},
    ],
    "figures": [
        {"id": "f1", "page": 2, "caption": "Figure 1. The old bridge at dawn."},
        {"id": "f2", "page": 2, "caption": "Figure 2. A map that was lost."},
        {"id": "f3", "page": 2},
    ],
    "notes": [
        {"marker": "1", "page": 1, "scope": "page", "text_start": "First words of the note"},
        {"marker": "1", "page": 2, "scope": "page", "text_start": "Another note"},
    ],
    "index": [{"term": "bridge", "pages": ["3"]}, {"term": "river", "pages": ["2"]}],
    "critical_tokens": ["1847", "Ponte Vecchio"],
    "passages": [
        {"page": 1, "text": "From the window Mara could see 1847 the river"},
        {"page": 1, "text": "From the windw Mara could see"},
        {"page": 1, "text": "Completely absent passage of text"},
    ],
}


@pytest.fixture
def epub(tmp_path):
    return build_epub(tmp_path / "book.epub")


def test_reads_nav_with_levels(epub):
    view = evaluate.read_epub(epub)
    assert [(e.title, e.level) for e in view.nav] == [
        ("Chapter One: The River", 1),
        ("Chapter Two", 1),
        ("Chapter One – the river", 2),
        ("Index", 1),
    ]
    assert view.has_page_anchors
    assert view.page_of(("doc", "OEBPS/text/c2.xhtml")) == (3, "anchor")
    assert view.page_of(("id", "OEBPS/text/c1.xhtml", "ch1")) == (1, "anchor")


def test_chapters_match_title_and_page(epub):
    score = evaluate.score_chapters(LABELS, evaluate.read_epub(epub))
    assert score["matched"] == 2 and score["nav_entries"] == 4
    assert score["precision"] == 0.5 and score["recall"] == pytest.approx(2 / 3)
    assert score["match_mode"] == "title_and_page"


def test_duplicate_chapter_name_on_the_wrong_page_is_a_wrong_target(epub):
    labels = {"chapters": [{"title": "Chapter One: The River", "level": 1, "page": 2}]}
    score = evaluate.score_chapters(labels, evaluate.read_epub(epub))
    assert (score["matched"], score["wrong_target"]) == (0, 1)


def test_without_page_anchors_chapters_fall_back_to_spans_then_titles(tmp_path):
    epub = build_epub(tmp_path / "plain.epub", anchors=False)
    titles = evaluate.score_chapters(LABELS, evaluate.read_epub(epub))
    assert titles["match_mode"] == "title_only" and titles["matched"] == 2
    spans = {"OEBPS/text/c1.xhtml#ch1": [1], "OEBPS/text/c2.xhtml#qc-5": [3]}
    view = evaluate.read_epub(epub, spans)
    assert view.page_of(("doc", "OEBPS/text/c2.xhtml")) == (3, "spans")
    scored = evaluate.score_chapters(LABELS, view)
    assert scored["match_mode"] == "title_and_page" and scored["matched"] == 2


def test_figures_notes_index_tokens(epub):
    view = evaluate.read_epub(epub)
    figures = evaluate.score_figures(LABELS, view)
    assert (figures["captioned"], figures["pairs_complete"], figures["images_in_epub"]) == (2, 1, 1)
    notes = evaluate.score_notes(LABELS, view)
    assert notes == {"annotated": 2, "correct": 1, "page_unverified": 0}
    index = evaluate.score_index(LABELS, view)
    assert index == {"annotated": 2, "correct": 1, "unverified": 0}
    tokens = evaluate.score_tokens(LABELS, view)
    assert (tokens["present"], tokens["missing"]) == (1, ["Ponte Vecchio"])


def test_index_without_anchors_is_unverified(tmp_path):
    view = evaluate.read_epub(build_epub(tmp_path / "plain.epub", anchors=False))
    assert evaluate.score_index(LABELS, view)["unverified"] == 2


def test_character_error_rate(epub):
    passages = evaluate.score_passages(LABELS, evaluate.read_epub(epub))
    assert passages["count"] == 3
    exact, typo, absent = passages["cer"]
    assert exact == 0.0
    assert typo == pytest.approx(1 / len("From the windw Mara could see"), abs=1e-3)
    assert absent == 1.0


def test_edit_distance_in():
    assert evaluate.edit_distance_in("abc", "xxabcxx") == 0
    assert evaluate.edit_distance_in("abc", "xxabxx") == 1
    assert evaluate.edit_distance_in("kitten", "sitting") == 2


def test_span_counts_ignore_navigation_and_metadata():
    issues = [
        {"code": "align.omitted_span"},
        {"code": "figure.lost"},
        {"code": "align.invented_span"},
        {"code": "nav.missing_chapter"},
        {"code": "meta.missing_title"},
    ]
    assert evaluate.span_counts(issues) == {"invented": 1, "omitted": 2}


def test_score_book_without_epub_scores_nothing_found():
    result = evaluate.score_book(LABELS, Outcome.FAILED, {"reason": "calibre: crash"}, None, 2.0)
    assert result["outcome"] == "failed" and not result["epub_readable"]
    assert result["chapters"]["recall"] == 0 and result["chapters"]["precision"] is None
    assert result["critical_tokens"]["present"] == 0


# --------------------------------------------------------------------------- corpus


def _corpus(tmp_path):
    for name in ("dev1", "dev2", "hold1"):
        (tmp_path / f"{name}.pdf").write_bytes(f"%PDF {name}".encode())
        (tmp_path / f"{name}.json").write_text(json.dumps(LABELS))
    books = "".join(
        f'[[book]]\nid = "{n}"\npath = "{n}.pdf"\nlabels = "{n}.json"\nsplit = "{s}"\n'
        f'family = "f-{n}"\nlanguage = "en"\nstrata = ["clean"]\nrights = "original"\n\n'
        for n, s in (("dev1", "dev"), ("dev2", "dev"), ("hold1", "holdout"))
    )
    path = tmp_path / "corpus.toml"
    path.write_text(
        '[corpus]\nname = "test"\nreference_machine = "machine.json"\n\n'
        "[gates]\nfrozen = false\n\n" + books
    )
    return path


def test_load_corpus(tmp_path):
    corpus = evaluate.load_corpus(_corpus(tmp_path))
    assert corpus.name == "test" and not corpus.frozen
    assert [b.id for b in corpus.books] == ["dev1", "dev2", "hold1"]
    assert corpus.books[0].path == tmp_path / "dev1.pdf"
    assert [b.id for b in evaluate.select_books(corpus, holdout=False)] == ["dev1", "dev2"]


def test_holdout_stays_closed_until_frozen_and_unchanged(tmp_path):
    path = _corpus(tmp_path)
    with pytest.raises(UsageError, match="frozen"):
        evaluate.select_books(evaluate.load_corpus(path), holdout=True)
    digest = evaluate.freeze(path)
    corpus = evaluate.load_corpus(path)
    assert corpus.frozen and corpus.holdout_sha256 == digest and corpus.frozen_at
    assert [b.id for b in evaluate.select_books(corpus, holdout=True)] == ["hold1"]
    with pytest.raises(UsageError, match="already frozen"):
        evaluate.freeze(path)
    # Dev books may change freely; a holdout edit (book or labels) must be refused.
    (tmp_path / "dev1.pdf").write_bytes(b"%PDF edited")
    evaluate.select_books(evaluate.load_corpus(path), holdout=True)
    (tmp_path / "hold1.json").write_text("{}")
    with pytest.raises(UsageError, match="changed"):
        evaluate.select_books(evaluate.load_corpus(path), holdout=True)


def test_set_toml_keys_adds_a_missing_section():
    text = evaluate.set_toml_keys('[corpus]\nname = "x"\n', "gates", {"frozen": "true"})
    assert text.endswith("[gates]\nfrozen = true\n")
    again = evaluate.set_toml_keys(text, "gates", {"frozen": "false", "frozen_at": '"t"'})
    assert again.count("frozen =") == 1 and 'frozen_at = "t"' in again


@pytest.mark.parametrize(
    "book",
    [
        'id = "a"\npath = "a.pdf"\nlabels = "a.json"\nsplit = "test"\n',
        'id = "a"\npath = "a.pdf"\nsplit = "dev"\n',
    ],
)
def test_bad_corpus_entries_are_usage_errors(tmp_path, book):
    path = tmp_path / "corpus.toml"
    path.write_text("[[book]]\n" + book)
    with pytest.raises(UsageError):
        evaluate.load_corpus(path)


def test_record_machine(tmp_path, monkeypatch):
    import quire_convert.tools

    tools = SimpleNamespace(versions=lambda: {"ebook_convert": "9.18.0"})
    monkeypatch.setattr(quire_convert.tools, "find_tools", lambda: tools)
    path = evaluate.record_machine(_corpus(tmp_path))
    assert path == tmp_path / "machine.json"
    data = json.loads(path.read_text())
    assert data["cores"] >= 1 and data["ram_bytes"] > 0 and data["cpu"]
    assert data["tools"] == {"ebook_convert": "9.18.0"}


def test_convert_book_maps_unexpected_exit_codes_to_failed(tmp_path):
    book = evaluate.CorpusBook("x", tmp_path / "missing.pdf", tmp_path / "x.json", "dev")
    outcome, report, epub, elapsed = evaluate._convert_book(book, "rules", tmp_path, tmp_path)
    assert outcome is Outcome.FAILED and epub is None
    assert report["reason"] == "convert exited 64"


@pytest.fixture
def fake_books(monkeypatch, epub):
    def convert(book, variant, job_root, out):
        target = out / "epub" / f"{book.id}.epub"
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(epub, target)
        report = {
            "reason": "ok",
            "peak_memory": {"peak_bytes": 300 * 2**20},
            "issues": [{"code": "align.omitted_span"}],
        }
        outcome = Outcome.READY if variant == "rules" else Outcome.REVIEW
        return outcome, report, target, 1.5

    monkeypatch.setattr(evaluate, "_convert_book", convert)


def _sheet(out):
    with open(out / "scoring-sheet.csv", newline="") as fh:
        return list(csv.DictReader(fh))


def test_run_writes_results_summary_and_blinded_sheet(tmp_path, fake_books):
    corpus = _corpus(tmp_path)
    out = evaluate.run(corpus, variant="baseline")
    assert out == tmp_path / "results"
    result = json.loads((out / "baseline" / "dev1.json").read_text())
    assert result["outcome"] == "review" and result["chapters"]["matched"] == 2
    assert result["spans"] == {"invented": 0, "omitted": 1}
    summary = (out / "summary.md").read_text()
    assert "Usable-book yield" in summary and "0/2" in summary
    assert "Paired comparison" not in summary

    evaluate.run(corpus, variant="rules")
    summary = (out / "summary.md").read_text()
    assert "Paired comparison" in summary and "2/2" in summary
    rows = _sheet(out)
    assert len(rows) == 4
    key = json.loads((out / "blinding-key.json").read_text())
    for row in rows:
        assert "baseline" not in row["epub"] and "rules" not in row["epub"]
        assert (out / row["epub"]).exists()
        assert row["blinded_label"] in key["books"][row["book_id"]].values()
    labels = key["books"]["dev1"]
    assert len(set(labels.values())) == 2

    # Filled review columns and blinded labels survive another run.
    rows[0]["human_repair_minutes"], rows[0]["reviewer_id"] = "12", "r1"
    with open(out / "scoring-sheet.csv", "w", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=evaluate.SHEET_COLUMNS)
        writer.writeheader()
        writer.writerows(rows)
    evaluate.run(corpus, variant="rules")
    again = {(r["book_id"], r["blinded_label"]): r for r in _sheet(out)}
    kept = again[(rows[0]["book_id"], rows[0]["blinded_label"])]
    assert (kept["human_repair_minutes"], kept["reviewer_id"]) == ("12", "r1")
    assert json.loads((out / "blinding-key.json").read_text())["books"]["dev1"] == labels


def test_run_refuses_unreleased_variant_and_unfrozen_holdout(tmp_path, fake_books):
    corpus = _corpus(tmp_path)
    with pytest.raises(UsageError):
        evaluate.run(corpus, variant="assisted")
    with pytest.raises(UsageError):
        evaluate.run(corpus, variant="rules", holdout=True)


def test_example_corpus_lists_every_fixture():
    from pathlib import Path

    from fixtures.build import FIXTURES

    root = Path(__file__).resolve().parents[1]
    corpus = evaluate.load_corpus(root / "corpus" / "corpus.example.toml")
    assert [b.id for b in corpus.books] == list(FIXTURES)
    assert all(b.split == "dev" for b in corpus.books)
    assert all(b.path.parent.resolve() == root / "tests" / "fixtures" / "out" for b in corpus.books)
    header = (root / "corpus" / "scoring-sheet.template.csv").read_text().strip().split(",")
    assert tuple(header) == evaluate.SHEET_COLUMNS
