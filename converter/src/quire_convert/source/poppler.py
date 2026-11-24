"""Run the Poppler tools once per document and parse what they print.

Every parser returns coordinates in PDF points, in displayed orientation (page
rotation applied). ``pdftotext -cropbox`` already measures from the crop box;
``pdftohtml`` measures from the media box, so its boxes are shifted later by
``PageGeometry.crop_offset``.
"""

from __future__ import annotations

import re
import shutil
import subprocess
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from lxml import etree

from ..models import StageError

XHTML = "{http://www.w3.org/1999/xhtml}"
HTML_ZOOM = 3.0  # pdftohtml caps zoom at 3; font sizes are printed as integers of size * zoom
_SUBSET = re.compile(r"^[A-Z]{6}\+")
_BOLD = re.compile(r"bold|black|heavy|semibold|demi", re.I)
_ITALIC = re.compile(r"italic|oblique|-it$|slanted", re.I)
_PASSWORD = re.compile(r"incorrect password|password", re.I)


class PasswordRequired(Exception):
    """The PDF opens only with a user password."""


def tool_path(toolchain: Any, name: str) -> str:
    """The configured path of a Poppler tool, else the one on PATH."""
    path = getattr(toolchain, name, None) if toolchain is not None else None
    found = str(path) if path else shutil.which(name)
    if not found:
        raise StageError("preflight", f"{name} not found (install poppler-utils)")
    return found


def run(
    cmd: list[str], timeout: float, stage: str = "preflight"
) -> subprocess.CompletedProcess[str]:
    """Run a tool; raise StageError on failure or timeout, PasswordRequired on a password error."""
    name = Path(cmd[0]).name
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, check=False)
    except subprocess.TimeoutExpired as exc:
        raise StageError(stage, f"{name} timed out after {timeout:.0f}s", True) from exc
    if proc.returncode != 0:
        err = proc.stderr.strip().splitlines()
        if any(_PASSWORD.search(line) for line in err):
            raise PasswordRequired
        tail = err[-1] if err else f"exit code {proc.returncode}"
        raise StageError(stage, f"{name} failed: {tail[:200]}")
    return proc


# --------------------------------------------------------------------------- pdfinfo


@dataclass(frozen=True)
class PdfInfo:
    pages: int
    encrypted: bool
    fields: dict[str, str]


def pdfinfo(pdfinfo_bin: str, pdf: Path, timeout: float) -> PdfInfo:
    out = run([pdfinfo_bin, str(pdf)], timeout).stdout
    fields: dict[str, str] = {}
    for line in out.splitlines():
        key, sep, value = line.partition(":")
        if sep:
            fields[key.strip()] = value.strip()
    try:
        pages = int(fields.get("Pages", ""))
    except ValueError as exc:
        raise StageError("preflight", "pdfinfo reported no page count") from exc
    return PdfInfo(pages, fields.get("Encrypted", "no").startswith("yes"), fields)


_BOX_LINE = re.compile(
    r"^Page\s+(\d+)\s+(MediaBox|CropBox|rot):\s+(.*)$",
)


def page_boxes(
    pdfinfo_bin: str, pdf: Path, first: int, last: int, timeout: float, stage: str = "preflight"
) -> list[dict[str, Any]]:
    """Media box, crop box and rotation of 1-based pages first..last as Poppler sees them."""
    cmd = [pdfinfo_bin, "-f", str(first), "-l", str(last), "-box", str(pdf)]
    out = run(cmd, timeout, stage).stdout
    result: list[dict[str, Any]] = [{} for _ in range(last - first + 1)]
    for line in out.splitlines():
        m = _BOX_LINE.match(line)
        if not m or not first <= int(m.group(1)) <= last:
            continue
        entry = result[int(m.group(1)) - first]
        if m.group(2) == "rot":
            entry["rotation"] = int(float(m.group(3)))
        else:
            entry[m.group(2)] = tuple(float(v) for v in m.group(3).split())
    return result


def image_list(pdfimages_bin: str, pdf: Path, timeout: float) -> dict[int, list[tuple[int, int]]]:
    """Decoded pixel size of every image XObject, by 0-based page, without decoding them."""
    out = run([pdfimages_bin, "-list", str(pdf)], timeout).stdout
    result: dict[int, list[tuple[int, int]]] = {}
    for line in out.splitlines()[2:]:
        parts = line.split()
        if len(parts) > 4 and parts[0].isdigit() and parts[3].isdigit() and parts[4].isdigit():
            result.setdefault(int(parts[0]) - 1, []).append((int(parts[3]), int(parts[4])))
    return result


# --------------------------------------------------------------------------- pdftotext


@dataclass
class RawWord:
    text: str
    box: tuple[float, float, float, float]


@dataclass
class RawLine:
    words: list[RawWord]
    box: tuple[float, float, float, float]


@dataclass
class RawBlock:
    lines: list[RawLine]
    box: tuple[float, float, float, float]


def _bbox(el: Any) -> tuple[float, float, float, float]:
    return (
        float(el.get("xMin")),
        float(el.get("yMin")),
        float(el.get("xMax")),
        float(el.get("yMax")),
    )


def run_pdftotext(pdftotext_bin: str, pdf: Path, out: Path, timeout: float) -> Path:
    run([pdftotext_bin, "-bbox-layout", "-cropbox", "-enc", "UTF-8", str(pdf), str(out)], timeout)
    return out


def parse_bbox_layout(path: Path) -> list[list[RawBlock]]:
    """Blocks per page in Poppler's reading order, crop-box points, displayed orientation."""
    parser = etree.XMLParser(recover=True, huge_tree=True, resolve_entities=False)
    root = etree.parse(str(path), parser).getroot()
    pages: list[list[RawBlock]] = []
    for page in root.iter(f"{XHTML}page"):
        blocks: list[RawBlock] = []
        for block in page.iter(f"{XHTML}block"):
            lines = []
            for line in block.iter(f"{XHTML}line"):
                words = [
                    RawWord(w.text or "", _bbox(w))
                    for w in line.iter(f"{XHTML}word")
                    if (w.text or "").strip()
                ]
                if words:
                    lines.append(RawLine(words, _bbox(line)))
            if lines:
                blocks.append(RawBlock(lines, _bbox(block)))
        pages.append(blocks)
    return pages


# --------------------------------------------------------------------------- pdftohtml


@dataclass
class Segment:
    text: str
    bold: bool
    italic: bool
    x0: float  # estimated from the run's width, proportional to characters
    x1: float


@dataclass
class TextRun:
    box: tuple[float, float, float, float]  # media-box points, displayed orientation
    font: str | None
    size: float | None
    segments: list[Segment]


@dataclass
class HtmlPage:
    runs: list[TextRun] = field(default_factory=list)


def run_pdftohtml(pdftohtml_bin: str, pdf: Path, outdir: Path, timeout: float) -> Path:
    outdir.mkdir(parents=True, exist_ok=True)
    prefix = outdir / "doc"
    # -i: extracting images re-encodes every one of them (seconds per scanned page);
    # placements come from the content stream instead (structure.image_placements).
    cmd = [pdftohtml_bin, "-xml", "-i", "-fontfullname", "-noroundcoord", "-q", "-nodrm"]
    cmd += ["-zoom", str(HTML_ZOOM), str(pdf), str(prefix)]
    run(cmd, timeout)
    return prefix.with_suffix(".xml")


def _segments(el: Any, bold: bool, italic: bool) -> list[tuple[str, bool, bool]]:
    tag = el.tag if isinstance(el.tag, str) else ""
    bold = bold or tag == "b"
    italic = italic or tag == "i"
    parts = [(el.text, bold, italic)] if el.text else []
    for child in el:
        parts.extend(_segments(child, bold, italic))
        if child.tail:
            parts.append((child.tail, bold, italic))
    return parts


def _signed_box(top: float, left: float, width: float, height: float, zoom: float):
    xs = sorted((left / zoom, (left + width) / zoom))
    ys = sorted((top / zoom, (top + height) / zoom))
    return (xs[0], ys[0], xs[1], ys[1])


def parse_pdftohtml(path: Path) -> list[HtmlPage]:
    """Text runs with font facts per page."""
    parser = etree.XMLParser(recover=True, huge_tree=True, resolve_entities=False)
    root = etree.parse(str(path), parser).getroot()
    fonts: dict[str, tuple[str, float]] = {}
    pages: list[HtmlPage] = []
    for page_el in root.iter("page"):
        page = HtmlPage()
        for el in page_el:
            if el.tag == "fontspec":
                family = _SUBSET.sub("", el.get("family") or "")
                fonts[el.get("id", "")] = (family, float(el.get("size") or 0) / HTML_ZOOM)
            elif el.tag == "text":
                run_ = _text_run(el, fonts)
                if run_:
                    page.runs.append(run_)
        pages.append(page)
    return pages


def _geometry(el: Any) -> tuple[float, float, float, float]:
    return tuple(float(el.get(k) or 0) for k in ("top", "left", "width", "height"))  # type: ignore[return-value]


def _text_run(el: Any, fonts: dict[str, tuple[str, float]]) -> TextRun | None:
    family, size = fonts.get(el.get("font", ""), (None, None))
    box = _signed_box(*_geometry(el), HTML_ZOOM)
    parts = [p for p in _segments(el, False, False) if p[0]]
    total = sum(len(p[0]) for p in parts)
    if not total:
        return None
    font_bold = bool(family and _BOLD.search(family))
    font_italic = bool(family and _ITALIC.search(family))
    segments, pos = [], 0
    width = box[2] - box[0]
    for text, bold, italic in parts:
        x0 = box[0] + width * pos / total
        pos += len(text)
        x1 = box[0] + width * pos / total
        segments.append(Segment(text, bold or font_bold, italic or font_italic, x0, x1))
    return TextRun(box, family or None, size or None, segments)


# --------------------------------------------------------------------------- pdftoppm


def render_pages(
    pdftoppm_bin: str, pdf: Path, outdir: Path, dpi: int, first: int, last: int, timeout: float
) -> dict[int, Path]:
    """Render 1-based pages first..last to grayscale PGMs; returns {0-based page: path}."""
    outdir.mkdir(parents=True, exist_ok=True)
    cmd = [pdftoppm_bin, "-cropbox", "-gray", "-r", str(dpi), "-f", str(first), "-l", str(last)]
    run([*cmd, str(pdf), str(outdir / "p")], timeout)
    result = {}
    for path in outdir.glob("p-*.pgm"):
        num = path.stem.rsplit("-", 1)[-1]
        if num.isdigit() and first <= int(num) <= last:
            result[int(num) - 1] = path
    return result
