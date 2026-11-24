import zipfile

import pytest
from lxml import etree
from pages_support import (
    EPUB_TYPE,
    XHTML,
    body_text,
    calibre_loader,
    codes,
    context,
    epub,
    page_anchors,
    source,
)

from quire_convert.align.align import refresh
from quire_convert.epub import inline
from quire_convert.epub.validate import run_epubcheck, semantic_check
from quire_convert.models import Severity
from quire_convert.repair import pages


@pytest.fixture(scope="session")
def calibre_book(tmp_path_factory):
    return calibre_loader(tmp_path_factory)


def book_of(tmp_path, specs):
    """A synthetic book: one paragraph of unique prose per page with text."""
    for p, spec in enumerate(specs):
        spec.setdefault("lines", [body_text(p)])
    paras = "".join(f"<p>{' '.join(s['lines'])}</p>" for s in specs if s["lines"])
    return context(source(specs), epub(tmp_path / "b.epub", [paras]), tmp_path)


def verified(ctx) -> dict[int, str]:
    return pages.labels(ctx).verified


# --------------------------------------------------------------------------- label runs


def test_folio_run_leaves_an_uncounted_plate_unlabelled(tmp_path):
    ctx = book_of(tmp_path, [{"folio": "1"}, {"folio": "2"}, {}, {"folio": "3"}, {"folio": "4"}])
    assert verified(ctx) == {0: "1", 1: "2", 3: "3", 4: "4"}
    pages.apply(ctx)
    assert [label for label, _ in ctx.book.page_list] == ["1", "2", "3", "4"]
    plate = page_anchors(ctx.book)["qc-page-2"]
    assert plate.get(EPUB_TYPE) is None and plate.get("aria-label") is None


def test_counted_opening_page_takes_its_label_from_the_run(tmp_path):
    ctx = book_of(tmp_path, [{"folio": "1"}, {}, {"folio": "3"}])
    assert verified(ctx) == {0: "1", 1: "2", 2: "3"}


def test_roman_front_matter_then_arabic_restart_from_page_labels(tmp_path):
    labels = ["i", "ii", "1", "2", "3"]
    folios = ["i", "ii", None, "2", "3"]  # the chapter opening prints no folio
    specs = [{"label": la, "folio": f} for la, f in zip(labels, folios, strict=True)]
    ctx = book_of(tmp_path, specs)
    assert verified(ctx) == dict(enumerate(labels))
    assert pages.printed_label(ctx, 2) == "1"
    assert pages.pages_for_label(ctx, "ii") == [1]
    assert pages.pages_for_label(ctx, "II") == [1]


def test_named_destinations_confirm_page_labels(tmp_path):
    ctx = book_of(tmp_path, [{"label": "1"}, {"label": "2"}])
    ctx.source.named_dests = {"page.1": (0, 0.0), "page.2": (1, 0.0)}
    assert verified(ctx) == {0: "1", 1: "2"}


def test_stray_margin_number_is_not_taken_as_a_folio(tmp_path):
    # near-match: a year printed alone in the margin of a page whose folio is missing
    ctx = book_of(tmp_path, [{"folio": "4"}, {"folio": "1847"}, {"folio": "6"}])
    assert verified(ctx) == {0: "4", 1: "5", 2: "6"}
    assert pages.pages_for_label(ctx, "1847") == []


def test_page_labels_contradicted_by_folios_abstain(tmp_path):
    specs = [{"label": str(p + 1), "folio": str(p + 5)} for p in range(3)]
    ctx = book_of(tmp_path, specs)
    pages.apply(ctx)
    assert verified(ctx) == {}
    assert codes(ctx).count("pages.unverified_label") == 3
    assert ctx.book.page_list == []
    anchors = page_anchors(ctx.book)
    assert set(anchors) == {"qc-page-0", "qc-page-1", "qc-page-2"}
    assert all(a.get(EPUB_TYPE) is None for a in anchors.values())


def test_duplicate_printed_label_is_ambiguous(tmp_path):
    ctx = book_of(tmp_path, [{"folio": f} for f in ("1", "2", "1", "2")])
    pages.apply(ctx)
    assert pages.pages_for_label(ctx, "1") == [0, 2]
    assert codes(ctx).count("pages.ambiguous_label") == 2
    assert ctx.book.page_list == []
    # the printed number is still announced on each page; only navigation abstains
    assert page_anchors(ctx.book)["qc-page-2"].get("aria-label") == "1"


def test_page_without_output_records_why(tmp_path):
    ctx = book_of(tmp_path, [{"folio": "1"}, {"lines": []}, {"folio": "3"}])
    pages.apply(ctx)
    [issue] = [i for i in ctx.issues if i.code == "pages.no_anchor"]
    assert (issue.page, issue.severity) == (1, Severity.INFO)
    assert "qc-page-1" not in page_anchors(ctx.book)


# --------------------------------------------------------------------------- real Calibre output


@pytest.mark.tools
def test_front_matter_roman_page_list_and_epubcheck(calibre_book, fixture_pdfs, tmp_path):
    pdf, expected = fixture_pdfs["front-matter-roman"]
    ctx = calibre_book(pdf)
    pages.apply(ctx)
    assert [label for label, _ in ctx.book.page_list] == expected["page_labels"]
    hrefs = [href for _, href in ctx.book.page_list]
    assert [h.rpartition("#")[2] for h in hrefs] == [f"qc-page-{p}" for p in range(7)]
    for label, href in ctx.book.page_list:
        _doc, el = ctx.book.element(href)
        assert el.get("aria-label") == label and el.get("role") == "doc-pagebreak"
        assert el.get(EPUB_TYPE) == "pagebreak" and not (el.text or "")
    out = tmp_path / "book.epub"
    ctx.book.save(out, modified="2026-01-01T00:00:00Z")
    checked = run_epubcheck(out, ctx.toolchain)
    assert checked.ok and not checked.errors and not checked.fatal
    assert semantic_check(out) == []
    with zipfile.ZipFile(out) as zf:
        nav = next(n for n in zf.namelist() if "nav" in n and n.endswith("html"))
        tree = etree.fromstring(zf.read(nav))
    kinds = [n.get(EPUB_TYPE) for n in tree.iter(f"{XHTML}nav")]
    assert "page-list" in kinds


@pytest.mark.tools
def test_anchor_lands_inside_a_paragraph_crossing_pages(calibre_book, fixture_pdfs):
    pdf, _ = fixture_pdfs["paragraph-spanning-pages"]
    ctx = calibre_book(pdf)
    # What the paragraphs family does: join the sentence split by the page break.
    first = next(b for b in ctx.blocks if b.text.startswith("It was getting dark"))
    rest = next(b for b in ctx.blocks if b.text.startswith("nued along"))
    _, a = ctx.book.element(f"{first.href}#{first.id}")
    _, b = ctx.book.element(f"{rest.href}#{rest.id}")
    inline.merge(a, b, " ")
    refresh(ctx)
    pages.apply(ctx)
    anchor = page_anchors(ctx.book)["qc-page-1"]
    assert anchor.getparent() is a
    assert anchor.get("aria-label") == "2"
    markup = etree.tostring(a, encoding=str)
    assert markup.index("conti-") < markup.index('id="qc-page-1"') < markup.index("nued along")
    assert inline.text_of(a) == f"{first.text} {rest.text}"
    assert [label for label, _ in ctx.book.page_list] == ["1", "2"]
