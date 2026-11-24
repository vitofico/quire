"""Source inventory and preflight on tiny PDFs generated with reportlab."""

import shutil
from pathlib import Path

import pytest
from PIL import Image
from pypdf import PdfWriter
from pypdf.generic import Fit
from pypdf.xmp import XmpInformation
from reportlab.lib.utils import ImageReader
from reportlab.pdfgen.canvas import Canvas

from quire_convert.models import Box, StageError
from quire_convert.source.preflight import Limits, inspect_pdf
from quire_convert.source.render import render_region

pytestmark = [
    pytest.mark.tools,
    pytest.mark.skipif(shutil.which("pdftotext") is None, reason="poppler not installed"),
]

W, H = 400, 600
PROSE = (
    "The river ran under the old bridge and the town woke slowly while the carts moved "
    "over the stones near the water and the bells rang from the tower across the square."
)
ITALIAN = (
    "La città taceva e dal porto saliva il profumo del caffè. Sulla soglia della casa un "
    "gatto alzò la testa e poi tornò a dormire, perché non c'era più nessuno per la strada "
    "e il vento portava con sé il rumore delle onde che si rompevano sul molo."
)


def _lines(c: Canvas, text: str, x: float, y: float, width: float, size: float = 11) -> float:
    c.setFont("Times-Roman", size)
    line = ""
    for word in text.split():
        if line and c.stringWidth(f"{line} {word}", "Times-Roman", size) > width:
            c.drawString(x, y, line)
            y -= size * 1.4
            line = word
        else:
            line = f"{line} {word}".strip()
    c.drawString(x, y, line)
    return y - size * 1.4


def _pdf(tmp_path: Path, name: str, pages, **canvas_kw) -> Path:
    path = tmp_path / f"{name}.pdf"
    c = Canvas(str(path), pagesize=(W, H), **canvas_kw)
    for draw in pages:
        draw(c)
        c.showPage()
    c.save()
    return path


def _inspect(pdf: Path, tmp_path: Path, **kw):
    return inspect_pdf(pdf, tmp_path / f"work-{pdf.stem}", **kw)


def _iou(a: Box, b: Box) -> float:
    inter = a.intersection_area(b)
    return inter / (a.area + b.area - inter)


def _text_page(c: Canvas) -> None:
    y = 520
    for _ in range(4):
        y = _lines(c, PROSE, 50, y, W - 100)


def _bridge_page(c: Canvas) -> None:
    _lines(c, PROSE, 50, 540, W - 100)
    c.setLineWidth(0.6)
    c.line(80, 380, 320, 380)  # deck
    for x in (80, 160, 240):
        arch = c.beginPath()
        arch.moveTo(x, 300)
        arch.curveTo(x + 10, 375, x + 70, 375, x + 80, 300)
        c.drawPath(arch)
    c.setFont("Helvetica", 8)
    c.drawString(195, 340, "river")  # label inside the drawing
    c.setFont("Times-Italic", 11)
    c.drawCentredString(W / 2, 270, "Figure 1. The old bridge at dawn, seen from the bank.")


# --------------------------------------------------------------------------- figures


def test_vector_drawing_without_raster_becomes_a_vector_region(tmp_path):
    inv = _inspect(_pdf(tmp_path, "bridge", [_bridge_page]), tmp_path)
    page = inv.pages[0]
    assert [i.kind for i in page.images] == ["vector"]
    region = page.images[0]
    assert region.id == "s0-i0"
    drawing = Box(80 / W, (H - 380) / H, 320 / W, (H - 300) / H)
    assert _iou(region.box, drawing) > 0.8
    caption_top = (H - 270 - 9) / H
    assert region.box.y1 < caption_top, "the caption is not part of the drawing"
    assert page.page_type == "mixed"
    assert page.text_coverage < 1.0
    assert inv.complexity["vector_region_pages"] == [0]
    assert inv.complexity["unexplained_ink"]["0"]


def test_raster_image_region(tmp_path):
    def draw(c: Canvas) -> None:
        _lines(c, PROSE, 50, 540, W - 100)
        c.drawImage(ImageReader(Image.new("RGB", (120, 60), (40, 90, 160))), 100, 250, 200, 100)

    page = _inspect(_pdf(tmp_path, "raster", [draw]), tmp_path).pages[0]
    assert [i.kind for i in page.images] == ["raster"]
    image = page.images[0]
    assert (image.width_px, image.height_px) == (120, 60)
    assert _iou(image.box, Box(100 / W, (H - 350) / H, 300 / W, (H - 250) / H)) > 0.95
    assert page.page_type == "mixed"


def test_full_page_raster_without_text_is_a_scan_but_one_insert_is_supported(tmp_path):
    def scan(c: Canvas) -> None:
        c.drawImage(ImageReader(Image.effect_noise((200, 300), 40).convert("L")), 0, 0, W, H)

    inv = _inspect(_pdf(tmp_path, "insert", [_text_page, scan, _text_page]), tmp_path)
    assert [p.page_type for p in inv.pages] == ["text", "scan", "text"]
    assert "zero_text_layer" in inv.pages[1].flags
    assert inv.complexity["scanned_pages"] == [1]
    assert inv.unsupported == []

    inv = _inspect(_pdf(tmp_path, "scanned", [scan, scan, _text_page]), tmp_path)
    assert inv.unsupported == ["mostly scanned pages (2 of 3) need OCR"]


def test_blank_page(tmp_path):
    inv = _inspect(_pdf(tmp_path, "blank", [_text_page, lambda c: None]), tmp_path)
    blank = inv.pages[1]
    assert blank.page_type == "blank"
    assert (blank.blocks, blank.images, blank.text_source) == ([], [], "none")
    assert blank.ink_coverage == 0.0


# --------------------------------------------------------------------------- layout


def test_two_column_pages_are_flagged(tmp_path):
    def two_columns(c: Canvas) -> None:
        for x in (40, 215):
            y = 540
            for _ in range(3):
                y = _lines(c, PROSE, x, y, 145)

    inv = _inspect(_pdf(tmp_path, "columns", [two_columns, two_columns]), tmp_path)
    assert inv.complexity["columns"] == [2, 2]
    assert all("multi_column" in p.flags for p in inv.pages)
    assert "multi-column layout on 2 of 2 text pages" in inv.unsupported

    single = _inspect(_pdf(tmp_path, "single", [_text_page]), tmp_path)
    assert single.complexity["columns"] == [1]
    assert single.unsupported == []


def _rotated(tmp_path: Path, src: Path, rotations: list[int], crop=None) -> Path:
    writer = PdfWriter(clone_from=str(src))
    for page, rotation in zip(writer.pages, rotations, strict=True):
        page.rotation = rotation
        if crop:
            page.cropbox.lower_left = crop[:2]
            page.cropbox.upper_right = crop[2:]
    out = tmp_path / f"{src.stem}-rotated.pdf"
    writer.write(str(out))
    return out


def test_rotated_landscape_page_normalizes_to_displayed_orientation(tmp_path):
    def landscape(c: Canvas) -> None:
        # Content counter-rotated so that /Rotate 90 displays it upright, landscape.
        c.translate(W, 0)
        c.rotate(90)
        c.setFont("Times-Bold", 20)
        c.drawString(60, 300, "Upright")
        c.drawImage(ImageReader(Image.new("RGB", (40, 20), "black")), 400, 100, 100, 50)

    src = _pdf(tmp_path, "landscape", [landscape])
    page = _inspect(_rotated(tmp_path, src, [90]), tmp_path).pages[0]
    assert (page.width_pt, page.height_pt, page.rotation) == (H, W, 90)
    assert "rotated" in page.flags
    word = page.blocks[0].lines[0].words[0]
    assert word.text == "Upright"
    assert word.bold and word.font_size == pytest.approx(20, abs=0.4)
    # Displayed frame is 600 wide and 400 high; the baseline at y=300 is 100 pt from the top.
    assert word.box.x0 == pytest.approx(60 / H, abs=0.005)
    assert word.box.y1 == pytest.approx((W - 300) / W, abs=0.02)
    raster = [i for i in page.images if i.kind == "raster"][0]
    assert _iou(raster.box, Box(400 / H, 1 - 150 / W, 500 / H, 1 - 100 / W)) > 0.9
    assert "vertical_text" not in page.flags


def test_rotation_and_crop_follow_the_render(tmp_path):
    def marker(c: Canvas) -> None:
        c.setFont("Helvetica", 20)
        c.drawString(80, H - 120, "Mark")

    src = _pdf(tmp_path, "marks", [marker] * 4)
    crop = (40, 80, 300, 560)
    pdf = _rotated(tmp_path, src, [0, 90, 180, 270], crop)
    inv = _inspect(pdf, tmp_path)
    for page in inv.pages:
        word = page.blocks[0].lines[0].words[0].box
        assert "cropped" in page.flags
        # Ink at the word box, measured on a render of just that box.
        out = render_region(pdf, page.index, word, 72, tmp_path / f"w{page.index}.png")
        with Image.open(out) as im:
            dark = sum(1 for v in im.convert("L").tobytes() if v < 128)
        assert dark > 20, f"no ink under the word box on rotation {page.rotation}"
    w0 = inv.pages[0].blocks[0].lines[0].words[0].box
    assert w0.x0 == pytest.approx((80 - 40) / 260, abs=0.005)


def test_fonts_and_folios(tmp_path):
    def styled(c: Canvas) -> None:
        c.setFont("Times-Bold", 18)
        c.drawString(50, 520, "Chapter One")
        text = c.beginText(50, 480)
        text.setFont("Times-Roman", 11)
        text.textOut("plain words ")
        text.setFont("Times-Italic", 11)
        text.textOut("slanted words")
        c.drawText(text)
        c.setFont("Times-Roman", 9)
        c.drawCentredString(W / 2, 20, "xiv")

    page = _inspect(_pdf(tmp_path, "styled", [styled]), tmp_path).pages[0]
    words = {w.text: w for b in page.blocks for line in b.lines for w in line.words}
    assert words["Chapter"].bold and not words["Chapter"].italic
    assert words["Chapter"].font == "Times-Bold"
    assert words["Chapter"].font_size == pytest.approx(18, abs=0.4)
    assert words["slanted"].italic and not words["plain"].italic
    assert page.folio_candidates == ["xiv"]
    folio = [b for b in page.blocks if b.text == "xiv"][0]
    assert folio.role == "folio"
    assert {b.role for b in page.blocks if b is not folio} == {"unknown"}
    assert [b.id for b in page.blocks] == [f"s0-b{n}" for n in range(len(page.blocks))]


def test_invisible_text_layer_is_flagged_broken(tmp_path):
    def invisible(c: Canvas) -> None:
        text = c.beginText(50, 520)
        text.setTextRenderMode(3)
        text.setFont("Times-Roman", 11)
        for _ in range(6):
            text.textLine("words that no render will ever show on this page")
        c.drawText(text)

    page = _inspect(_pdf(tmp_path, "invisible", [invisible]), tmp_path).pages[0]
    assert "broken_text_layer" in page.flags


# --------------------------------------------------------------------------- document


def test_italian_text_is_detected(tmp_path):
    def page(c: Canvas) -> None:
        y = 540
        for _ in range(3):
            y = _lines(c, ITALIAN, 50, y, W - 100)

    inv = _inspect(_pdf(tmp_path, "italiano", [page, page]), tmp_path)
    assert inv.language == "it"
    assert inv.complexity["language"]["detected"] == "it"


def test_declared_language_is_kept_when_it_agrees_or_text_is_short(tmp_path):
    def short(c: Canvas) -> None:
        c.setFont("Times-Roman", 11)
        c.drawString(50, 500, "Ciao")

    inv = _inspect(_pdf(tmp_path, "short", [short], lang="it-IT"), tmp_path)
    assert inv.language == "it-IT"

    def english(c: Canvas) -> None:
        _text_page(c)

    inv = _inspect(_pdf(tmp_path, "mislabelled", [english], lang="de-DE"), tmp_path)
    assert inv.language == "en"


def test_page_labels_outline_and_metadata(tmp_path):
    src = _pdf(tmp_path, "outline", [_text_page] * 4)
    writer = PdfWriter(clone_from=str(src))
    writer.set_page_label(0, 1, style="/r")
    writer.set_page_label(2, 3, style="/D", start=1)
    part = writer.add_outline_item(title="Part One", page_number=2)
    writer.add_outline_item(
        title="Chapter 1", page_number=3, parent=part, fit=Fit.xyz(left=0, top=H - 150)
    )
    writer.add_named_destination("p1", 1)
    writer.add_metadata({"/Title": "The River", "/Author": "A. Writer"})
    xmp = XmpInformation.create()
    xmp.dc_title = {"x-default": "The River"}
    xmp.dc_creator = ["A. Writer"]
    xmp.dc_language = ["it-IT"]
    writer.xmp_metadata = xmp
    pdf = tmp_path / "labelled.pdf"
    writer.write(str(pdf))

    inv = _inspect(pdf, tmp_path)
    assert [p.label for p in inv.pages] == ["i", "ii", "1", "2"]
    assert [(b.title, b.level, b.page) for b in inv.bookmarks] == [
        ("Part One", 1, 2),
        ("Chapter 1", 2, 3),
    ]
    assert inv.bookmarks[1].y == pytest.approx(150 / H, abs=0.001)
    assert inv.named_dests["p1"] == (1, 0.0)
    assert inv.info["title"] == "The River" and inv.info["author"] == "A. Writer"
    assert inv.xmp == {
        "dc_title": "The River",
        "dc_creator": ["A. Writer"],
        "dc_language": ["it-IT"],
    }
    assert inv.language == "en", "the declared language loses to clear text evidence"
    assert len(inv.sha256) == 64 and inv.page_count == 4


def test_password_protected_pdf_returns_needs_password(tmp_path):
    writer = PdfWriter(clone_from=str(_pdf(tmp_path, "open", [_text_page])))
    writer.encrypt("secret", algorithm="RC4-128")
    pdf = tmp_path / "locked.pdf"
    writer.write(str(pdf))

    inv = _inspect(pdf, tmp_path)
    assert inv.encrypted and inv.needs_password
    assert inv.unsupported == ["password-protected PDF"]
    assert inv.pages == []


def test_owner_password_only_is_read_normally(tmp_path):
    writer = PdfWriter(clone_from=str(_pdf(tmp_path, "open", [_text_page])))
    writer.encrypt("", owner_password="owner", algorithm="RC4-128")
    pdf = tmp_path / "restricted.pdf"
    writer.write(str(pdf))

    inv = _inspect(pdf, tmp_path)
    assert inv.encrypted and not inv.needs_password
    assert inv.pages[0].page_type == "text" and inv.language == "en"


def test_unreadable_input_raises_stage_error(tmp_path):
    junk = tmp_path / "junk.pdf"
    junk.write_bytes(b"this is not a pdf at all")
    with pytest.raises(StageError) as err:
        _inspect(junk, tmp_path)
    assert err.value.stage == "preflight"
    with pytest.raises(StageError):
        _inspect(tmp_path / "missing.pdf", tmp_path)


def test_resource_limits_are_unsupported_reasons(tmp_path):
    pdf = _pdf(tmp_path, "three", [_text_page] * 3)
    inv = _inspect(pdf, tmp_path, limits=Limits(max_pages=2))
    assert inv.unsupported == ["exceeds page limit (3 pages, limit 2)"]
    assert inv.pages == [] and inv.resource["pages"] == 3

    inv = _inspect(pdf, tmp_path, limits=Limits(max_decoded_pixels=1000))
    assert inv.unsupported[0].startswith("page too large to render")
    assert inv.resource["max_render_pixels"] > 1000


def test_render_region_crops_the_normalized_box(tmp_path):
    def draw(c: Canvas) -> None:
        c.drawImage(ImageReader(Image.new("RGB", (10, 10), "black")), 100, 300, 100, 100)

    pdf = _pdf(tmp_path, "square", [draw])
    full = render_region(pdf, 0, None, 72, tmp_path / "full.png")
    with Image.open(full) as im:
        assert im.size == (W, H)
    box = Box(100 / W, (H - 400) / H, 200 / W, (H - 300) / H)
    crop = render_region(pdf, 0, box, 144, tmp_path / "crop.png")
    with Image.open(crop) as im:
        assert im.size == (200, 200)
        assert max(im.convert("L").tobytes()) < 60, "the crop is all image"
    with pytest.raises(StageError):
        render_region(pdf, 3, None, 72, tmp_path / "none.png")


# --------------------------------------------------------------------------- shared fixtures


def test_shared_fixtures(fixture_pdfs, tmp_path):
    if "bridge" not in fixture_pdfs or "two-column" not in fixture_pdfs:
        pytest.skip("shared fixtures not built yet")
    pdf, labels = fixture_pdfs["bridge"]
    inv = _inspect(pdf, tmp_path)
    figure = labels["figures"][0]
    vectors = [i for i in inv.pages[figure["page"]].images if i.kind == "vector"]
    assert len(vectors) == 1
    assert _iou(vectors[0].box, Box(*figure["box"])) > 0.8
    assert all(not p.images for p in inv.pages if p.index != figure["page"])

    pdf, labels = fixture_pdfs["two-column"]
    inv = _inspect(pdf, tmp_path)
    assert [p.index for p in inv.pages if "multi_column" in p.flags] == labels["two_column_pages"]
    assert any(r.startswith("multi-column layout") for r in inv.unsupported)
