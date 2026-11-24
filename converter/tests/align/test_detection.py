"""Coverage detection on real Calibre output of the fixture PDFs.

Each demo starts from what Calibre made of a fixture, damages the EPUB in memory the way
a bad conversion or repair would, and checks that coverage names the right issue on the
right page. The clean controls check the untouched output raises nothing else.
"""

import copy

import pytest
from lxml import etree

from quire_convert.align.align import align_book
from quire_convert.align.coverage import account, note_blocks
from quire_convert.context import ConvertContext
from quire_convert.epub.book import Book
from quire_convert.models import Severity
from quire_convert.report import is_safe_evidence

pytestmark = pytest.mark.tools

# What coverage should say about Calibre's untouched output: the bridge drawing on page 2
# is lost, and Calibre keeps the printer slug outside the rotated page's crop box.
EXPECTED_BASELINE = {
    "bridge": [("coverage.lost_figure", 2)],
    "rotated-crop": [("coverage.invented_text", 1)],
}


@pytest.fixture
def load(calibre_fixture, tmp_path):
    def make(name: str) -> ConvertContext:
        inv, epub = calibre_fixture(name)
        ctx = ConvertContext(
            pdf_path=tmp_path / "in.pdf",
            workdir=tmp_path,
            source=inv,
            book=Book.load(epub),
            toolchain=None,
        )
        align_book(ctx)
        return ctx

    return make


def codes(ctx) -> list[tuple[str, int | None]]:
    """(code, page) of every issue; every issue must also survive report redaction."""
    assert all(is_safe_evidence(i.evidence) for i in ctx.issues)
    return sorted((i.code, i.page) for i in ctx.issues)


def element(ctx, block_id: str) -> etree._Element:
    for doc in ctx.book.docs:
        for el in doc.body.iter(etree.Element):
            if el.get("id") == block_id:
                return el
    raise KeyError(block_id)


def prose_on(ctx, page: int) -> list[str]:
    """Output paragraphs drawn wholly from ``page``, in reading order."""
    return [
        b.id
        for b in ctx.blocks
        if b.tag == "p" and len(b.text.split()) >= 20 and ctx.alignment.block_pages(b.id) == [page]
    ]


def source_block(ctx, block_id: str) -> str:
    return ctx.alignment.output_to_source(block_id, 0).block


def test_clean_controls(fixture_pdfs, load):
    seen = {}
    for name in sorted(fixture_pdfs):
        ctx = load(name)
        summary = account(ctx)
        seen[name] = codes(ctx)
        assert summary["prose"]["ratio"] == 1.0, name
    assert seen == {name: EXPECTED_BASELINE.get(name, []) for name in fixture_pdfs}


def test_bridge_baseline_loses_the_drawing(load):
    ctx = load("bridge")
    summary = account(ctx)
    (issue,) = ctx.issues
    assert issue.code == "coverage.lost_figure" and issue.page == 2
    assert issue.severity == Severity.CRITICAL and issue.material and issue.action == "flagged"
    assert issue.evidence.startswith("s2-i0 ") and issue.region is not None
    assert issue.output_anchor and issue.uncertainty
    assert summary["illustrations"]["regions"] == 1 and summary["illustrations"]["ratio"] == 0


def test_figure_marked_with_its_source_clears_the_loss(load):
    ctx = load("bridge")
    anchor = element(ctx, prose_on(ctx, 2)[0])
    ns = etree.QName(anchor).namespace
    figure = etree.Element(f"{{{ns}}}figure", {"data-qc-source": "s2-i0"})
    etree.SubElement(figure, f"{{{ns}}}img", src="images/fig.png", alt="")
    anchor.addnext(figure)
    summary = account(ctx)
    assert ctx.issues == []
    assert summary["illustrations"]["ratio"] == 1.0
    assert ctx.alignment.block_pages(ctx.alignment.images["s2-i0"]) == [2]


def test_duplicated_paragraph(load):
    ctx = load("bridge")
    original = element(ctx, prose_on(ctx, 1)[0])
    twin = copy.deepcopy(original)
    del twin.attrib["id"]
    element(ctx, prose_on(ctx, 2)[-1]).addnext(twin)  # a page later
    account(ctx)
    assert codes(ctx) == [("coverage.duplicated_text", 1), ("coverage.lost_figure", 2)]
    dup = next(i for i in ctx.issues if i.code == "coverage.duplicated_text")
    assert dup.severity == Severity.CRITICAL
    assert f"first={original.get('id')} " in dup.evidence


def test_deleted_paragraph(load):
    ctx = load("bridge")
    victim = prose_on(ctx, 1)[1]
    gone = source_block(ctx, victim)
    el = element(ctx, victim)
    el.getparent().remove(el)
    account(ctx)
    assert codes(ctx) == [("coverage.lost_figure", 2), ("coverage.missing_text", 1)]
    missing = next(i for i in ctx.issues if i.code == "coverage.missing_text")
    assert missing.severity == Severity.CRITICAL and missing.evidence.startswith(f"{gone} ")


def test_swapped_paragraphs(load):
    ctx = load("bridge")
    first, second = (element(ctx, b) for b in prose_on(ctx, 1)[:2])
    second.addnext(copy.deepcopy(first))
    first.getparent().remove(first)
    account(ctx)
    assert "coverage.order_mismatch" in {code for code, _ in codes(ctx)}
    order = [i for i in ctx.issues if i.code == "coverage.order_mismatch"]
    assert order and all(i.page == 1 for i in order)
    assert not [
        i for i in ctx.issues if i.code in ("coverage.missing_text", "coverage.invented_text")
    ]


def test_swapped_columns(load):
    ctx = load("two-column")
    blocks = {b.id: b for p in ctx.source.pages for b in p.blocks}
    page0 = [b.id for b in ctx.blocks if ctx.alignment.block_pages(b.id) == [0]]
    right = [b for b in page0 if blocks[source_block(ctx, b)].box.x0 >= 0.5]
    left = [b for b in page0 if b not in right and len(element(ctx, b).text or "") > 20]
    assert right and left
    first_left = element(ctx, left[0])
    for block_id in right:
        el = element(ctx, block_id)
        el.getparent().remove(el)
        first_left.addprevious(el)
    account(ctx)
    assert "coverage.order_mismatch" in {code for code, _ in codes(ctx)}
    order = [i for i in ctx.issues if i.code == "coverage.order_mismatch"]
    assert order and {i.page for i in order} == {0}
    assert not [
        i for i in ctx.issues if i.code in ("coverage.missing_text", "coverage.invented_text")
    ]


def test_deleted_note_body(load):
    ctx = load("repeated-note-numbers")
    note = sorted(n for n in note_blocks(ctx.source) if n.startswith("s0-"))[0]
    victim = next(
        b.id
        for b in ctx.blocks
        if ctx.alignment.output_block_tokens(b.id) and source_block(ctx, b.id) == note
    )
    el = element(ctx, victim)
    el.getparent().remove(el)
    summary = account(ctx)
    assert codes(ctx) == [("coverage.lost_note", 0)]
    (issue,) = ctx.issues
    assert issue.severity == Severity.CRITICAL and issue.evidence.startswith(f"{note} ")
    assert summary["notes"]["ratio"] < 1.0 and summary["prose"]["ratio"] == 1.0


def test_deleted_illustration(load):
    ctx = load("zero-text-layer")
    for doc in ctx.book.docs:
        for img in list(doc.body.iter("{*}img")):
            img.getparent().remove(img)
    account(ctx)
    assert codes(ctx) == [("coverage.lost_figure", 1)]
