import zipfile

import pytest

from quire_convert.epub.book import Book
from quire_convert.epub.validate import run_epubcheck, semantic_check
from quire_convert.models import Severity

MODIFIED = "2025-02-03T04:05:06Z"
CH1 = "OEBPS/Text/ch1.xhtml"


def _codes(path) -> set[str]:
    return {issue.code for issue in semantic_check(path)}


def _anchor(book, anchor):
    return book.element(anchor)[1]


def test_clean_book_has_no_issues(sample_epub, tmp_path):
    Book.load(sample_epub).save(tmp_path / "a.epub", modified=MODIFIED)
    assert semantic_check(tmp_path / "a.epub") == []


def test_broken_fragment_and_nav_target(sample_epub, tmp_path):
    book = Book.load(sample_epub)
    link = _anchor(book, f"{CH1}#s2").getnext()[0]
    link.set("href", "ch2.xhtml#nowhere")
    book.toc[1].href = "OEBPS/Text/ch2.xhtml#gone"
    book.save(tmp_path / "a.epub", modified=MODIFIED)
    issues = semantic_check(tmp_path / "a.epub")
    assert {i.code for i in issues} == {"epub.missing_target", "nav.broken_target"}
    nav = [i for i in issues if i.code == "nav.broken_target"]
    assert nav and all(i.severity is Severity.CRITICAL for i in nav)


def test_blank_image(sample_epub, tmp_path, make_png):
    book = Book.load(sample_epub)
    book.add_asset("OEBPS/Images/blank.png", make_png(blank=True), "image/png")
    book.save(tmp_path / "a.epub", modified=MODIFIED)
    assert _codes(tmp_path / "a.epub") == {"asset.blank"}


def test_undecodable_image(sample_epub, tmp_path):
    book = Book.load(sample_epub)
    book.assets["OEBPS/Images/fig.png"].data = b"not a png"
    book.save(tmp_path / "a.epub", modified=MODIFIED)
    assert _codes(tmp_path / "a.epub") == {"asset.undecodable"}


def test_note_without_working_backlink(sample_epub, tmp_path):
    book = Book.load(sample_epub)
    note = _anchor(book, f"{CH1}#n1")
    note[0][0].set("href", "#gone")
    book.save(tmp_path / "a.epub", modified=MODIFIED)
    issues = semantic_check(tmp_path / "a.epub")
    notes = [i for i in issues if i.code.startswith("note.")]
    assert [(i.code, i.severity) for i in notes] == [("note.no_return", Severity.CRITICAL)]
    assert notes[0].output_anchor == f"{CH1}#r1"
    # the backlink is also an ordinary broken fragment link
    assert {i.code for i in issues} == {"note.no_return", "epub.missing_target"}


def test_broken_noteref_and_missing_member(sample_epub, tmp_path):
    book = Book.load(sample_epub)
    _anchor(book, f"{CH1}#r1").set("href", "#nonote")
    book.save(tmp_path / "a.epub", modified=MODIFIED)
    with (
        zipfile.ZipFile(tmp_path / "a.epub") as src,
        zipfile.ZipFile(tmp_path / "b.epub", "w") as dst,
    ):
        for info in src.infolist():
            if info.filename != "OEBPS/Images/fig.png":
                dst.writestr(info, src.read(info))
    assert _codes(tmp_path / "b.epub") == {"note.broken_ref", "asset.missing"}


def test_mimetype_must_come_first(tmp_path):
    with zipfile.ZipFile(tmp_path / "bad.epub", "w") as zf:
        zf.writestr("META-INF/container.xml", "<container/>")
        zf.writestr("mimetype", "application/epub+zip")
    assert _codes(tmp_path / "bad.epub") == {"epub.mimetype"}


@pytest.mark.tools
def test_epubcheck_accepts_saved_output(sample_epub, tmp_path, toolchain):
    book = Book.load(sample_epub)
    book.split_doc(CH1, "s2")
    book.ensure_block_ids()
    book.save(tmp_path / "a.epub", modified=MODIFIED)
    result = run_epubcheck(tmp_path / "a.epub", toolchain)
    assert result.errors == [] and result.fatal == []
    assert result.ok and result.version == "5.4.0"
