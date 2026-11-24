import zipfile

import pytest
from lxml import etree
from pages_support import (
    EPUB_TYPE,
    XHTML,
    body_text,
    codes,
    context,
    epub,
    source,
)

import quire_convert.repair
from quire_convert.epub import inline
from quire_convert.epub.validate import semantic_check
from quire_convert.models import Outcome, Severity
from quire_convert.pipeline import convert
from quire_convert.repair import index, pages


def indexed_book(tmp_path, folios, index_lines, heading="Index", run_pages=True):
    """Body pages with ``folios``, then an index page whose lines Calibre merged into one
    paragraph (as it often does), printed with the next folio."""
    specs = [{"lines": [body_text(p)], "folio": f} for p, f in enumerate(folios)]
    specs.append({"lines": [heading, *index_lines], "folio": str(int(folios[-1]) + 1)})
    body = "".join(f"<p>{body_text(p)}</p>" for p in range(len(folios)))
    body += f"<h2>{heading}</h2><p>{' '.join(index_lines)}</p>"
    ctx = context(source(specs), epub(tmp_path / "b.epub", [body]), tmp_path)
    if run_pages:
        pages.apply(ctx)
    index.apply(ctx)
    return ctx


def index_para(ctx):
    return ctx.book.docs[0].body.findall(f"{XHTML}p")[-1]


def links(el) -> list[tuple[str, str]]:
    return [(a.text, a.get("href")) for a in el.iter(f"{XHTML}a")]


def anchor_label(ctx, href: str) -> str | None:
    _doc, el = ctx.book.element(f"d0.xhtml{href}")
    return el.get("aria-label")


LINES = [
    "Arno river, 2, 3-4; see also bridge",
    "bridge, 3",
    "Revolution of 1848, 2",
    "Florence. See Arno river",
    "weir, 9",
]


@pytest.mark.parametrize("heading", ["Index", "Indice analitico"])
def test_index_references_link_to_their_printed_pages(tmp_path, heading):
    ctx = indexed_book(tmp_path, ["1", "2", "3", "4", "5"], LINES, heading)
    para = index_para(ctx)
    found = links(para)
    numbers = [(text, href) for text, href in found if text.isdigit()]
    assert numbers == [
        ("2", "#qc-page-1"),
        ("3", "#qc-page-2"),
        ("4", "#qc-page-3"),
        ("3", "#qc-page-2"),
        ("2", "#qc-page-1"),
    ]
    for text, href in numbers:
        assert anchor_label(ctx, href) == text  # the destination carries the printed label
    # the printed wording is untouched: links wrap existing text and add none
    assert inline.text_of(para) == " ".join(LINES)
    # a year inside a term and an unresolved number stay plain text
    assert "1848" not in [t for t, _ in found] and "9" not in [t for t, _ in found]
    [weir] = [i for i in ctx.issues if i.code == "index.unresolved_reference"]
    assert (weir.severity, weir.page, weir.page_label) == (Severity.MAJOR, 5, "6")
    assert "'9'" in weir.uncertainty


def test_cross_references_link_only_to_a_unique_entry(tmp_path):
    ctx = indexed_book(tmp_path, ["1", "2", "3", "4", "5"], LINES)
    para = index_para(ctx)
    see = {text: href for text, href in links(para) if not text.isdigit()}
    assert set(see) == {"bridge", "Arno river"}
    # "Arno river" opens the paragraph, so its link targets the block itself; "bridge" sits
    # mid-paragraph and gets an empty anchor at the start of its entry
    assert see["Arno river"] == f"#{para.get('id')}"
    _doc, dest = ctx.book.element("d0.xhtml" + see["bridge"])
    assert dest.getparent() is para and not (dest.text or "")
    assert (dest.tail or "").startswith("bridge, ")


def test_cross_reference_to_a_repeated_term_stays_unlinked(tmp_path):
    lines = ["bridge, 2", "bridge, 3", "ford, 4; see bridge"]
    ctx = indexed_book(tmp_path, ["1", "2", "3", "4"], lines)
    assert [t for t, _ in links(index_para(ctx)) if not t.isdigit()] == []
    assert "index.unresolved_cross_reference" in codes(ctx)


def test_numbers_outside_an_index_are_never_linked(tmp_path):
    # near-match: a table of numbers and body text with "see chapter 3" and a year, on a
    # page with no index heading, and a contents page with page numbers
    specs = [
        {"lines": ["Contents", "The River, 2", "The Ford, 3"], "folio": "1"},
        {"lines": [body_text(1), "see chapter 3 for 1848"], "folio": "2"},
        {"lines": ["Year, 1848", "Count, 3", "Depth, 12, 14"], "folio": "3"},
    ]
    paras = "".join(f"<p>{line}</p>" for s in specs for line in s["lines"])
    ctx = context(source(specs), epub(tmp_path / "b.epub", [paras]), tmp_path)
    pages.apply(ctx)
    index.apply(ctx)
    assert not [a for doc in ctx.book.docs for a in doc.body.iter(f"{XHTML}a")]
    assert not [c for c in codes(ctx) if c.startswith("index.")]


def test_duplicated_printed_label_abstains(tmp_path):
    # pages restart their numbering: "2" is printed on two physical pages
    lines = ["river, 2", "mill, 3", "ford, 3-4"]
    ctx = indexed_book(tmp_path, ["1", "2", "1", "2"], lines)
    para = index_para(ctx)
    assert links(para) == [("3", "#qc-page-4")]  # the index page itself is the only "3"
    assert inline.text_of(para) == " ".join(lines)
    issues = {i.code: i for i in ctx.issues if i.code.startswith("index.")}
    ambiguous = issues["index.ambiguous_reference"]
    assert ambiguous.severity == Severity.MAJOR and "'2'" in ambiguous.uncertainty
    assert "pages=1,3" in ambiguous.evidence
    # a range is linked at both ends or not at all
    assert "'4'" in issues["index.unresolved_reference"].uncertainty


def test_index_without_page_anchors_abstains_once(tmp_path):
    ctx = indexed_book(tmp_path, ["1", "2", "3"], ["bridge, 2", "mill, 3"], run_pages=False)
    assert links(index_para(ctx)) == []
    assert codes(ctx) == ["index.no_page_anchors"]


@pytest.mark.tools
def test_bridge_end_to_end(fixture_pdfs, tmp_path, monkeypatch):
    pdf, expected = fixture_pdfs["bridge"]
    monkeypatch.setattr(quire_convert.repair, "REPAIRS", [pages.apply, index.apply])
    result = convert(pdf, job_root=tmp_path / "jobs")
    assert result.outcome in (Outcome.READY, Outcome.REVIEW)  # FAILED on any EPUBCheck error
    assert semantic_check(result.epub_path) == []
    with zipfile.ZipFile(result.epub_path) as zf:
        trees = {
            n: etree.fromstring(zf.read(n))
            for n in zf.namelist()
            if n.endswith((".html", ".xhtml"))
        }
    ids = {el.get("id"): el for tree in trees.values() for el in tree.iter() if el.get("id")}
    found = {}
    for tree in trees.values():
        if tree.find(f".//{XHTML}nav") is not None:
            continue
        for a in tree.iter(f"{XHTML}a"):
            href = a.get("href", "")
            if "qc-page-" in href:
                found[a.getparent().xpath("string()").split(",")[0]] = (a.text, href)
    # bridge -> printed 3 -> physical 2; dawn -> 3 -> 2; river -> 2 -> 1
    want = {e["term"]: e["pages"][0] for e in expected["index"]}
    assert {term: text for term, (text, _) in found.items()} == want
    for text, href in found.values():
        anchor = ids[href.rpartition("#")[2]]
        assert anchor.get("aria-label") == text and anchor.get(EPUB_TYPE) == "pagebreak"
    assert found["bridge"][1].endswith("#qc-page-2") and found["river"][1].endswith("#qc-page-1")
    navs = [n for tree in trees.values() for n in tree.iter(f"{XHTML}nav")]
    assert "page-list" in [n.get(EPUB_TYPE) for n in navs]
    assert not [i for i in result.report.issues if i.code.startswith("index.")]
