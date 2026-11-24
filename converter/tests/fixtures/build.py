"""Generate the converter's small, original regression books and reference labels."""

import argparse
import json
from collections.abc import Callable
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont
from reportlab.lib.utils import ImageReader
from reportlab.pdfgen.canvas import Canvas

W, H = 420, 600
M = 44
BODY = "Times-Roman"
SIZE = 12

# Short passages with distinct openings for page and chapter alignment.
RIVER = (
    "From the window Mara could see the river cutting a new channel through the reeds. "
    "The white post that once marked the landing now stood alone in deep water.",
    "Her father had measured the bank each winter with a length of knotted cord. "
    "She found his figures in the kitchen ledger beside the price of salt and flour.",
    "Before the ferry came she walked down to the last step and stretched the cord "
    "between her hands. Somewhere across the water a shutter tapped against a wall.",
)
HARBOUR = (
    "When the bell sounded Elena put down her sewing and went to the quay. "
    "A boat was edging past the nets with a yellow lamp still burning at its bow.",
    "Inside the cabin a child had drawn the coast on a square of brown paper. "
    "The little crosses beside the lighthouse showed where the water ran shallow.",
    "By noon the tide had turned. Elena waited by the warehouse door until the "
    "last crate was lifted and the men had thrown their gloves upon the stones.",
    "In the evening she took the map home and spread it on the table. A faint "
    "line in blue pencil led beyond the headland to a cove she remembered.",
)


def _canvas(out: Path, name: str, language: str = "en") -> Canvas:
    return Canvas(str(out / f"{name}.pdf"), pagesize=(W, H), invariant=1, lang=language)


def _labels(count: int, language: str = "en") -> dict:
    return {"pages": count, "language": language, "page_labels": [str(i + 1) for i in range(count)]}


def _chapter(title: str, page: int) -> dict:
    return {"title": title, "level": 1, "page": page}


def _furniture(c: Canvas, folio: str, header: str = "") -> None:
    c.setFont("Times-Italic", 10)
    c.drawCentredString(W / 2, 24, folio)
    if header:
        c.drawCentredString(W / 2, H - 27, header)


def _heading(c: Canvas, title: str) -> None:
    c.setFont("Times-Bold", 22)
    c.drawString(M, H - 88, title)


def _paragraph(c: Canvas, text: str, y: float, x: float = M, width: float = W - 2 * M) -> float:
    c.setFont(BODY, SIZE)
    line = ""
    for word in text.split():
        candidate = f"{line} {word}".strip()
        if line and c.stringWidth(candidate, BODY, SIZE) > width:
            gaps = line.count(" ")
            extra = (width - c.stringWidth(line, BODY, SIZE)) / gaps if gaps else 0
            c.drawString(x, y, line, wordSpace=extra)
            y -= 16
            line = word
        else:
            line = candidate
    c.drawString(x, y, line)
    return y - 27


def _bridge_figure(c: Canvas) -> list[float]:
    left, right, deck, water = 63, W - 63, 345, 258
    c.setLineWidth(2)
    c.line(left, deck, right, deck)
    for x in range(left, right, 98):
        arch = c.beginPath()
        arch.moveTo(x + 3, water + 8)
        arch.curveTo(x + 9, deck + 5, x + 89, deck + 5, x + 95, water + 8)
        c.drawPath(arch)
    for x in range(left, right + 1, 98):
        c.rect(x - 3, water + 8, 6, deck - water - 8, fill=1)
    c.setLineWidth(0.7)
    for dy in (0, -4, -8):
        c.line(left + 4, water + dy, right - 4, water + dy)
    c.setFont("Helvetica", 11)
    for x, label in ((left, "A"), (right, "B")):
        c.drawCentredString(x, deck + 7, label)
    return [(left - 5) / W, (H - deck - 19) / H, (right + 5) / W, (H - water + 9) / H]


def bridge(out: Path) -> dict:
    c = _canvas(out, "bridge")
    river, crossing = "Chapter One: The River", "Chapter Two: The Bridge"
    caption = "Figure 1. The old bridge at dawn."
    _furniture(c, "1")
    _heading(c, "Contents")
    c.setFont(BODY, SIZE)
    for i, title in enumerate((river, crossing, "Index")):
        c.drawString(M, 465 - 25 * i, f"{title} ..... {i + 2}")
    c.showPage()
    header = "Along the Water"
    _furniture(c, "2", header)
    _heading(c, river)
    y = 462
    for passage in RIVER:
        y = _paragraph(c, passage, y)
    c.showPage()
    _furniture(c, "3", header)
    _heading(c, crossing)
    _paragraph(
        c,
        "At first light the bridge seemed to float above its own reflection. "
        "From the eastern bank Mara could just make out the shape of the three arches.",
        462,
    )
    box = _bridge_figure(c)
    c.setFont("Times-Italic", 11)
    c.drawCentredString(W / 2, 227, caption)
    _paragraph(c, "Figure 1 shows the low deck where the road crosses the river.", 186)
    c.showPage()
    _furniture(c, "4")
    _heading(c, "Index")
    entries = [("bridge", "3"), ("dawn", "3"), ("river", "2")]
    c.setFont(BODY, SIZE)
    for i, (term, label) in enumerate(entries):
        c.drawString(M, 462 - 23 * i, f"{term}, {label}")
    c.save()
    return _labels(4) | {
        "chapters": [_chapter(river, 1), _chapter(crossing, 2)],
        "toc_pages": [0],
        "figures": [{"id": "fig1", "page": 2, "box": box, "caption": caption}],
        "index": [{"term": term, "pages": [label]} for term, label in entries],
        "running_headers": [{"text": header, "pages": [1, 2]}],
    }


def front_matter_roman(out: Path) -> dict:
    c = _canvas(out, "front-matter-roman")
    c.setTitle("Microsoft Word - draft3.docx")
    c.setAuthor("Administrator")
    c.setCreator("Microsoft Word")
    c.addPageLabel(0, "ROMAN_LOWER", 1)
    c.addPageLabel(3, "ARABIC", 1)
    title, subtitle, author, publisher = (
        "A Quiet Harbour",
        "Letters from the Quay",
        "Elena Marsh",
        "Lantern Press",
    )
    _furniture(c, "i")
    for value, y, font, size in [
        (title, 420, "Times-Bold", 28),
        (subtitle, 386, "Times-Italic", 16),
        (author, 315, BODY, 16),
        (publisher, 100, BODY, 12),
    ]:
        c.setFont(font, size)
        c.drawCentredString(W / 2, y, value)
    c.showPage()
    stem = "014032724"
    digits = "978" + stem
    isbn13 = digits + str(
        -sum(int(n) * (1 if i % 2 == 0 else 3) for i, n in enumerate(digits)) % 10
    )
    remainder = -sum((10 - i) * int(n) for i, n in enumerate(stem)) % 11
    isbn10 = stem + ("X" if remainder == 10 else str(remainder))
    _furniture(c, "ii")
    c.setFont(BODY, SIZE)
    for i, line in enumerate(
        (
            f"Published by {publisher}",
            f"Copyright © 2021 {author}",
            f"ISBN-13: {isbn13}",
            f"ISBN-10: {isbn10}",
        )
    ):
        c.drawString(M, 240 - i * 20, line)
    c.showPage()
    chapters = ["The Lamp", "The Tide"]
    _furniture(c, "iii")
    _heading(c, "Contents")
    c.setFont(BODY, SIZE)
    for i, chapter in enumerate(chapters):
        c.drawString(M, 462 - 24 * i, f"{chapter} ..... {1 + i * 2}")
    c.showPage()
    for i, passage in enumerate(HARBOUR):
        _furniture(c, str(i + 1), title)
        if i in (0, 1):
            dest = f"dest{i}"
            c.bookmarkPage(dest)
            c.addOutlineEntry(chapters[i], dest)
        if i in (0, 2):
            _heading(c, chapters[i // 2])
        _paragraph(c, passage, 462 if i % 2 == 0 else 520)
        c.showPage()
    c.save()
    return _labels(7) | {
        "page_labels": ["i", "ii", "iii", "1", "2", "3", "4"],
        "toc_pages": [2],
        "metadata": {
            "title": title,
            "subtitle": subtitle,
            "authors": [author],
            "publisher": publisher,
            "date": "2021",
            "isbn": [isbn13, isbn10],
            "language": "en",
        },
        "chapters": [_chapter(chapters[0], 3), _chapter(chapters[1], 5)],
        "bookmark_traps": [{"title": chapters[1], "bookmark_page": 4, "chapter_page": 5}],
    }


def duplicate_chapter_names(out: Path) -> dict:
    c = _canvas(out, "duplicate-chapter-names")
    book = "The Sounding Line"
    titles = ["The Crossing", "Interlude", book, "Interlude"]
    for i, (title, passage) in enumerate(zip(titles, HARBOUR, strict=True)):
        _furniture(c, str(i + 1), book)
        _heading(c, title)
        _paragraph(c, passage, 462)
        c.showPage()
    c.save()
    return _labels(4) | {
        "chapters": [_chapter(t, i) for i, t in enumerate(titles)],
        "running_headers": [{"text": book, "pages": [0, 1, 2, 3]}],
    }


def repeated_note_numbers(out: Path) -> dict:
    c = _canvas(out, "repeated-note-numbers")
    notes = []
    citations = [
        [("She turned to the harbour ledger.", "The ledger dates from the winter of 1847.")],
        [
            ("The map bears a second name.", "The name was added in a different hand."),
            ("Only one sounding survives.", "The depth is given in fathoms."),
        ],
    ]
    for i, refs in enumerate(citations):
        _furniture(c, str(i + 1), "The Harbour Ledger")
        y = _paragraph(c, HARBOUR[i], 508)
        c.setLineWidth(0.6)
        c.line(M, 112, M + 90, 112)
        for j, (line, note) in enumerate(refs, 1):
            marker = str(j)
            t = c.beginText(M, y)
            t.setFont(BODY, SIZE)
            t.textOut(line)
            t.setFont(BODY, 8)
            t.setRise(5)
            t.textOut(marker)
            t.setRise(0)
            c.drawText(t)
            c.setFont(BODY, 10)
            c.drawString(M, 110 - j * 16, f"{marker}. {note}")
            notes.append({"marker": marker, "page": i, "scope": "page", "text_start": note})
            y -= 22
        c.showPage()
    c.save()
    return _labels(2) | {"notes": notes}


def rotated_crop(out: Path) -> dict:
    c = _canvas(out, "rotated-crop")
    c.setPageRotation(90)
    # ReportLab swaps the media dimensions. Counter-rotate the text for upright display.
    c.translate(H, 0)
    c.rotate(90)
    _furniture(c, "1", "Field Notes")
    _paragraph(c, RIVER[0], 500)
    c.showPage()
    c.setPageRotation(0)
    c.setCropBox((24, 16, W - 24, H - 16))
    _furniture(c, "2", "Field Notes")
    _paragraph(c, RIVER[1], 500)
    c.setFont("Helvetica", 7)
    c.drawString(M, 5, "TRIMMED PRINTER SLUG")
    c.save()
    return _labels(2) | {
        "passages": [{"page": i, "text": s} for i, s in enumerate(RIVER[:2])],
        "excluded_text": [{"page": 1, "text": "TRIMMED PRINTER SLUG"}],
    }


def zero_text_layer(out: Path) -> dict:
    c = _canvas(out, "zero-text-layer")
    _furniture(c, "1", "The Boatman's Letters")
    _paragraph(c, HARBOUR[0], 504)
    c.showPage()
    image = Image.new("L", (2 * W, 2 * H), "white")
    draw = ImageDraw.Draw(image)
    font = ImageFont.load_default(size=24)
    lines = [
        "Dear Elena,",
        "We reached the cove before sunset.",
        "The light was still on in the tower.",
        "I shall bring the ledger when I return.",
        "Yours, Paolo",
    ]
    for i, line in enumerate(lines):
        draw.text((M * 2, 200 + 54 * i), line, font=font, fill=0)
    draw.text((W, 2 * (H - 24)), "2", anchor="ms", font=font, fill=0)
    c.drawImage(ImageReader(image), 0, 0, W, H)
    c.showPage()
    _furniture(c, "3", "The Boatman's Letters")
    _paragraph(c, HARBOUR[3], 504)
    c.save()
    return _labels(3) | {"scanned_pages": [1]}


def paragraph_spanning_pages(out: Path) -> dict:
    c = _canvas(out, "paragraph-spanning-pages")
    _furniture(c, "1", "The Inland Road")
    _heading(c, "The Long Walk")
    y = 465
    for p in RIVER:
        y = _paragraph(c, p, y)
    lines = [
        "It was getting dark when Mara reached the well-known",
        "turning by the mill. She folded the letter and conti-",
        "nued along the path until the roofs came into view.",
    ]
    c.setFont(BODY, SIZE)
    c.drawString(M, 75, lines[0])
    c.drawString(M, 59, lines[1])
    c.showPage()
    _furniture(c, "2", "The Inland Road")
    c.setFont(BODY, SIZE)
    c.drawString(M, 528, lines[2])
    _paragraph(c, HARBOUR[3], 493)
    c.save()
    return _labels(2) | {
        "chapters": [_chapter("The Long Walk", 0)],
        "passages": [
            {
                "page": 0,
                "text": "It was getting dark when Mara reached the well-known turning by the mill. "
                "She folded the letter and continued along the path "
                "until the roofs came into view.",
            }
        ],
    }


def italian(out: Path) -> dict:
    c = _canvas(out, "italian", "it")
    passages = [
        "«È già sera», disse Lia. La città taceva e dal porto saliva il profumo del caffè. "
        "Sulla soglia della casa un gatto alzò la testa e poi tornò a dormire.",
        "Partì perché l'anima chiedeva un poco di silenzio. Lungo la strada non incontrò "
        "più nessuno e per la prima volta sentì il rumore dei propri passi.",
        "«Dov'è la barca?» chiese al guardiano. Lui indicò il pontile più lontano, dove "
        "una vela bianca si gonfiava appena nella brezza del mattino.",
    ]
    for i, p in enumerate(passages):
        _furniture(c, str(i + 1), "Il ritorno")
        if i == 0:
            _heading(c, "La partenza")
        _paragraph(c, p, 462 if i == 0 else 516)
        c.showPage()
    c.save()
    return _labels(3, "it") | {
        "chapters": [_chapter("La partenza", 0)],
        "passages": [{"page": i, "text": p} for i, p in enumerate(passages)],
        "critical_tokens": ["«È", "già", "caffè", "Partì", "perché", "l'anima", "più", "indicò"],
    }


def two_column(out: Path) -> dict:
    c = _canvas(out, "two-column")
    width = (W - 2 * M - 24) / 2
    columns = [
        (RIVER[:2], HARBOUR[:2]),
        (
            HARBOUR[2:],
            (
                RIVER[2],
                "The ferry carried one last passenger across. She heard the chain drop into the "
                "water and watched the lamp grow small before she closed the ledger.",
            ),
        ),
    ]
    for i, pair in enumerate(columns):
        _furniture(c, str(i + 1), "Letters from the River")
        for j, passages in enumerate(pair):
            y = 514
            for p in passages:
                y = _paragraph(c, p, y, M + j * (width + 24), width)
        c.showPage()
    c.save()
    return _labels(2) | {"two_column_pages": [0, 1]}


FIXTURES: dict[str, Callable[[Path], dict]] = {
    "bridge": bridge,
    "front-matter-roman": front_matter_roman,
    "duplicate-chapter-names": duplicate_chapter_names,
    "repeated-note-numbers": repeated_note_numbers,
    "rotated-crop": rotated_crop,
    "zero-text-layer": zero_text_layer,
    "paragraph-spanning-pages": paragraph_spanning_pages,
    "italian": italian,
    "two-column": two_column,
}


def build_all(out_dir: Path) -> dict[str, tuple[Path, dict]]:
    out_dir.mkdir(parents=True, exist_ok=True)
    books = {}
    for name, builder in FIXTURES.items():
        labels = builder(out_dir)
        (out_dir / f"{name}.expected.json").write_text(
            json.dumps(labels, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
        )
        books[name] = out_dir / f"{name}.pdf", labels
    return books


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("out_dir", nargs="?", type=Path, default=Path(__file__).parent / "out")
    build_all(parser.parse_args().out_dir)
