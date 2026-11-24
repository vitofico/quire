"""In-memory source inventories and EPUBs for alignment tests (no PDF, no Calibre)."""

import random
import zipfile
from pathlib import Path

from quire_convert.context import ConvertContext
from quire_convert.epub.book import Book
from quire_convert.models import Box, Line, PageInventory, SourceInventory, TextBlock, Word

CONTAINER = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/>
</rootfiles></container>"""
DOC = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>t</title></head><body>
{body}
</body></html>"""


def block(page: int, n: int, lines: list[str], y0: float, size: float = 12.0, role="unknown"):
    out = []
    for k, text in enumerate(lines):
        y = y0 + 0.02 * k
        words = [
            Word(w, Box(0.1 + 0.05 * i, y, 0.14 + 0.05 * i, y + 0.015), font_size=size)
            for i, w in enumerate(text.split())
        ]
        out.append(Line(words, Box(0.1, y, 0.9, y + 0.015)))
    box = Box(0.1, y0, 0.9, y0 + 0.02 * len(lines))
    return TextBlock(f"s{page}-b{n}", page, out, box, role=role)


def inventory(pages: list[list[TextBlock]]) -> SourceInventory:
    return SourceInventory(
        path="x.pdf",
        sha256="0" * 64,
        page_count=len(pages),
        encrypted=False,
        needs_password=False,
        info={},
        xmp={},
        language="en",
        pages=[
            PageInventory(
                index=i,
                label=str(i + 1),
                folio_candidates=[],
                width_pt=420,
                height_pt=600,
                rotation=0,
                crop_box=(0, 0, 420, 600),
                media_box=(0, 0, 420, 600),
                blocks=blocks,
                images=[],
                text_source="native",
                ink_coverage=0.05,
                text_coverage=1.0,
                page_type="text",
            )
            for i, blocks in enumerate(pages)
        ],
        bookmarks=[],
        named_dests={},
        complexity={},
        unsupported=[],
        resource={},
    )


def epub(path: Path, docs: list[str]) -> Path:
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
    return path


def context(source: SourceInventory, book: Book, tmp: Path) -> ConvertContext:
    return ConvertContext(
        pdf_path=tmp / "x.pdf", workdir=tmp, source=source, book=book, toolchain=None
    )


def novel(words: int, seed: int = 7, per_page: int = 330):
    """Random prose: pages of 3-line-per-block paragraphs, plus matching XHTML paragraphs."""
    rng = random.Random(seed)
    vocab = [
        "".join(rng.choice("abcdefghijklmnopqrstuvwxyz") for _ in range(rng.randint(2, 9)))
        for _ in range(6000)
    ] + ["the", "and", "of", "a", "to", "in"] * 300
    stream = [rng.choice(vocab) for _ in range(words)]
    pages, paragraphs = [], []
    for p, start in enumerate(range(0, words, per_page)):
        chunk = stream[start : start + per_page]
        blocks = [block(p, 0, ["Running Title"], 0.03, 10)]
        for n, k in enumerate(range(0, len(chunk), 30), 1):
            para = chunk[k : k + 30]
            lines = [" ".join(para[i : i + 10]) for i in range(0, len(para), 10)]
            blocks.append(block(p, n, lines, 0.1 + 0.07 * n))
            paragraphs.append(" ".join(para))
        blocks.append(block(p, len(blocks), [str(p + 1)], 0.95, 10, role="folio"))
        pages.append(blocks)
    return pages, paragraphs
