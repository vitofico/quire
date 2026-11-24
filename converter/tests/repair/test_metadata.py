import io
import zipfile
from pathlib import Path

import pytest
from PIL import Image, ImageDraw
from reportlab.lib.utils import ImageReader
from reportlab.pdfgen.canvas import Canvas

from quire_convert.context import ConvertContext
from quire_convert.epub.book import Book
from quire_convert.models import Severity
from quire_convert.repair import metadata
from quire_convert.report import is_safe_evidence
from quire_convert.source.preflight import inspect_pdf

CALIBRE_ID = "uuid:6147e72e-16de-4019-84ee-faa7fa650393"

# What Calibre 9.18 writes for a reportlab PDF: info values copied over, "Unknown" file-as,
# "unspecified" subject, its default language and the symlinked file name as doc title.
OPF = """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uuid_id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="uuid_id">{ident}</dc:identifier>
    <dc:title id="t">{title}</dc:title>
    <dc:creator id="c">{author}</dc:creator>
    <meta refines="#c" property="file-as">Unknown</meta>
    <dc:language>en</dc:language>
    <dc:subject>unspecified</dc:subject>
  </metadata>
  <manifest>
    <item id="html" href="index.html" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="html"/></spine>
</package>
"""
DOC = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>source.pdf</title></head>
<body><p>Text</p></body></html>
"""
CONTAINER = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
"""


def calibre_like_book(tmp_path: Path, title: str, author: str) -> Book:
    path = tmp_path / "calibre.epub"
    with zipfile.ZipFile(path, "w") as zf:
        zf.writestr("mimetype", "application/epub+zip")
        zf.writestr("META-INF/container.xml", CONTAINER)
        zf.writestr("content.opf", OPF.format(ident=CALIBRE_ID, title=title, author=author))
        zf.writestr("index.html", DOC)
    return Book.load(path)


def context(tmp_path: Path, pdf: Path, catalog: dict | None = None, book=None) -> ConvertContext:
    source = inspect_pdf(pdf, tmp_path / "work")
    if book is None:
        book = calibre_like_book(tmp_path, source.info.get("title", "source"), "Unknown")
        book.metadata.authors = [source.info.get("author", "Unknown")]
    return ConvertContext(
        pdf_path=pdf,
        workdir=tmp_path / "work",
        source=source,
        book=book,
        toolchain=None,
        catalog_metadata=catalog,
    )


def codes(ctx: ConvertContext) -> list[str]:
    return [i.code for i in ctx.issues]


def doc_title(book: Book) -> str:
    return book.docs[0].tree.findtext("{http://www.w3.org/1999/xhtml}head/{*}title")


# --------------------------------------------------------------------------- units


def test_isbn_check_digits():
    assert metadata.isbn13_valid("9780140327243")
    assert not metadata.isbn13_valid("9780140327244")
    assert not metadata.isbn13_valid("1234567890128")  # not a 978/979 prefix
    assert metadata.isbn10_valid("014032724X")
    assert not metadata.isbn10_valid("0140327241")
    assert metadata.isbn10_to_13("014032724X") == "9780140327243"
    assert metadata.normalize_isbn("ISBN 0-14-032724-x") == "9780140327243"
    assert metadata.normalize_isbn("urn:isbn:978-0-306-40615-7") == "9780306406157"
    assert metadata.normalize_isbn("978-0-306-40615-8") is None
    assert metadata.normalize_isbn("12345") is None


def test_junk_filters():
    for title in ("Microsoft Word - draft3.docx", "untitled", "Document1", "scan_0001", "x.pdf"):
        assert metadata.junk_title(title, set())
    assert metadata.junk_title("ADOBE INDESIGN CS6", set())
    assert metadata.junk_title("front matter roman", {"front matter roman"})
    for title in ("A Quiet Harbour", "1984", "Catch-22", "The Office"):
        assert not metadata.junk_title(title, set())
    for person in ("Administrator", "anonymous", "Unknown", "user1", "Microsoft Word", "a@b.c"):
        assert metadata.junk_person(person)
    assert not metadata.junk_person("Elena Marsh")
    assert metadata.junk_subject("unspecified")


def test_sort_names():
    assert metadata.sort_name("Elena Marsh") == "Marsh, Elena"
    assert metadata.sort_name("Ludwig van Beethoven") == "Beethoven, Ludwig van"
    assert metadata.sort_name("Martin Luther King Jr.") == "King, Martin Luther, Jr."
    assert metadata.sort_name("Homer") == "Homer"


# --------------------------------------------------------------------------- fixtures


@pytest.mark.tools
def test_front_matter_end_to_end(tmp_path, fixture_pdfs):
    pdf, labels = fixture_pdfs["front-matter-roman"]
    ctx = context(tmp_path, pdf)
    metadata.apply(ctx)
    md, want = ctx.book.metadata, labels["metadata"]

    assert md.title == want["title"] and md.subtitle == want["subtitle"]
    assert md.authors == want["authors"] and md.author_sort == "Marsh, Elena"
    assert md.publisher == want["publisher"] and md.date == want["date"]
    assert md.language == want["language"]
    # Both printed forms are the same book: one verified ISBN-13, after the rendition slot.
    assert md.identifiers == [CALIBRE_ID, f"urn:isbn:{want['isbn'][0]}"]
    assert md.subjects == [] and md.cover_href is None  # a plain text title page
    assert "2021" in md.rights
    assert doc_title(ctx.book) == want["title"]
    assert codes(ctx) == []

    sources = {f.name: f.source for f in ctx.metadata}
    assert sources["title"] == "title_page" and sources["publisher"] == "copyright_page"
    assert sources["language"] == "detected"
    assert all(is_safe_evidence(f.evidence) for f in ctx.metadata)
    assert all(is_safe_evidence(e) for r in ctx.repairs for e in r.evidence)
    title_repair = next(r for r in ctx.repairs if r.action == "set_title")
    assert title_repair.before == "Microsoft Word - draft3.docx"


@pytest.mark.tools
def test_real_calibre_output_round_trips(tmp_path, fixture_pdfs):
    from quire_convert.epub.calibre import run_calibre
    from quire_convert.tools import find_tools

    pdf, labels = fixture_pdfs["front-matter-roman"]
    epub = run_calibre(pdf, tmp_path / "c.epub", tmp_path / "cal", find_tools()).epub_path
    ctx = context(tmp_path, pdf, book=Book.load(epub))
    metadata.apply(ctx)
    ctx.book.save(tmp_path / "out.epub", modified="2020-01-01T00:00:00Z")
    md = Book.load(tmp_path / "out.epub").metadata
    assert (md.title, md.subtitle) == (labels["metadata"]["title"], "Letters from the Quay")
    assert md.authors == ["Elena Marsh"] and md.author_sort == "Marsh, Elena"
    assert md.subjects == [] and "urn:isbn:9780140327243" in md.identifiers


@pytest.mark.tools
def test_italian_language_and_calibre_junk_removed(tmp_path, fixture_pdfs):
    pdf, _ = fixture_pdfs["italian"]
    ctx = context(tmp_path, pdf)
    metadata.apply(ctx)
    md = ctx.book.metadata
    assert md.language == "it"  # Calibre wrote its default "en"
    assert md.title is None and md.authors == [] and md.author_sort is None
    assert md.subjects == []
    assert md.identifiers == [CALIBRE_ID]
    assert doc_title(ctx.book) == "source.pdf"  # nothing better to put there
    missing = [i for i in ctx.issues if i.code.startswith("meta.missing_")]
    assert [i.code for i in missing] == ["meta.missing_title", "meta.missing_author"]
    assert all(i.severity is Severity.MAJOR and not i.material for i in missing)


@pytest.mark.tools
def test_catalog_overrides_with_recorded_conflict(tmp_path, fixture_pdfs):
    pdf, _ = fixture_pdfs["front-matter-roman"]
    catalog = {
        "title": "The Harbour Letters",
        "authors": ["Elena Marsh"],
        "language": "en-GB",
        "series": "Coastal",
        "series_index": "2",
        "isbn": ["978-0-306-40615-7"],
    }
    ctx = context(tmp_path, pdf, catalog)
    metadata.apply(ctx)
    md = ctx.book.metadata
    assert md.title == "The Harbour Letters" and md.language == "en-GB"
    assert (md.series, md.series_index) == ("Coastal", "2")
    assert md.identifiers == [CALIBRE_ID, "urn:isbn:9780306406157", "urn:isbn:9780140327243"]
    conflicts = [i for i in ctx.issues if i.code == "meta.conflict"]
    assert [i.evidence for i in conflicts] == ["meta:title kept=src:catalog over=src:title_page"]
    assert conflicts[0].severity is Severity.MINOR and not conflicts[0].material


@pytest.mark.tools
def test_junk_only_pdf_has_no_cover_and_flags_missing(tmp_path, fixture_pdfs):
    pdf, _ = fixture_pdfs["bridge"]
    ctx = context(tmp_path, pdf)
    metadata.apply(ctx)
    md = ctx.book.metadata
    assert md.title is None and md.authors == [] and md.subjects == []
    assert md.description is None  # the last page is the index, not a blurb
    assert md.cover_href is None and not ctx.book.assets
    assert {"meta.missing_title", "meta.missing_author"} <= set(codes(ctx))


def cover_pdf(path: Path) -> Path:
    w, h = 420, 600
    art = Image.new("RGB", (420, 600), (24, 60, 110))
    draw = ImageDraw.Draw(art)
    for y in range(0, 600, 40):
        draw.rectangle((0, y, 420, y + 18), fill=(200, 120 + y // 10, 40))
    buf = io.BytesIO()
    art.save(buf, format="PNG")
    c = Canvas(str(path), pagesize=(w, h), invariant=1)
    c.drawImage(ImageReader(io.BytesIO(buf.getvalue())), 0, 0, w, h)
    c.setFont("Times-Bold", 34)
    c.drawCentredString(w / 2, 420, "Tidewater")
    c.showPage()
    c.setFont("Times-Roman", 12)
    for i in range(20):
        c.drawString(44, 540 - 18 * i, "The river ran low that summer and the boats stayed in.")
    c.save()
    return path


@pytest.mark.tools
def test_cover_from_full_page_image(tmp_path):
    ctx = context(tmp_path, cover_pdf(tmp_path / "cover.pdf"))
    metadata.apply(ctx)
    book = ctx.book
    assert book.metadata.cover_href == "cover.jpg"
    asset = book.assets["cover.jpg"]
    assert asset.media_type == "image/jpeg" and "cover-image" in asset.properties
    with Image.open(io.BytesIO(asset.data)) as img:
        assert img.format == "JPEG" and img.width == 875  # 420 pt at 150 dpi
    assert len(book.docs) == 1  # no cover page inserted into the spine
    cover = next(f for f in ctx.metadata if f.name == "cover")
    assert cover.source == "first_page" and is_safe_evidence(cover.evidence)


BLURB = (
    "When the lighthouse keeper vanishes one winter night, his daughter rows out",
    "across the bay to find him. What she uncovers on the island will change the",
    "village for ever: a ledger of ships that never sailed and a long-buried ferry-",
    "man's promise.",
)
BLURB_2 = (
    "A spare and luminous novel about grief, tides and the stories a small town",
    "tells itself to keep the sea at bay.",
)
WANT_BLURB = " ".join(BLURB).replace("ferry- man", "ferryman") + "\n\n" + " ".join(BLURB_2)


def novel_pdf(path: Path, back_cover: bool) -> Path:
    """Title page, one numbered body page, then a back cover or the novel's last page."""
    w, h = 420, 600
    c = Canvas(str(path), pagesize=(w, h), invariant=1)
    c.setFont("Times-Bold", 28)
    c.drawCentredString(w / 2, 420, "The Keeper")
    c.showPage()
    for page in range(2):
        if page == 1 and back_cover:
            break
        c.setFont("Times-Roman", 12)
        for i in range(22):
            c.drawString(44, 540 - 18 * i, "She rowed on through the dark and did not look back.")
        if page == 0:
            c.setFont("Times-Italic", 10)
            c.drawCentredString(w / 2, 24, "1")
        c.showPage()
    if back_cover:
        c.setFont("Times-Roman", 12)
        for lines, top in ((BLURB, 520), (BLURB_2, 420)):
            for i, line in enumerate(lines):
                c.drawString(44, top - 16 * i, line)
        c.setFont("Times-Italic", 12)
        c.drawString(44, 320, "\u201cA patient, haunting book.\u201d")
        c.drawString(60, 302, "\u2014 The Harbour Review")
        c.setFont("Times-Roman", 10)
        c.drawString(44, 90, "ISBN 978-0-306-40615-7")
        c.drawString(300, 90, "\u00a39.99")
        c.showPage()
    c.save()
    return path


@pytest.mark.tools
def test_back_cover_blurb_fills_description(tmp_path):
    ctx = context(tmp_path, novel_pdf(tmp_path / "keeper.pdf", back_cover=True))
    metadata.apply(ctx)
    assert ctx.book.metadata.description == WANT_BLURB
    field = next(f for f in ctx.metadata if f.name == "description")
    assert field.source == "blurb_page" and is_safe_evidence(field.evidence)
    assert field.evidence == "s2-b0 s2-b1 page=2 cue=review_quote"
    assert "meta.conflict" not in codes(ctx)


@pytest.mark.tools
def test_final_page_of_a_novel_is_not_a_blurb(tmp_path):
    ctx = context(tmp_path, novel_pdf(tmp_path / "keeper.pdf", back_cover=False))
    metadata.apply(ctx)
    assert ctx.book.metadata.description is None
    assert not [f for f in ctx.metadata if f.name == "description"]


@pytest.mark.tools
def test_catalog_description_wins_over_blurb(tmp_path):
    catalog = {"description": "The publisher's own catalogue description."}
    ctx = context(tmp_path, novel_pdf(tmp_path / "keeper.pdf", back_cover=True), catalog)
    metadata.apply(ctx)
    assert ctx.book.metadata.description == catalog["description"]
    conflicts = [i.evidence for i in ctx.issues if i.code == "meta.conflict"]
    assert conflicts == ["meta:description kept=src:catalog over=src:blurb_page"]
