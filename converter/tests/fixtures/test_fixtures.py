import json
import subprocess
from pathlib import Path
from xml.etree import ElementTree

import pytest
from pypdf import PdfReader

from .build import build_all

Books = dict[str, tuple[Path, dict]]
COUNTS = {
    "bridge": 4,
    "front-matter-roman": 7,
    "duplicate-chapter-names": 4,
    "repeated-note-numbers": 2,
    "rotated-crop": 2,
    "zero-text-layer": 3,
    "paragraph-spanning-pages": 2,
    "italian": 3,
    "two-column": 2,
}


def _extract(path: Path, page: int, *options: str) -> str:
    number = str(page + 1)
    return subprocess.run(
        ["pdftotext", "-f", number, "-l", number, "-enc", "UTF-8", *options, str(path), "-"],
        check=True,
        capture_output=True,
        encoding="utf-8",
    ).stdout


def test_artifacts(fixture_pdfs: Books, tmp_path: Path) -> None:
    assert fixture_pdfs.keys() == COUNTS.keys()
    rebuilt = build_all(tmp_path)
    build_all(tmp_path)  # Repeat over existing artifacts as well as in a fresh directory.
    for name, (path, labels) in fixture_pdfs.items():
        assert COUNTS[name] == labels["pages"] == len(PdfReader(path).pages)
        assert len(labels["page_labels"]) == labels["pages"]
        other, other_labels = rebuilt[name]
        assert other.read_bytes() == path.read_bytes()
        expected = path.with_suffix(".expected.json").read_bytes()
        assert expected == other.with_suffix(".expected.json").read_bytes()
        assert json.loads(expected) == labels == other_labels


def test_front_matter(fixture_pdfs: Books) -> None:
    path, labels = fixture_pdfs["front-matter-roman"]
    reader = PdfReader(path)
    assert reader.page_labels == labels["page_labels"] == ["i", "ii", "iii", "1", "2", "3", "4"]
    assert reader.metadata.title == "Microsoft Word - draft3.docx"
    assert reader.metadata.author == "Administrator"
    assert reader.metadata.creator == "Microsoft Word"
    first, wrong = reader.outline
    assert reader.get_destination_page_number(first) == 3
    (trap,) = labels["bookmark_traps"]
    assert wrong.title == trap["title"]
    assert reader.get_destination_page_number(wrong) == trap["bookmark_page"] == 4
    assert wrong.title in reader.pages[trap["chapter_page"]].extract_text()
    copyright_text = reader.pages[1].extract_text()
    isbn13, isbn10 = labels["metadata"]["isbn"]
    assert isbn13 in copyright_text and isbn10 in copyright_text
    assert isbn13[3:-1] == isbn10[:-1]
    assert sum(int(d) * w for d, w in zip(isbn13, [1, 3] * 6 + [1], strict=True)) % 10 == 0
    assert sum((10 if d == "X" else int(d)) * (10 - i) for i, d in enumerate(isbn10)) % 11 == 0


@pytest.mark.tools
def test_bridge(fixture_pdfs: Books) -> None:
    path, labels = fixture_pdfs["bridge"]
    reader = PdfReader(path)
    assert not reader.outline and "/PageLabels" not in reader.root_object
    assert not reader.pages[2].images
    pages = [_extract(path, i, "-layout") for i in range(4)]
    for i, title in enumerate(("Chapter One: The River", "Chapter Two: The Bridge"), 1):
        assert f"{title} ..... {i + 1}" in pages[0]
        assert title in pages[i]
    assert "Index ..... 4" in pages[0]
    assert "Figure 1. The old bridge at dawn." in pages[2]
    assert all(term in pages[3] for term in ("bridge, 3", "dawn, 3", "river, 2"))
    for i, page in enumerate(pages, 1):
        assert page.strip().splitlines()[-1].strip() == str(i)
    # Check both separately drawn letters actually fit inside the gold crop.
    doc = ElementTree.fromstring(_extract(path, 2, "-bbox"))
    page = doc.find(".//{*}page")
    w, h = float(page.attrib["width"]), float(page.attrib["height"])
    left, top, right, bottom = labels["figures"][0]["box"]
    letters = [word for word in page.iterfind(".//{*}word") if word.text in ("A", "B")]
    assert [word.text for word in letters] == ["A", "B"]
    for word in letters:
        b = word.attrib
        assert left <= float(b["xMin"]) / w < float(b["xMax"]) / w <= right
        assert top <= float(b["yMin"]) / h < float(b["yMax"]) / h <= bottom


@pytest.mark.tools
def test_scan_and_crop(fixture_pdfs: Books) -> None:
    path, labels = fixture_pdfs["zero-text-layer"]
    (scanned,) = labels["scanned_pages"]
    assert _extract(path, scanned, "-layout").strip() == ""
    assert len(PdfReader(path).pages[scanned].images) == 1
    for i in (0, 2):
        assert _extract(path, i, "-layout").strip()

    path, labels = fixture_pdfs["rotated-crop"]
    rotated, cropped = PdfReader(path).pages
    assert rotated.rotation == 90
    assert cropped.cropbox != cropped.mediabox
    for i, passage in enumerate(labels["passages"]):
        assert passage["text"] in " ".join(_extract(path, i, "-layout", "-cropbox").split())
    slug = labels["excluded_text"][0]["text"]
    assert slug in cropped.extract_text()
    visible = ElementTree.fromstring(_extract(path, 1, "-bbox", "-cropbox"))
    words = [word.text for word in visible.iterfind(".//{*}word")]
    assert "ledger" in words and not set(slug.split()).intersection(words)


@pytest.mark.tools
def test_text_traps(fixture_pdfs: Books) -> None:
    path, labels = fixture_pdfs["duplicate-chapter-names"]
    header = labels["running_headers"][0]["text"]
    for chapter in labels["chapters"]:
        lines = [s.strip() for s in _extract(path, chapter["page"], "-layout").splitlines()]
        assert lines.count(chapter["title"]) == (2 if chapter["title"] == header else 1)

    path, labels = fixture_pdfs["repeated-note-numbers"]
    assert [(n["page"], n["marker"]) for n in labels["notes"]] == [(0, "1"), (1, "1"), (1, "2")]
    for note in labels["notes"]:
        assert f"{note['marker']}. {note['text_start']}" in _extract(path, note["page"], "-layout")

    path, labels = fixture_pdfs["paragraph-spanning-pages"]
    a, b = (
        [s.strip() for s in _extract(path, i, "-layout").splitlines() if s.strip()] for i in (0, 1)
    )
    assert a[-3].endswith("well-known") and a[-2].endswith("conti-") and b[1].startswith("nued")
    assert a[-3] + " " + a[-2][:-1] + b[1] == labels["passages"][0]["text"]

    path, labels = fixture_pdfs["italian"]
    for i, p in enumerate(labels["passages"]):
        assert p["text"] in " ".join(_extract(path, i, "-layout").split())


@pytest.mark.tools
def test_columns(fixture_pdfs: Books) -> None:
    path, _ = fixture_pdfs["two-column"]
    for i in (0, 1):
        doc = ElementTree.fromstring(_extract(path, i, "-bbox"))
        page = doc.find(".//{*}page")
        center = float(page.attrib["width"]) / 2
        body = [w.attrib for w in page.iterfind(".//{*}word") if 70 < float(w.attrib["yMin"]) < 500]
        left = [b for b in body if float(b["xMax"]) < center]
        right = [b for b in body if float(b["xMin"]) > center]
        assert len(left) + len(right) == len(body)
        assert len({b["yMin"] for b in left} & {b["yMin"] for b in right}) >= 8
