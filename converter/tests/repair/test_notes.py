import shutil
from pathlib import Path

import pytest
from fixtures.build import BODY, HARBOUR, SIZE, H, M, W, _paragraph
from lxml import etree
from reportlab.pdfgen.canvas import Canvas

from quire_convert.align.align import align_book
from quire_convert.align.coverage import account
from quire_convert.context import ConvertContext
from quire_convert.epub.book import Book
from quire_convert.epub.calibre import run_calibre
from quire_convert.epub.inline import text_of
from quire_convert.epub.package import EPUB_NS, XHTML_NS
from quire_convert.epub.validate import run_epubcheck, semantic_check
from quire_convert.repair import notes
from quire_convert.report import is_safe_evidence
from quire_convert.source.preflight import inspect_pdf
from quire_convert.tools import find_tools

pytestmark = pytest.mark.tools

X = f"{{{XHTML_NS}}}"
TYPE = f"{{{EPUB_NS}}}type"


@pytest.fixture(scope="module")
def tools():
    found = find_tools()
    if found.ebook_convert is None or shutil.which("pdftotext") is None:
        pytest.skip("calibre or poppler not installed")
    return found


def aligned(pdf: Path, work: Path, tools) -> ConvertContext:
    source = inspect_pdf(pdf, work / "source", toolchain=tools)
    run = run_calibre(pdf, work / "calibre.epub", work / "calibre", tools)
    ctx = ConvertContext(
        pdf_path=pdf,
        workdir=work,
        source=source,
        book=Book.load(run.epub_path),
        toolchain=tools,
    )
    align_book(ctx)
    return ctx


def codes(ctx: ConvertContext) -> list[str]:
    return [i.code for i in ctx.issues]


def by_type(ctx: ConvertContext, kind: str) -> list[tuple[str, etree._Element]]:
    return [
        (doc.href, el)
        for doc in ctx.book.docs
        for el in doc.body.iter()
        if kind in (el.get(TYPE) or "").split()
    ]


def full_text(el: etree._Element) -> str:
    return " ".join("".join(el.itertext()).split())


def book_text(ctx: ConvertContext) -> str:
    return " ".join(full_text(d.body) for d in ctx.book.docs)


def assert_valid(ctx: ConvertContext, tmp_path: Path, tools) -> None:
    for r in ctx.repairs:
        assert all(is_safe_evidence(e) for e in r.evidence), r.evidence
    for i in ctx.issues:
        assert is_safe_evidence(i.evidence), i.evidence
    out = tmp_path / "out.epub"
    ctx.book.save(out, modified="2020-01-01T00:00:00Z")
    result = run_epubcheck(out, tools)
    assert result.ok and not result.fatal and not result.errors, result.errors
    assert semantic_check(out) == []


def test_repeated_note_numbers_linked_both_ways(fixture_pdfs, tmp_path, tools):
    pdf, labels = fixture_pdfs["repeated-note-numbers"]
    ctx = aligned(pdf, tmp_path, tools)
    notes.apply(ctx)
    account(ctx)

    refs, asides = by_type(ctx, "noteref"), by_type(ctx, "footnote")
    assert len(refs) == len(asides) == len(labels["notes"]) == 3
    ids = [el.get("id") for _, el in refs + asides]
    assert len(set(ids)) == len(ids)

    asides_by_id = {el.get("id"): (href, el) for href, el in asides}
    seen = []
    for href, ref in refs:
        assert ref.get("role") == "doc-noteref" and ref.tag == X + "a"
        target_href, aside = asides_by_id[ref.get("href").lstrip("#")]
        assert target_href == href and aside.tag == X + "aside"
        assert aside.get("role") == "doc-footnote"
        [back] = [a for a in aside.iter(X + "a") if a.get("role") == "doc-backlink"]
        assert back.get("href") == "#" + ref.get("id")
        assert text_of(back).strip().rstrip(".") == text_of(ref).strip()
        seen.append((text_of(ref).strip(), full_text(aside)))

    expected = [(n["marker"], f"{n['marker']}. {n['text_start']}") for n in labels["notes"]]
    assert seen == expected
    text = book_text(ctx)
    for note in labels["notes"]:
        assert text.count(note["text_start"]) == 1

    assert not [c for c in codes(ctx) if c.startswith(("note.", "coverage."))]
    assert [r.action for r in ctx.repairs] == ["link_note"] * 3
    assert_valid(ctx, tmp_path, tools)


def raised(c: Canvas, x: float, y: float, parts: list[tuple[str, int]]) -> None:
    """Draw ``parts`` as one line; a non-zero rise sets that part as a small raised glyph."""
    t = c.beginText(x, y)
    for text, rise in parts:
        t.setFont(BODY, 8 if rise else SIZE)
        t.setRise(rise)
        t.textOut(text)
    t.setRise(0)
    c.drawText(t)


def page_with_notes(path: Path, lines: list[list[tuple[str, int]]], bodies: list[str]) -> Path:
    c = Canvas(str(path), pagesize=(W, H), invariant=1)
    y = _paragraph(c, HARBOUR[0], H - 92)
    for parts in lines:
        raised(c, M, y, parts)
        y -= 22
    c.setLineWidth(0.6)
    c.line(M, 112, M + 90, 112)
    c.setFont(BODY, 10)
    for k, body in enumerate(bodies):
        c.drawString(M, 94 - k * 16, body)
    c.showPage()
    c.save()
    return path


def test_superscripts_that_are_not_markers(tmp_path, tools):
    pdf = page_with_notes(
        tmp_path / "near.pdf",
        [
            [("The ledger dates from the winter of the flood.", 0), ("1", 5)],
            [("Its margin gives the rule E = mc", 0), ("2", 5), (" in pencil.", 0)],
            [("The deck covered forty m", 0), ("2", 5), (" of planking.", 0)],
            [("A sample was sent for ", 0), ("14", 5), ("C dating last spring.", 0)],
            [("The water held Fe", 0), ("3", 5), (" from the old anchors.", 0)],
            [("She came 1", 0), ("st", 5), (" in the race and 2", 0), ("º", 5), (" at sea.", 0)],
        ],
        ["1. The ledger is kept in the harbour office."],
    )
    ctx = aligned(pdf, tmp_path, tools)
    notes.apply(ctx)
    account(ctx)

    [(_, ref)] = by_type(ctx, "noteref")
    assert text_of(ref).strip() == "1"
    [(_, aside)] = by_type(ctx, "footnote")
    assert full_text(aside) == "1. The ledger is kept in the harbour office."
    assert not [c for c in codes(ctx) if c.startswith(("note.", "coverage."))]
    assert_valid(ctx, tmp_path, tools)


def test_marker_with_two_bodies_abstains(tmp_path, tools):
    pdf = page_with_notes(
        tmp_path / "two.pdf",
        [[("The ledger dates from the winter of the flood.", 0), ("1", 5)]],
        ["1. The ledger is kept in the harbour office.", "1. The flood came in the spring."],
    )
    ctx = aligned(pdf, tmp_path, tools)
    before = [etree.tostring(d.tree) for d in ctx.book.docs]
    notes.apply(ctx)

    assert [etree.tostring(d.tree) for d in ctx.book.docs] == before
    assert ctx.repairs == []
    [issue] = ctx.issues
    assert issue.code == "note.ambiguous" and issue.severity.value == "major"
    assert is_safe_evidence(issue.evidence)
