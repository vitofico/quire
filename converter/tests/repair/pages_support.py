"""Shared helpers for the pages and index repair tests: synthetic books and Calibre runs."""

import copy
import shutil
import zipfile
from pathlib import Path

import pytest

from quire_convert.align.align import align_book
from quire_convert.context import ConvertContext
from quire_convert.epub.book import Book
from quire_convert.models import Box, Line, PageInventory, SourceInventory, TextBlock, Word

XHTML = "{http://www.w3.org/1999/xhtml}"
EPUB_TYPE = "{http://www.idpf.org/2007/ops}type"
CONTAINER = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/>
</rootfiles></container>"""
DOC = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>t</title></head><body>
{body}
</body></html>"""


def text_block(page: int, n: int, lines: list[str], y0: float, role: str = "unknown"):
    out = []
    for k, text in enumerate(lines):
        y = y0 + 0.03 * k
        words = [
            Word(w, Box(0.1 + 0.06 * i, y, 0.15 + 0.06 * i, y + 0.02), font_size=11.0)
            for i, w in enumerate(text.split())
        ]
        out.append(Line(words, Box(0.1, y, 0.9, y + 0.02)))
    return TextBlock(f"s{page}-b{n}", page, out, Box(0.1, y0, 0.9, y0 + 0.03 * len(lines)), role)


def source(pages: list[dict]) -> SourceInventory:
    """Pages given as ``{"lines": [...], "folio": "3" | None, "label": "3" | None}``; each
    line is its own text block, the folio a margin block at the foot of the page."""
    inventory = []
    for p, spec in enumerate(pages):
        blocks = [text_block(p, n, [line], 0.15 + 0.04 * n) for n, line in enumerate(spec["lines"])]
        folio = spec.get("folio")
        if folio:
            blocks.append(text_block(p, len(blocks), [folio], 0.95, role="folio"))
        inventory.append(
            PageInventory(
                index=p,
                label=spec.get("label"),
                folio_candidates=[folio] if folio else [],
                width_pt=420,
                height_pt=600,
                rotation=0,
                crop_box=(0, 0, 420, 600),
                media_box=(0, 0, 420, 600),
                blocks=blocks,
                images=[],
                text_source="native" if blocks else "none",
                ink_coverage=0.05 if blocks else 0.0,
                text_coverage=1.0,
                page_type="text" if blocks else "blank",
            )
        )
    return SourceInventory(
        path="x.pdf",
        sha256="0" * 64,
        page_count=len(pages),
        encrypted=False,
        needs_password=False,
        info={},
        xmp={},
        language="en",
        pages=inventory,
        bookmarks=[],
        named_dests={},
        complexity={},
        unsupported=[],
        resource={},
    )


def epub(path: Path, docs: list[str]) -> Book:
    items = "".join(
        f'<item id="d{k}" href="d{k}.xhtml" media-type="application/xhtml+xml"/>'
        for k in range(len(docs))
    )
    spine = "".join(f'<itemref idref="d{k}"/>' for k in range(len(docs)))
    opf = f"""<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">x</dc:identifier>
<dc:title>t</dc:title><dc:language>en</dc:language></metadata>
<manifest>{items}</manifest><spine>{spine}</spine></package>"""
    with zipfile.ZipFile(path, "w") as zf:
        zf.writestr("mimetype", "application/epub+zip")
        zf.writestr("META-INF/container.xml", CONTAINER)
        zf.writestr("content.opf", opf)
        for k, body in enumerate(docs):
            zf.writestr(f"d{k}.xhtml", DOC.format(body=body))
    return Book.load(path)


def body_text(page: int) -> str:
    """Unique prose for synthetic page ``page``, so alignment anchors it unambiguously."""
    return f"walk{page} along{page} the river{page} past{page} mills{page} quietly{page}"


def context(src: SourceInventory, book: Book, tmp: Path) -> ConvertContext:
    ctx = ConvertContext(pdf_path=tmp / "x.pdf", workdir=tmp, source=src, book=book, toolchain=None)
    align_book(ctx)
    return ctx


def codes(ctx: ConvertContext) -> list[str]:
    return [i.code for i in ctx.issues]


def page_anchors(book: Book) -> dict[str, object]:
    return {
        el.get("id"): el
        for doc in book.docs
        for el in doc.body.iter(f"{XHTML}span")
        if (el.get("id") or "").startswith("qc-page-")
    }


def calibre_loader(tmp_path_factory):
    """``load(pdf) -> ConvertContext`` on a fresh copy of the PDF's Calibre output."""
    from quire_convert.epub.calibre import run_calibre
    from quire_convert.source.preflight import inspect_pdf
    from quire_convert.tools import find_tools

    tools = find_tools()
    if tools.ebook_convert is None or shutil.which("pdftotext") is None:
        pytest.skip("calibre or poppler not installed")
    cache = {}

    def load(pdf: Path) -> ConvertContext:
        if pdf not in cache:
            work = tmp_path_factory.mktemp(f"pages-{pdf.stem}")
            inv = inspect_pdf(pdf, work / "source", toolchain=tools)
            cache[pdf] = (inv, run_calibre(pdf, work / "calibre.epub", work / "c", tools).epub_path)
        inv, path = cache[pdf]
        ctx = ConvertContext(
            pdf_path=pdf,
            workdir=path.parent,
            source=copy.deepcopy(inv),
            book=Book.load(path),
            toolchain=tools,
        )
        align_book(ctx)
        return ctx

    return load
