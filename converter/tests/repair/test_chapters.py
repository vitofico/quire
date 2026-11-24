import shutil
from pathlib import Path

import pytest
from fixtures.build import HARBOUR, RIVER, M, _canvas, _furniture, _heading, _paragraph
from lxml import etree

from quire_convert import pipeline
from quire_convert.align.align import align_book
from quire_convert.context import ConvertContext
from quire_convert.epub.book import Book, NavEntry, is_block
from quire_convert.epub.calibre import run_calibre
from quire_convert.epub.inline import merge, text_of
from quire_convert.epub.package import XHTML_NS, local_name, resolve_href
from quire_convert.epub.validate import run_epubcheck, semantic_check
from quire_convert.models import Outcome, Severity
from quire_convert.repair import chapters
from quire_convert.report import is_safe_evidence
from quire_convert.source.preflight import inspect_pdf
from quire_convert.tools import find_tools

H = f"{{{XHTML_NS}}}"
BOLD_SENTENCE = "She never crossed at night again."
PULL_QUOTE = "“The river keeps its own ledger”"


# --------------------------------------------------------------------------- helpers


def _near_match_pages(c, chapters_on: bool) -> None:
    """Two pages of body text, one with a bold body sentence, one with a large pull quote;
    with ``chapters_on`` each page opens with a 22pt bold chapter heading."""
    for i, (title, texts) in enumerate([("The Ferry", RIVER), ("The Quay", HARBOUR[:3])]):
        _furniture(c, str(i + 1), "Small Hours")
        if chapters_on:
            _heading(c, title)
        y = _paragraph(c, texts[0], 462)
        if i == 0:
            c.setFont("Times-Bold", 12)
            c.drawString(M, y, BOLD_SENTENCE)
            y -= 40
        else:
            c.setFont("Times-Italic", 16)
            c.drawString(M + 20, y - 6, PULL_QUOTE)
            y -= 50
        for text in texts[1:]:
            y = _paragraph(c, text, y)
        c.showPage()


def near_match_pdf(out: Path, chapters_on: bool) -> Path:
    name = "near-match" if chapters_on else "no-structure"
    c = _canvas(out, name)
    _near_match_pages(c, chapters_on)
    if not chapters_on:  # a third plain page: a longer book still without any structure
        _furniture(c, "3", "Small Hours")
        _paragraph(c, HARBOUR[3], 504)
        c.showPage()
    c.save()
    return out / f"{name}.pdf"


@pytest.fixture(scope="session")
def converted(fixture_pdfs, tmp_path_factory):
    """``get(name) -> ConvertContext`` on a fresh copy of the fixture's Calibre output,
    aligned and ready for a repair; inventory and Calibre run are built once."""
    tools = find_tools()
    if tools.ebook_convert is None or shutil.which("pdftotext") is None:
        pytest.skip("calibre or poppler not installed")
    extra = tmp_path_factory.mktemp("chapters-pdfs")
    pdfs = {name: pdf for name, (pdf, _) in fixture_pdfs.items()}
    pdfs["near-match"] = near_match_pdf(extra, True)
    pdfs["no-structure"] = near_match_pdf(extra, False)
    cache = {}

    def get(name: str) -> ConvertContext:
        if name not in cache:
            work = tmp_path_factory.mktemp(f"chapters-{name}")
            inv = inspect_pdf(pdfs[name], work / "source", toolchain=tools)
            run = run_calibre(pdfs[name], work / "calibre.epub", work / "calibre", tools)
            cache[name] = (inv, run.epub_path, work)
        inv, epub, work = cache[name]
        ctx = ConvertContext(
            pdf_path=pdfs[name], workdir=work, source=inv, book=Book.load(epub), toolchain=tools
        )
        align_book(ctx)
        return ctx

    return get


def flat(entries: list[NavEntry]) -> list[NavEntry]:
    out = []
    for entry in entries:
        out.append(entry)
        out.extend(flat(entry.children))
    return out


def headings(book: Book) -> list[etree._Element]:
    return [
        el
        for doc in book.docs
        for el in doc.body.iter(etree.Element)
        if local_name(el) in chapters.HEADINGS
    ]


def first_block(book: Book, href: str) -> etree._Element:
    doc = book.doc(href.partition("#")[0])
    return next(el for el in doc.body.iter(etree.Element) if is_block(el) and text_of(el))


def assert_lands(ctx: ConvertContext, entry: NavEntry, title: str, page: int) -> None:
    """The entry shows ``title`` on an h1 that aligns to source ``page`` and opens its doc."""
    _, el = ctx.book.element(entry.href)
    assert local_name(el) == "h1"
    assert text_of(el) == title == entry.title
    assert ctx.alignment.block_pages(el.get("id")) == [page]
    assert first_block(ctx.book, entry.href) is el


def assert_safe(ctx: ConvertContext) -> None:
    assert all(is_safe_evidence(i.evidence) for i in ctx.issues)
    assert all(is_safe_evidence(e) for r in ctx.repairs for e in r.evidence)


def codes(ctx: ConvertContext) -> list[str]:
    return [i.code for i in ctx.issues]


# --------------------------------------------------------------------------- pure helpers


def test_parse_entry_reads_titles_and_trailing_page_numbers():
    assert chapters.parse_entry("Chapter One: The River ..... 2") == (
        "Chapter One: The River",
        "2",
    )
    assert chapters.parse_entry("Preface . . . . vii") == ("Preface", "vii")
    assert chapters.parse_entry("The Tide 13") == ("The Tide", "13")
    assert chapters.parse_entry("bridge, 3") is None  # an index line
    assert chapters.parse_entry("Part I") is None  # a roman number needs a leader
    assert chapters.parse_entry("1847") is None


def test_same_title_drops_numbering_on_one_side_only():
    n = chapters.norms_of
    assert chapters.same_title(n("Chapter 3: The River"), n("The River"))
    assert chapters.same_title(n("INTERLUDE"), n("Interlude"))
    assert not chapters.same_title(n("Chapter One: The Sea"), n("Chapter Two: The Sea"))
    assert not chapters.same_title(n("The River"), n("The Bridge"))


def test_wrap_links_a_range_across_inline_markup():
    p = etree.fromstring(f'<p xmlns="{XHTML_NS}"><b>Chapter One</b> ..... 2</p>')
    assert chapters._wrap(p, 0, 11, "c1.html#h")
    a = p.find(H + "a")
    assert a.get("href") == "c1.html#h" and text_of(a) == "Chapter One"
    assert a.find(H + "b") is not None
    assert text_of(p) == "Chapter One ..... 2"
    assert not chapters._wrap(p, 0, 11, "elsewhere.html")  # never nests links


# --------------------------------------------------------------------------- fixtures


@pytest.mark.tools
def test_bridge_nav_lands_on_each_heading_and_contents_entries_link(converted, fixture_pdfs):
    ctx = converted("bridge")
    before = [(e.title, e.href) for e in flat(ctx.book.toc)]
    assert [t for t, _ in before] == ["Chapter One: The River", "Chapter Two: The Bridge"]
    contents = ctx.book.docs[0]
    contents_text = [text_of(el) for el in contents.body.iter(etree.Element) if is_block(el)]

    chapters.apply(ctx)

    entries = flat(ctx.book.toc)
    titles = [e.title for e in entries]
    assert titles == ["Contents", "Chapter One: The River", "Chapter Two: The Bridge", "Index"]
    _, labels = fixture_pdfs["bridge"]
    for entry, chapter in zip(entries[1:3], labels["chapters"], strict=True):
        assert_lands(ctx, entry, chapter["title"], chapter["page"])
    assert_lands(ctx, entries[3], "Index", 3)
    # The contents page is linked in place: same text, each entry title now an <a>.
    assert [text_of(el) for el in contents.body.iter(etree.Element) if is_block(el)] == (
        contents_text
    )
    anchors = contents.body.iter(H + "a")
    links = [(text_of(a), resolve_href(contents.href, a.get("href"))) for a in anchors]
    assert links == [(e.title, e.href) for e in entries[1:]]
    assert {m.type: m.href for m in ctx.book.landmarks} == {
        "toc": entries[0].href,
        "bodymatter": entries[1].href,
        "index": entries[3].href,
    }
    assert not [c for c in codes(ctx) if c.startswith("nav.")]
    assert_safe(ctx)


@pytest.mark.tools
def test_front_matter_bookmark_trap_is_rejected(converted, fixture_pdfs):
    ctx = converted("front-matter-roman")
    _, labels = fixture_pdfs["front-matter-roman"]
    trap = labels["bookmark_traps"][0]

    chapters.apply(ctx)

    rejected = [i for i in ctx.issues if i.code == "nav.unverified_bookmark"]
    assert len(rejected) == 1
    assert rejected[0].severity is Severity.MINOR and rejected[0].page == trap["bookmark_page"]
    assert f"heading_page={trap['chapter_page']}" in rejected[0].evidence
    entries = flat(ctx.book.toc)
    assert [e.title for e in entries] == ["Contents", "The Lamp", "The Tide"]
    for entry, chapter in zip(entries[1:], labels["chapters"], strict=True):
        assert_lands(ctx, entry, chapter["title"], chapter["page"])
    assert "nav.missing_chapter" not in codes(ctx)
    assert_safe(ctx)


@pytest.mark.tools
def test_duplicate_titles_resolved_by_sequence_and_page(converted, fixture_pdfs):
    ctx = converted("duplicate-chapter-names")
    _, labels = fixture_pdfs["duplicate-chapter-names"]

    chapters.apply(ctx)

    entries = flat(ctx.book.toc)
    assert [e.title for e in entries] == [c["title"] for c in labels["chapters"]]
    for entry, chapter in zip(entries, labels["chapters"], strict=True):
        assert_lands(ctx, entry, chapter["title"], chapter["page"])
    assert len({e.href.partition("#")[0] for e in entries}) == 4
    # The running header shares the third chapter's title; it is not an extra chapter.
    assert [text_of(h) for h in headings(ctx.book)].count("The Sounding Line") == 1
    assert ctx.issues == []
    assert_safe(ctx)


@pytest.mark.tools
def test_heading_merged_into_a_paragraph_is_split_out(converted):
    ctx = converted("bridge")
    _, h2 = ctx.book.element("index_split_004.html#calibre_toc_3")
    spacer = h2.getnext()
    body = spacer.getnext()
    spacer.getparent().remove(spacer)
    h2.tag = H + "p"
    merge(h2, body, sep=" ")  # Calibre often runs a heading into the page's first paragraph
    align_book(ctx)

    chapters.apply(ctx)

    entry = next(e for e in flat(ctx.book.toc) if e.title.startswith("Chapter Two"))
    assert_lands(ctx, entry, "Chapter Two: The Bridge", 2)
    _, el = ctx.book.element(entry.href)
    assert text_of(el.getnext()).startswith("At first light the bridge")
    assert el.find(f".//{H}b") is not None  # inline markup kept


@pytest.mark.tools
def test_bold_sentence_and_pull_quote_are_not_promoted(converted):
    ctx = converted("near-match")

    chapters.apply(ctx)

    assert [e.title for e in flat(ctx.book.toc)] == ["The Ferry", "The Quay"]
    texts = [text_of(h) for h in headings(ctx.book)]
    assert texts == ["The Ferry", "The Quay"]
    assert BOLD_SENTENCE not in texts and PULL_QUOTE not in texts
    assert_safe(ctx)


@pytest.mark.tools
def test_no_structure_abstains_and_flags(converted):
    ctx = converted("no-structure")
    toc = [(e.title, e.href) for e in flat(ctx.book.toc)]
    before = {id(h) for h in headings(ctx.book)}

    chapters.apply(ctx)

    assert {id(h) for h in headings(ctx.book)} <= before
    assert [(e.title, e.href) for e in flat(ctx.book.toc)] == toc
    assert not [r for r in ctx.repairs if r.action in ("promote_heading", "split_doc")]
    flagged = [i for i in ctx.issues if i.code == "nav.unresolved_structure"]
    assert len(flagged) == 1 and flagged[0].severity is Severity.MAJOR
    assert_safe(ctx)


@pytest.mark.tools
@pytest.mark.parametrize("name", ["bridge", "front-matter-roman", "duplicate-chapter-names"])
def test_epubcheck_stays_clean_after_repair(converted, name, tmp_path):
    ctx = converted(name)
    chapters.apply(ctx)
    out = tmp_path / "book.epub"
    ctx.book.save(out, modified="2026-01-01T00:00:00Z")
    result = run_epubcheck(out, ctx.toolchain)
    assert result.ok and result.errors == [] and result.fatal == []
    assert [i.code for i in semantic_check(out)] == []


@pytest.mark.tools
def test_pipeline_with_chapters_only_passes_epubcheck(fixture_pdfs, tmp_path, monkeypatch):
    if find_tools().epubcheck_jar is None:
        pytest.skip("EPUBCheck not installed")
    monkeypatch.setattr("quire_convert.repair.REPAIRS", [chapters.apply])
    pdf, _ = fixture_pdfs["bridge"]
    result = pipeline.convert(pdf, job_root=tmp_path / "jobs", variant="rules")
    assert result.outcome is not Outcome.FAILED
    assert {r.action for r in result.report.repairs} >= {"promote_heading", "rebuild_toc"}
