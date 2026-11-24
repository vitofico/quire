import os
import stat
from pathlib import Path

import pytest
from lxml import etree

from quire_convert.epub.book import Book
from quire_convert.epub.calibre import run_calibre
from quire_convert.epub.validate import run_epubcheck, semantic_check
from quire_convert.models import StageError
from quire_convert.tools import CALIBRE_PIN, Toolchain, find_tools


def _script(path: Path, body: str) -> Path:
    path.write_text(f"#!/bin/sh\n{body}\n")
    path.chmod(path.stat().st_mode | stat.S_IEXEC)
    return path


def test_require_lists_every_missing_tool():
    with pytest.raises(StageError) as err:
        Toolchain(pdfinfo=Path("/bin/sh")).require("pdfinfo", "java", "epubcheck_jar")
    assert err.value.stage == "tools" and not err.value.retryable
    assert "java" in err.value.reason and "epubcheck_jar" in err.value.reason
    assert "pdfinfo" not in err.value.reason


def test_unusable_override_counts_as_missing(monkeypatch, tmp_path):
    monkeypatch.setenv("QC_EBOOK_CONVERT", str(tmp_path / "nope"))
    monkeypatch.setenv("QC_JAVA", str(_script(tmp_path / "java", "exit 1")))
    tools = find_tools()
    assert tools.ebook_convert is None and tools.java is None


def test_calibre_runs_isolated_and_logs_to_file(tmp_path):
    # A stand-in ebook-convert that records its environment and writes an empty EPUB.
    fake = _script(
        tmp_path / "ebook-convert",
        (
            # The version probe runs outside the sandbox; answer it without dumping anything.
            '[ "$1" = --version ] && { echo "ebook-convert (calibre 9.18.0)"; exit 0; }\n'
            'env > "$(pwd -P)/../env.txt"; echo noisy; : > "$2"'
        ),
    )
    pdf = tmp_path / "in.pdf"
    pdf.write_bytes(b"%PDF-1.4")
    run = run_calibre(pdf, tmp_path / "out" / "book.epub", tmp_path / "work", Toolchain(fake))
    assert run.epub_path.is_file()
    assert run.argv[1:3] == ["source.pdf", "out.epub"]
    assert "--epub-version=3" in run.argv
    assert run.log_path.read_text().strip() == "noisy"
    env = dict(
        line.split("=", 1) for line in (tmp_path / "work" / "env.txt").read_text().splitlines()
    )
    assert env["HOME"] != os.environ["HOME"] and "/calibre-" in env["HOME"]
    assert "/calibre-" in env["TMPDIR"]
    assert env["CALIBRE_CONFIG_DIRECTORY"] != os.environ.get("CALIBRE_CONFIG_DIRECTORY")
    assert not list((tmp_path / "work").glob("calibre-*"))  # sandbox removed


def test_calibre_failure_and_timeout(tmp_path):
    pdf = tmp_path / "in.pdf"
    pdf.write_bytes(b"%PDF-1.4")
    failing = Toolchain(_script(tmp_path / "fail", "exit 3"))
    with pytest.raises(StageError) as err:
        run_calibre(pdf, tmp_path / "a.epub", tmp_path / "w1", failing)
    assert err.value.stage == "calibre" and not err.value.retryable
    slow = Toolchain(_script(tmp_path / "slow", "sleep 30"))
    with pytest.raises(StageError) as err:
        run_calibre(pdf, tmp_path / "b.epub", tmp_path / "w2", slow, timeout=0.5)
    assert err.value.retryable


def _two_chapter_pdf(path: Path) -> Path:
    from reportlab.lib.pagesizes import A5
    from reportlab.pdfgen.canvas import Canvas

    c = Canvas(str(path), pagesize=A5, invariant=1)
    c.setTitle("Two Rivers")
    c.setAuthor("Ann Author")
    width, height = A5
    for page, heading in enumerate(["Chapter One", None, "Chapter Two"]):
        y = height - 80
        if heading:
            c.setFont("Helvetica-Bold", 26)
            c.drawString(50, y, heading)
            y -= 50
        c.setFont("Times-Roman", 11)
        for n in range(12):
            c.drawString(50, y, f"Line {page}.{n} of prose that fills the page with words.")
            y -= 15
        c.setFont("Times-Roman", 9)
        c.drawCentredString(width / 2, 30, str(page + 1))
        c.showPage()
    c.save()
    return path


@pytest.mark.tools
def test_calibre_baseline_round_trip(tmp_path):
    tools = find_tools()
    pdf = _two_chapter_pdf(tmp_path / "two.pdf")
    run = run_calibre(pdf, tmp_path / "raw.epub", tmp_path / "work", tools)
    assert run.version.startswith(CALIBRE_PIN)
    book = Book.load(run.epub_path)
    assert [e.title for e in book.toc] == ["Chapter One", "Chapter Two"]
    headings = [
        el.get("id")
        for doc in book.docs
        for el in doc.body.iter("{http://www.w3.org/1999/xhtml}h2")
        if "Chapter" in "".join(el.itertext())
    ]
    assert len(headings) == 2
    assert book.metadata.title == "Two Rivers"
    blocks = book.ensure_block_ids()
    assert any(b.text.startswith("Line 1.0") for b in blocks)
    book.save(tmp_path / "ours.epub", modified="2026-01-01T00:00:00Z")
    result = run_epubcheck(tmp_path / "ours.epub", tools)
    assert result.errors == [] and result.fatal == []
    assert semantic_check(tmp_path / "ours.epub") == []
    for doc in Book.load(tmp_path / "ours.epub").docs:
        assert doc.tree.findtext("{http://www.w3.org/1999/xhtml}head/{*}title") == "source.pdf"
        assert etree.QName(doc.tree).namespace == "http://www.w3.org/1999/xhtml"
