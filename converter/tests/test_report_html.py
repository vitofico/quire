import io
import re
import shutil

import pytest
from lxml import etree
from PIL import Image

from quire_convert import report_html
from quire_convert.align.align import align_book
from quire_convert.align.coverage import account
from quire_convert.context import ConvertContext
from quire_convert.epub.book import Book
from quire_convert.epub.calibre import run_calibre
from quire_convert.epub.package import XHTML_NS
from quire_convert.report_html import write_html_report
from quire_convert.source.preflight import inspect_pdf
from quire_convert.tools import find_tools

pytestmark = pytest.mark.tools

HOSTILE = "<img src=x onerror=alert(1)>"


@pytest.fixture(scope="module")
def bridge_build(fixture_pdfs, tmp_path_factory):
    tools = find_tools()
    if tools.ebook_convert is None or shutil.which("pdftoppm") is None:
        pytest.skip("calibre or poppler not installed")
    pdf, _labels = fixture_pdfs["bridge"]
    work = tmp_path_factory.mktemp("html-bridge")
    inv = inspect_pdf(pdf, work / "source", toolchain=tools)
    run = run_calibre(pdf, work / "calibre.epub", work / "calibre", tools)
    return pdf, inv, run.epub_path, tools


@pytest.fixture
def ctx(bridge_build, tmp_path):
    pdf, inv, epub, tools = bridge_build
    ctx = ConvertContext(
        pdf_path=pdf, workdir=tmp_path, source=inv, book=Book.load(epub), toolchain=tools
    )
    ctx.blocks = ctx.book.ensure_block_ids()
    align_book(ctx)
    account(ctx)
    return ctx


def _inject(ctx) -> None:
    """Hostile markup inside a real block (text, an event handler, a script, remote refs)
    next to a local image that the report must copy."""
    block = next(b for b in ctx.blocks if b.tag == "p" and b.spans)
    _doc, el = ctx.book.element(f"{block.href}#{block.id}")
    el.text = HOSTILE + (el.text or "")
    el.set("onclick", "alert(2)")
    etree.SubElement(el, f"{{{XHTML_NS}}}script").text = "alert(3)"
    link = etree.SubElement(el, f"{{{XHTML_NS}}}a", href="https://evil.example/x")
    link.text = "link"
    etree.SubElement(el, f"{{{XHTML_NS}}}img", src="http://evil.example/x.png")
    png = io.BytesIO()
    Image.new("RGB", (4, 4), "red").save(png, "PNG")
    ctx.book.add_asset("images/qc-test.png", png.getvalue(), "image/png")
    etree.SubElement(el, f"{{{XHTML_NS}}}img", src="images/qc-test.png", alt="local")


def test_report_pairs_lost_figure_crop_and_is_inert(ctx, tmp_path):
    _inject(ctx)
    out = tmp_path / "html"
    index = write_html_report(ctx, out)
    html = index.read_text(encoding="utf-8")

    cards = re.findall(r'<article class="card[^"]*" id="issue-\d+">.*?</article>', html, re.S)
    assert len(cards) == len(ctx.issues)
    lost = next(c for c in cards if "coverage.lost_figure" in c)
    crop = re.search(r'<img src="(crops/issue-\d+\.png)"', lost).group(1)
    with Image.open(out / crop) as img:
        lo, hi = img.convert("L").getextrema()
    assert hi - lo > 100  # the bridge drawing, not a blank render
    assert "EPUB" in lost and '<div class="epub">' in lost

    lowered = html.lower()
    assert "<script" not in lowered and "onclick" not in lowered and "onerror=alert(1)>" not in html
    assert "&lt;img src=x onerror=alert(1)&gt;" in html
    assert "http://" not in html and "https://" not in html and 'src="//' not in html
    srcs = re.findall(r'src="([^"]+)"', html)
    assert all(s.startswith(("crops/", "assets/")) and (out / s).is_file() for s in srcs)
    assert any(s.startswith("assets/") for s in srcs)
    assert html.count('<article class="page"') == len(ctx.source.pages)


def test_render_cap_is_reported(ctx, tmp_path, monkeypatch):
    monkeypatch.setattr(report_html, "MAX_RENDERS", 1)
    html = write_html_report(ctx, tmp_path / "html").read_text(encoding="utf-8")
    assert "caps renders at 1" in html
    assert len(list((tmp_path / "html" / "crops").glob("*.png"))) == 1
