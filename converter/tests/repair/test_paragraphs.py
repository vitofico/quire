import copy
import shutil

import pytest
from lxml import etree
from reportlab.pdfgen.canvas import Canvas

from quire_convert import pipeline
from quire_convert.align.align import refresh
from quire_convert.align.coverage import account
from quire_convert.context import ConvertContext
from quire_convert.epub import inline
from quire_convert.epub.book import Book
from quire_convert.epub.calibre import run_calibre
from quire_convert.epub.validate import run_epubcheck, semantic_check
from quire_convert.models import Outcome, Severity
from quire_convert.repair import characters, paragraphs
from quire_convert.report import is_safe_evidence
from quire_convert.source.preflight import inspect_pdf
from quire_convert.tools import find_tools

pytestmark = pytest.mark.tools

XHTML = "{http://www.w3.org/1999/xhtml}"
W, H, M = 420, 600, 44


def layouts_pdf(path):
    """One page of an indented book: a hyphen join, a line-end compound, a paragraph that
    opens on a quotation, an ornament, verse, a block quotation, a scene break shown only
    by space, and a paragraph whose short line cannot tell whether a new one starts."""
    c = Canvas(str(path), pagesize=(W, H), invariant=1)
    c.setFont("Times-Roman", 12)
    y = 540

    def line(text, dx=0):
        nonlocal y
        c.drawString(M + dx, y, text)
        y -= 16

    line("The first paragraph opens with an indent and, as is well known, runs", 18)
    line("on for a while before it stops short.")
    line("A second paragraph follows with the same indent and no extra space", 18)
    line("above it, and it carries a hyphenated word that conti-")
    line("nues here, and a compound that is well-")
    line("known to everyone who lives in the valley.")
    line("“Come inside,” she said, and the third paragraph ends there.", 18)
    line("A fourth paragraph has a line that ends early.", 18)
    line("Then the text runs on at the margin without any indent at all")
    line("until it reaches the end of the paragraph.")
    y -= 12
    c.drawCentredString(W / 2, y, "* * *")
    y -= 28
    for k, text in enumerate(
        [
            "Under the bridge the water ran",
            "over stones as old as rain,",
            "and nobody remembered when",
            "the lamps were lit again.",
        ]
    ):
        line(text, 60 + 12 * (k % 2))
    y -= 12
    line("This quotation is indented on both sides of the measure and runs", 30)
    line("over two lines of the narrower setting.", 30)
    y -= 12
    line("After the quotation the text resumes with a new paragraph.", 18)
    y -= 40
    line("After a wide gap a new scene begins with a fresh paragraph.", 18)
    c.showPage()
    c.save()


@pytest.fixture(scope="session")
def converted(fixture_pdfs, tmp_path_factory):
    """``get(name) -> ConvertContext`` on fresh Calibre output; the inventory and the
    Calibre run are made once per name ("layouts" is built here, the rest are fixtures)."""
    tools = find_tools()
    if tools.ebook_convert is None or shutil.which("pdftotext") is None:
        pytest.skip("calibre or poppler not installed")
    cache = {}

    def get(name: str) -> ConvertContext:
        if name not in cache:
            work = tmp_path_factory.mktemp(f"paragraphs-{name}")
            if name == "layouts":
                pdf = work / "layouts.pdf"
                layouts_pdf(pdf)
            else:
                pdf = fixture_pdfs[name][0]
            inv = inspect_pdf(pdf, work / "source", toolchain=tools)
            run = run_calibre(pdf, work / "calibre.epub", work / "calibre", tools)
            cache[name] = (pdf, work, inv, run.epub_path)
        pdf, work, inv, epub = cache[name]
        ctx = ConvertContext(
            pdf_path=pdf,
            workdir=work,
            source=copy.deepcopy(inv),
            book=Book.load(epub),
            toolchain=tools,
        )
        refresh(ctx)
        return ctx

    return get


def texts(ctx) -> list[str]:
    return [b.text for b in ctx.blocks if b.text]


def block(ctx, start: str):
    found = [b for b in ctx.blocks if b.text.startswith(start)]
    assert len(found) == 1, start
    return found[0]


def element(ctx, b):
    return ctx.book.element(f"{b.href}#{b.id}")[1]


def ids(ctx) -> set[str]:
    return {
        f"{doc.href}#{ident}"
        for doc in ctx.book.docs
        for x in doc.tree.iter(etree.Element)
        if (ident := x.get("id"))
    }


def assert_safe(ctx):
    for repair in ctx.repairs:
        assert all(is_safe_evidence(e) for e in repair.evidence), repair.evidence
    for issue in ctx.issues:
        assert is_safe_evidence(issue.evidence), issue.evidence


def actions(ctx, action: str):
    return [r for r in ctx.repairs if r.family == "paragraphs" and r.action == action]


# --------------------------------------------------------------------------- positive


def test_paragraph_across_pages_is_joined(converted):
    ctx = converted("paragraph-spanning-pages")
    page_ids = {i for i in ids(ctx) if "#page_" in i}
    paragraphs.apply(ctx)
    joined = [t for t in texts(ctx) if "conti" in t]
    assert len(joined) == 1
    assert "the well-known turning" in joined[0]
    assert "the letter and continued along the path" in joined[0]
    join = actions(ctx, "join_hyphenated")
    assert len(join) == 1 and "rule=never_mid_line" in join[0].evidence
    assert len(actions(ctx, "merge_paragraph")) == 1
    # Running headers and folios are gone; every page anchor still resolves.
    assert not [t for t in texts(ctx) if t in ("The Inland Road", "1", "2")]
    assert len(actions(ctx, "remove_furniture")) == 4
    assert {i.rsplit("#", 1)[1] for i in page_ids} <= {i.rsplit("#", 1)[1] for i in ids(ctx)}
    for _, href in ctx.book.page_list:
        assert ctx.book.element(href)
    assert not [i for i in ctx.issues if i.code.startswith("paragraphs.")]
    account(ctx)
    assert not [
        i for i in ctx.issues if i.code in ("coverage.missing_text", "coverage.invented_text")
    ]
    assert_safe(ctx)


def test_page_run_together_is_split_at_paragraph_starts(converted):
    ctx = converted("paragraph-spanning-pages")
    first = block(ctx, "From the window")
    el = element(ctx, first)
    for start in ("Her father", "Before the ferry"):
        inline.merge(el, element(ctx, block(ctx, start)), " ")
    refresh(ctx)
    assert block(ctx, "From the window").text.endswith("against a wall.")
    paragraphs.apply(ctx)
    for start, end in [
        ("From the window", "deep water."),
        ("Her father", "salt and flour."),
        ("Before the ferry", "against a wall."),
    ]:
        assert block(ctx, start).text.endswith(end)
    assert_safe(ctx)


def test_verse_ornament_quotation_and_scene_break(converted):
    ctx = converted("layouts")
    paragraphs.apply(ctx)
    body = texts(ctx)
    verse = ["Under the bridge", "over stones", "and nobody", "the lamps"]
    for k, start in enumerate(verse):
        holder = [t for t in body if start in t]
        assert len(holder) == 1
        assert not any(other in holder[0] for other in verse[k + 1 :])
    assert "* * *" in body
    assert block(ctx, "This quotation is indented").text.endswith("narrower setting.")
    assert block(ctx, "After the quotation")
    scene = element(ctx, block(ctx, "After a wide gap"))
    hr = scene.getprevious()
    assert hr.tag == f"{XHTML}hr" and hr.get("class") == "qc-scene-break"
    assert hr.get("data-qc-generated") == ""
    assert len(actions(ctx, "insert_scene_break")) == 1
    assert_safe(ctx)


def test_running_header_removed_but_kept_at_chapter_opening(converted):
    ctx = converted("duplicate-chapter-names")
    # Calibre dropped the running headers here; put them back the way it usually keeps
    # them, as the first block of each page carrying the page anchor.
    for n in range(1, 5):
        doc, heading = ctx.book.element(f"{ctx.blocks[0].href}#page_{n}")
        header = heading.makeelement(f"{XHTML}p", {"id": f"page_{n}"})
        header.text = "The Sounding Line"
        del heading.attrib["id"]
        heading.addprevious(header)
    refresh(ctx)
    paragraphs.apply(ctx)
    removed = actions(ctx, "remove_furniture")
    assert [r.before for r in removed] == ["The Sounding Line"] * 4
    body = texts(ctx)
    assert body.count("The Sounding Line") == 1
    assert body.count("Interlude") == 2 and "The Crossing" in body
    for n in range(1, 5):
        assert ctx.book.element(f"{ctx.blocks[0].href}#page_{n}")
    assert_safe(ctx)


def test_chapter_title_equal_to_running_header_survives_raw_output(converted):
    ctx = converted("duplicate-chapter-names")
    before = texts(ctx)
    paragraphs.apply(ctx)
    assert texts(ctx) == before
    assert not actions(ctx, "remove_furniture")


def test_rotated_crop_slug_removed(converted):
    ctx = converted("rotated-crop")
    account(ctx)
    assert [i for i in ctx.issues if i.code == "coverage.invented_text"]
    ctx = converted("rotated-crop")
    paragraphs.apply(ctx)
    slug = actions(ctx, "remove_out_of_crop")
    assert len(slug) == 1 and "box=media_box" in slug[0].evidence
    account(ctx)
    assert not [i for i in ctx.issues if i.code == "coverage.invented_text"]
    assert_safe(ctx)


# --------------------------------------------------------------------------- near matches


def test_quote_opening_and_line_end_compound_are_kept(converted):
    ctx = converted("layouts")
    paragraphs.apply(ctx)
    assert block(ctx, "“Come inside,”").text.endswith("ends there.")
    second = block(ctx, "A second paragraph")
    assert "a compound that is well-known to everyone" in second.text
    assert "hyphenated word that continues here" in second.text
    # Calibre 9.19 closes up "conti-nues" on one page itself, so there is nothing left to
    # join; the page-break case keeps testing the join.
    assert not actions(ctx, "join_hyphenated")
    assert not [t for t in texts(ctx) if "wellknown" in t]


# --------------------------------------------------------------------------- abstention


def test_ambiguous_boundary_left_as_converted_and_flagged(converted):
    ctx = converted("layouts")
    original = block(ctx, "A fourth paragraph").text
    paragraphs.apply(ctx)
    assert block(ctx, "A fourth paragraph").text == original
    flagged = [i for i in ctx.issues if i.code == "paragraphs.uncertain_split"]
    assert len(flagged) == 1
    issue = flagged[0]
    assert issue.severity is Severity.MINOR and issue.page == 0 and issue.region is not None
    assert issue.output_anchor.endswith(f"#{block(ctx, 'A fourth paragraph').id}")
    assert "rule=short_unindented" in issue.evidence
    assert_safe(ctx)


# --------------------------------------------------------------------------- packaging


@pytest.mark.parametrize("name", ["paragraph-spanning-pages", "rotated-crop", "layouts"])
def test_epubcheck_stays_clean(converted, name, tmp_path):
    ctx = converted(name)
    if ctx.toolchain.epubcheck_jar is None:
        pytest.skip("EPUBCheck not installed")
    paragraphs.apply(ctx)
    characters.apply(ctx)
    out = tmp_path / "book.epub"
    ctx.book.save(out, modified="2026-01-01T00:00:00Z")
    result = run_epubcheck(out, ctx.toolchain)
    assert result.ok and result.errors == [] and result.fatal == []
    assert [i.code for i in semantic_check(out)] == []


def test_pipeline_with_both_families(fixture_pdfs, tmp_path, monkeypatch):
    if find_tools().epubcheck_jar is None:
        pytest.skip("EPUBCheck not installed")
    monkeypatch.setattr("quire_convert.repair.REPAIRS", [paragraphs.apply, characters.apply])
    pdf, _ = fixture_pdfs["paragraph-spanning-pages"]
    result = pipeline.convert(pdf, job_root=tmp_path / "jobs", variant="rules")
    assert result.outcome is not Outcome.FAILED
    assert {"merge_paragraph", "join_hyphenated"} <= {r.action for r in result.report.repairs}
    checked = run_epubcheck(result.epub_path, find_tools())
    assert checked.ok and checked.errors == []
