import shutil
from pathlib import Path

import pytest
from fixtures.build import HARBOUR, RIVER, H, M, W, _bridge_figure, _paragraph
from reportlab.pdfgen.canvas import Canvas

from quire_convert.align.align import align_book
from quire_convert.align.coverage import account
from quire_convert.context import ConvertContext
from quire_convert.epub.book import Book
from quire_convert.epub.calibre import run_calibre
from quire_convert.epub.inline import text_of
from quire_convert.epub.package import XHTML_NS, resolve_href
from quire_convert.epub.validate import run_epubcheck, semantic_check
from quire_convert.repair import figures
from quire_convert.report import is_safe_evidence
from quire_convert.source.preflight import inspect_pdf
from quire_convert.tools import find_tools

pytestmark = pytest.mark.tools

X = f"{{{XHTML_NS}}}"


@pytest.fixture(scope="module")
def tools():
    found = find_tools()
    if found.ebook_convert is None or shutil.which("pdftotext") is None:
        pytest.skip("calibre or poppler not installed")
    return found


def convert(pdf: Path, work: Path, tools) -> ConvertContext:
    """Raw Calibre output for ``pdf``, aligned, with figures repaired and coverage taken."""
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
    figures.apply(ctx)
    account(ctx)
    return ctx


def codes(ctx: ConvertContext) -> list[str]:
    return [i.code for i in ctx.issues]


def elements(ctx: ConvertContext, tag: str):
    for doc in ctx.book.docs:
        for el in doc.body.iter(X + tag):
            yield doc, el


def outside_figures(ctx: ConvertContext) -> list[str]:
    """Text of every paragraph not inside a figure."""
    return [
        " ".join(text_of(p).split())
        for _, p in elements(ctx, "p")
        if not any(a.tag == X + "figure" for a in p.iterancestors())
    ]


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


def test_bridge_figure_restored(fixture_pdfs, tmp_path, tools):
    pdf, labels = fixture_pdfs["bridge"]
    ctx = convert(pdf, tmp_path, tools)

    [(doc, fig)] = elements(ctx, "figure")
    assert fig.get("data-qc-source") == "s2-i0 s2-b3 s2-b4"
    [img] = fig.iter(X + "img")
    assert img.get("alt") == ""
    asset = ctx.book.assets[resolve_href(doc.href, img.get("src"))]
    width, height = figures.visible_size(asset.data)
    assert width > height > 100

    caption = labels["figures"][0]["caption"]
    [figcaption] = fig.iter(X + "figcaption")
    assert " ".join(text_of(figcaption).split()) == caption
    body = outside_figures(ctx)
    assert caption not in " ".join(body)
    assert not any(text in {"A", "B", "A B"} for text in body)
    assert any(text.startswith("Figure 1 shows the low deck") for text in body)

    assert "coverage.lost_figure" not in codes(ctx)
    assert "figure.image_only" in codes(ctx)
    actions = [r.action for r in ctx.repairs]
    assert actions.count("remove_label") == 2
    assert {"insert_figure", "attach_caption"} <= set(actions)
    assert_valid(ctx, tmp_path, tools)


def test_scanned_page_kept_as_facsimile(fixture_pdfs, tmp_path, tools):
    pdf, _ = fixture_pdfs["zero-text-layer"]
    ctx = convert(pdf, tmp_path, tools)

    marked = [img for _, img in elements(ctx, "img") if img.get("data-qc-source") == "s1-i0"]
    assert len(marked) == 1
    [issue] = [i for i in ctx.issues if i.code == "figure.facsimile_page"]
    assert issue.page == 1 and not issue.material
    assert "coverage.lost_figure" not in codes(ctx)
    assert_valid(ctx, tmp_path, tools)


def near_match_pdf(path: Path) -> Path:
    """A rule and a tinted panel of body text, then a drawing whose only nearby text is a
    sentence about Figure 1 (no caption)."""
    c = Canvas(str(path), pagesize=(W, H), invariant=1)
    _paragraph(c, RIVER[0], 540)
    c.setLineWidth(3)
    c.line(M, 470, W - M, 470)
    c.setFillGray(0.55)
    c.rect(M - 6, 300, W - 2 * M + 12, 140, fill=1, stroke=0)
    c.setFillGray(0)
    _paragraph(c, HARBOUR[1] + " " + HARBOUR[2], 420)
    c.showPage()
    _paragraph(c, RIVER[1], 540)
    _bridge_figure(c)
    _paragraph(c, "Figure 1 shows the low deck where the road crosses the river.", 227)
    c.showPage()
    c.save()
    return path


def test_text_panel_and_figure_mention_are_left_alone(tmp_path, tools):
    ctx = convert(near_match_pdf(tmp_path / "near.pdf"), tmp_path, tools)

    figs = [fig for _, fig in elements(ctx, "figure")]
    assert [f.get("data-qc-source").split()[0] for f in figs] == ["s1-i0"]
    assert not list(figs[0].iter(X + "figcaption"))
    body = " ".join(outside_figures(ctx))
    assert "The little crosses beside the lighthouse" in body
    assert "Figure 1 shows the low deck" in body
    assert "coverage.lost_figure" not in codes(ctx)
    assert "figure.caption_unmatched" not in codes(ctx)
    # The tinted panel is counted by coverage itself; no paragraph is tagged to stand in.
    assert ctx.alignment.coverage["illustrations"]["text_panels"] == 1
    assert all(el.get("data-qc-source") is None for _, el in elements(ctx, "p"))
    assert_valid(ctx, tmp_path, tools)


def two_captions_pdf(path: Path) -> Path:
    """A drawing with an equally close, equally numbered caption above and below it."""
    c = Canvas(str(path), pagesize=(W, H), invariant=1)
    _paragraph(c, RIVER[2], 540)
    top, bottom = _bridge_figure(c)[1::2]
    gap = 8
    c.setFont("Times-Italic", 11)
    c.drawCentredString(W / 2, H - top * H + gap, "Figure 1. The bridge from the east bank.")
    c.drawCentredString(W / 2, H - bottom * H - gap - 8, "Figure 1. The bridge from the west bank.")
    _paragraph(c, HARBOUR[3], 190)
    c.showPage()
    c.save()
    return path


def test_two_equal_captions_abstain(tmp_path, tools):
    ctx = convert(two_captions_pdf(tmp_path / "two.pdf"), tmp_path, tools)

    [(_, fig)] = elements(ctx, "figure")
    assert not list(fig.iter(X + "figcaption"))
    body = " ".join(outside_figures(ctx))
    assert "Figure 1. The bridge from the east bank." in body
    assert "Figure 1. The bridge from the west bank." in body
    [issue] = [i for i in ctx.issues if i.code == "figure.caption_unmatched"]
    assert len([t for t in issue.evidence.split() if t.startswith("candidate=")]) == 2
    assert "attach_caption" not in [r.action for r in ctx.repairs]
    assert "coverage.lost_figure" not in codes(ctx)
    assert_valid(ctx, tmp_path, tools)
