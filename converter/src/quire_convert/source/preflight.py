"""Independent source inventory and preflight of one PDF.

Each Poppler tool runs once over the whole document (in parallel): ``pdftotext
-bbox-layout`` for words, ``pdftohtml -xml`` for fonts and placed images and
``pdftoppm`` for a low-resolution render that is compared with both. pypdf reads
the PDF's own structure. The result answers ``inspect`` and seeds the source map.
"""

from __future__ import annotations

import hashlib
import logging
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from ..models import (
    Box,
    ImageRegion,
    Line,
    PageInventory,
    SourceInventory,
    StageError,
    TextBlock,
    Word,
)
from . import ink, language, layout, poppler
from .structure import PageGeometry, Structure, geometry_from_boxes, read_structure

log = logging.getLogger(__name__)

REFERENCE_DPI = 300  # highest resolution later stages render crops at
MIN_RASTER_AREA = 0.0005  # smaller placed images (rules, spacers) are not regions
LARGE_RASTER = 0.5  # page share of a raster that makes a page a scan when it has no text
RENDER_WORKERS = 4  # parallel pdftoppm processes for long documents
RENDER_CHUNK = 50  # minimum pages per pdftoppm process


@dataclass
class _Signals:
    """Per-page measurements that only feed document-level complexity."""

    stats: layout.CharStats
    columns: int
    unexplained: list[Box]
    vertical_words: int
    words: int


@dataclass(frozen=True)
class Limits:
    max_bytes: int = 300 * 1024 * 1024
    max_pages: int = 3000
    max_decoded_pixels: int = 120_000_000  # one embedded image, or one page at REFERENCE_DPI
    tool_timeout_s: float = 600.0


def inspect_pdf(
    pdf: Path, workdir: Path, limits: Limits | None = None, toolchain: Any = None
) -> SourceInventory:
    """Inventory every page of ``pdf`` and decide whether reflow is in scope.

    Raises ``StageError("preflight", ...)`` for input that cannot be read. A PDF
    that needs a user password returns an inventory with ``needs_password``.
    """
    started = time.monotonic()
    limits = limits or Limits()
    pdf = Path(pdf)
    if not pdf.is_file():
        raise StageError("preflight", "input PDF not found")
    tools = {n: poppler.tool_path(toolchain, n) for n in ("pdfinfo", "pdfimages")}
    size = pdf.stat().st_size
    inv = SourceInventory(
        path=str(pdf),
        sha256=_sha256(pdf),
        page_count=0,
        encrypted=False,
        needs_password=False,
        info={},
        xmp={},
        language=None,
        pages=[],
        bookmarks=[],
        named_dests={},
        complexity={},
        unsupported=[],
        resource={
            "file_bytes": size,
            "max_bytes": limits.max_bytes,
            "max_pages": limits.max_pages,
            "max_decoded_pixels": limits.max_decoded_pixels,
            "tool_timeout_s": limits.tool_timeout_s,
        },
    )
    timeout = limits.tool_timeout_s
    try:
        info = poppler.pdfinfo(tools["pdfinfo"], pdf, timeout)
    except poppler.PasswordRequired:
        inv.encrypted = inv.needs_password = True
        inv.unsupported.append("password-protected PDF")
        return _finish(inv, started)
    inv.page_count = info.pages
    inv.encrypted = info.encrypted
    inv.resource["pages"] = info.pages
    if info.pages < 1:
        raise StageError("preflight", "PDF has no pages")
    if size > limits.max_bytes:
        inv.unsupported.append(
            f"file is {size / 2**20:.0f} MiB, over the {limits.max_bytes / 2**20:.0f} MiB limit"
        )
    if info.pages > limits.max_pages:
        inv.unsupported.append(f"exceeds page limit ({info.pages} pages, limit {limits.max_pages})")
    if inv.unsupported:
        return _finish(inv, started)

    geometry = geometry_from_boxes(
        poppler.page_boxes(tools["pdfinfo"], pdf, 1, info.pages, timeout)
    )
    images = poppler.image_list(tools["pdfimages"], pdf, timeout)
    if _over_pixel_limit(inv, geometry, images, limits):
        return _finish(inv, started)

    structure = read_structure(pdf, geometry, set(images))
    inv.info, inv.xmp = structure.info, structure.xmp
    inv.bookmarks, inv.named_dests = structure.bookmarks, structure.named_dests
    pages = _inventory_pages(pdf, workdir, geometry, structure, toolchain, timeout)
    inv.pages = [page for page, _ in pages]
    _document_signals(inv, structure, [signals for _, signals in pages])
    return _finish(inv, started)


def _sha256(pdf: Path) -> str:
    digest = hashlib.sha256()
    with pdf.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _finish(inv: SourceInventory, started: float) -> SourceInventory:
    inv.resource["elapsed_s"] = round(time.monotonic() - started, 2)
    log.info(
        "preflight pages=%d unsupported=%d elapsed=%.1fs",
        inv.page_count,
        len(inv.unsupported),
        inv.resource["elapsed_s"],
    )
    return inv


def _over_pixel_limit(
    inv: SourceInventory,
    geometry: list[PageGeometry],
    images: dict[int, list[tuple[int, int]]],
    limits: Limits,
) -> bool:
    scale = REFERENCE_DPI / 72
    page_px = max(round(g.width * scale) * round(g.height * scale) for g in geometry)
    image_px = max((w * h for sizes in images.values() for w, h in sizes), default=0)
    inv.resource["max_render_pixels"] = page_px
    inv.resource["render_reference_dpi"] = REFERENCE_DPI
    inv.resource["max_image_pixels"] = image_px
    if page_px > limits.max_decoded_pixels:
        inv.unsupported.append(
            f"page too large to render at {REFERENCE_DPI} dpi ({page_px} pixels, "
            f"limit {limits.max_decoded_pixels})"
        )
    if image_px > limits.max_decoded_pixels:
        inv.unsupported.append(
            f"embedded image too large to decode ({image_px} pixels, "
            f"limit {limits.max_decoded_pixels})"
        )
    return bool(inv.unsupported)


# --------------------------------------------------------------------------- pages


def _inventory_pages(
    pdf: Path,
    workdir: Path,
    geometry: list[PageGeometry],
    structure: Structure,
    toolchain: Any,
    timeout: float,
) -> list[tuple[PageInventory, _Signals]]:
    out = Path(workdir) / "preflight"
    out.mkdir(parents=True, exist_ok=True)
    bins = {n: poppler.tool_path(toolchain, n) for n in ("pdftotext", "pdftohtml", "pdftoppm")}
    n = len(geometry)
    # Rendering dominates on image-heavy books, so it is split into page ranges.
    chunk = max(RENDER_CHUNK, -(-n // RENDER_WORKERS))
    ranges = [(first, min(n, first + chunk - 1)) for first in range(1, n + 1, chunk)]
    with ThreadPoolExecutor(max_workers=2 + len(ranges)) as pool:
        text_job = pool.submit(
            poppler.run_pdftotext, bins["pdftotext"], pdf, out / "words.xhtml", timeout
        )
        html_job = pool.submit(poppler.run_pdftohtml, bins["pdftohtml"], pdf, out / "html", timeout)
        render_jobs = [
            pool.submit(
                poppler.render_pages,
                bins["pdftoppm"],
                pdf,
                out / "lowres",
                ink.RENDER_DPI,
                first,
                last,
                timeout,
            )
            for first, last in ranges
        ]
        try:
            words = poppler.parse_bbox_layout(text_job.result())
            html = poppler.parse_pdftohtml(html_job.result())
            renders = {k: v for job in render_jobs for k, v in job.result().items()}
        except poppler.PasswordRequired as exc:
            raise StageError("preflight", "PDF requires a password") from exc
    pages = []
    for index, geom in enumerate(geometry):
        label = (
            structure.labels[index] if structure.labels and index < len(structure.labels) else None
        )
        pages.append(
            _page(
                index,
                geom,
                label,
                words[index] if index < len(words) else [],
                html[index] if index < len(html) else poppler.HtmlPage(),
                structure.images.get(index, []),
                renders.get(index),
            )
        )
    return pages


def _norm(box: tuple[float, float, float, float], geom: PageGeometry, dx=0.0, dy=0.0) -> Box:
    w, h = geom.width or 1.0, geom.height or 1.0

    def clamp(v: float) -> float:
        return round(min(1.0, max(0.0, v)), 5)

    return Box(
        clamp((box[0] - dx) / w),
        clamp((box[1] - dy) / h),
        clamp((box[2] - dx) / w),
        clamp((box[3] - dy) / h),
    )


class _FontIndex:
    """Text-run segments bucketed by vertical band for fast overlap lookups."""

    BANDS = 100

    def __init__(self, page: poppler.HtmlPage, geom: PageGeometry):
        dx, dy = geom.crop_offset
        self.bands: list[list[tuple[Box, poppler.TextRun, poppler.Segment]]] = [
            [] for _ in range(self.BANDS)
        ]
        for run in page.runs:
            for seg in run.segments:
                box = _norm((seg.x0, run.box[1], seg.x1, run.box[3]), geom, dx, dy)
                if box.width <= 0 or box.height <= 0:
                    continue
                lo, hi = self._band(box.y0), self._band(box.y1)
                for band in range(lo, hi + 1):
                    self.bands[band].append((box, run, seg))

    def _band(self, y: float) -> int:
        return min(self.BANDS - 1, max(0, int(y * self.BANDS)))

    def word(self, text: str, box: Box) -> Word:
        best, best_area = None, 0.3 * box.area
        for seg_box, run, seg in self.bands[self._band((box.y0 + box.y1) / 2)]:
            area = seg_box.intersection_area(box)
            if area > best_area:
                best, best_area = (run, seg), area
        if best is None:
            return Word(text, box)
        run, seg = best
        size = round(run.size, 2) if run.size else None
        return Word(text, box, font_size=size, bold=seg.bold, italic=seg.italic, font=run.font)


def _page(
    index: int,
    geom: PageGeometry,
    label: str | None,
    raw_blocks: list[poppler.RawBlock],
    html: poppler.HtmlPage,
    placements: list,
    render: Path | None,
) -> tuple[PageInventory, _Signals]:
    fonts = _FontIndex(html, geom)
    blocks: list[TextBlock] = []
    lines: list[Line] = []
    for raw in raw_blocks:
        block_lines = []
        for raw_line in raw.lines:
            words = [fonts.word(w.text, _norm(w.box, geom)) for w in raw_line.words]
            block_lines.append(Line(words, _norm(raw_line.box, geom)))
        lines.extend(block_lines)
        blocks.append(
            TextBlock(f"s{index}-b{len(blocks)}", index, block_lines, _norm(raw.box, geom))
        )
    all_words = [w for line in lines for w in line.words]

    folios = layout.folio_candidates(lines)
    for block in blocks:
        if layout.in_margin(block.box) and len(block.lines) == 1:
            words = block.lines[0].words
            if len(words) == 1 and layout.folio_text(words[0].text) in folios:
                block.role = "folio"

    rasters = _rasters(index, placements, geom)
    result = (
        ink.analyze(
            render, [w.box for w in all_words], [ln.box for ln in lines], [r.box for r in rasters]
        )
        if render
        else ink.InkResult()
    )
    vectors = _grow_regions(result.regions, result.unexplained, lines)
    images = rasters + [
        ImageRegion(f"s{index}-i{len(rasters) + k}", index, box, "vector")
        for k, box in enumerate(vectors)
    ]
    text = "\n".join(b.text for b in blocks)
    stats = layout.char_stats(text)
    columns = layout.column_count([ln.box for ln in lines])
    page = PageInventory(
        index=index,
        label=label,
        folio_candidates=folios,
        width_pt=round(geom.width, 3),
        height_pt=round(geom.height, 3),
        rotation=geom.rotation,
        crop_box=geom.crop_box,
        media_box=geom.media_box,
        blocks=blocks,
        images=images,
        text_source="native" if all_words else "none",
        ink_coverage=result.ink_coverage,
        text_coverage=result.text_coverage,
        page_type="text",
        flags=[],
    )
    _classify(page, all_words, rasters, vectors, result, stats, columns)
    signals = _Signals(
        stats, columns, result.unexplained, _vertical_words(all_words), len(all_words)
    )
    return page, signals


def _rasters(index: int, placements: list, geom: PageGeometry) -> list[ImageRegion]:
    regions: list[ImageRegion] = []
    for rect, width, height in placements:
        box = Box(*geom.normalize(rect))
        if box.area < MIN_RASTER_AREA or min(box.width, box.height) < 0.005:
            continue
        regions.append(
            ImageRegion(f"s{index}-i{len(regions)}", index, box, "raster", width, height)
        )
    return regions


def _contains(outer: Box, inner: Box) -> bool:
    return (
        outer.x0 <= inner.x0
        and outer.y0 <= inner.y0
        and inner.x1 <= outer.x1
        and inner.y1 <= outer.y1
    )


def _grow_regions(regions: list[Box], parts: list[Box], lines: list[Line]) -> list[Box]:
    """Join the parts of one drawing (thin strokes included), pull in its labels, join again."""
    regions = _merge_near(regions, lines)
    loose = [p for p in parts if not any(_contains(r, p) for r in regions)]
    changed = True
    while changed and loose:
        changed = False
        for k, region in enumerate(regions):
            near = [p for p in loose if _joinable(region, p, lines)]
            for part in near:
                region = region.union(part)
                loose.remove(part)
            if near:
                regions[k], changed = region, True
    grown = [_with_labels(r, lines) for r in _merge_near(regions, lines)]
    return sorted(_merge_near(grown, lines), key=lambda b: (b.y0, b.x0))


def _with_labels(region: Box, lines: list[Line], near: float = 0.01) -> Box:
    """Grow a drawing's box over short lines inside or touching it (labels, not captions)."""
    for _ in range(4):
        before = region
        halo = Box(region.x0 - near, region.y0 - near, region.x1 + near, region.y1 + near)
        for line in lines:
            b = line.box
            if _contains(region, b) or halo.intersection_area(b) <= 0:
                continue
            inside = region.intersection_area(b) >= 0.6 * b.area
            label = len(line.words) <= 3 and b.width <= 0.5 * region.width
            if inside or label:
                region = region.union(b)
        if region == before:
            break
    return region


def _gap(a: Box, b: Box) -> float:
    return max(b.x0 - a.x1, a.x0 - b.x1, b.y0 - a.y1, a.y0 - b.y1, 0.0)


def _joinable(a: Box, b: Box, lines: list[Line]) -> bool:
    """Overlapping, or close relative to their size with no running text between them."""
    gap = _gap(a, b)
    if gap > max(0.03, 0.35 * min(max(a.width, a.height), max(b.width, b.height))):
        return False
    if gap == 0:
        return True
    span = a.union(b)
    return not any(
        len(line.words) >= 4
        and span.intersection_area(line.box) > 0
        and not (_contains(a, line.box) or _contains(b, line.box))
        for line in lines
    )


def _merge_near(boxes: list[Box], lines: list[Line]) -> list[Box]:
    boxes = list(boxes)
    merged = True
    while merged:
        merged = False
        for i in range(len(boxes)):
            for j in range(i + 1, len(boxes)):
                if _joinable(boxes[i], boxes[j], lines):
                    boxes[i] = boxes[i].union(boxes.pop(j))
                    merged = True
                    break
            if merged:
                break
    return boxes


def _vertical_words(words: list[Word]) -> int:
    return sum(1 for w in words if len(w.text) >= 2 and w.box.height > 1.5 * w.box.width > 0)


def _classify(
    page: PageInventory,
    words: list[Word],
    rasters: list[ImageRegion],
    vectors: list[Box],
    result: ink.InkResult,
    stats: layout.CharStats,
    columns: int,
) -> None:
    flags = page.flags
    if page.rotation:
        flags.append("rotated")
    if any(abs(a - b) > 0.5 for a, b in zip(page.crop_box, page.media_box, strict=True)):
        flags.append("cropped")
    large_raster = any(r.box.area >= LARGE_RASTER for r in rasters)
    art = min(1.0, sum(r.box.area for r in rasters) + sum(v.area for v in vectors))
    if not words:
        if large_raster:
            page.page_type = "scan"
        elif rasters or vectors or page.ink_coverage >= 0.01:
            page.page_type = "image"
        else:
            page.page_type = "blank"
        if page.ink_coverage >= 0.002:
            flags.append("zero_text_layer")
        return
    if rasters or vectors:
        page.page_type = "image" if art >= 0.5 and len(words) < 80 else "mixed"
    if large_raster:
        flags.append("image_backed_text")
    broken_chars = stats.chars >= 20 and stats.bad >= 0.05 * stats.chars
    invisible = result.lines_checked >= 3 and result.lines_without_ink >= 0.5 * result.lines_checked
    if broken_chars or invisible:
        flags.append("broken_text_layer")
    if columns >= 2:
        flags.append("multi_column")
    if len(words) >= 3 and _vertical_words(words) >= 0.3 * len(words):
        flags.append("vertical_text")


# --------------------------------------------------------------------------- document


def _document_signals(inv: SourceInventory, structure: Structure, signals: list[_Signals]) -> None:
    pages = inv.pages
    total = layout.CharStats()
    for s in signals:
        total.add(s.stats)
    text_pages = [p for p in pages if p.text_source == "native"]
    n_text = len(text_pages)
    n = len(pages)
    multi = [p.index for p in pages if "multi_column" in p.flags]
    scans = [p.index for p in pages if p.page_type == "scan"]
    broken = [p.index for p in pages if "broken_text_layer" in p.flags]
    words = sum(s.words for s in signals)
    vertical = sum(s.vertical_words for s in signals)
    math_pages = [
        p.index
        for p, s in zip(pages, signals, strict=True)
        if s.stats.math >= 10 and s.stats.math >= 0.03 * s.stats.chars
    ]

    tokens = language.tokens(b.text for p in pages for b in p.blocks)
    detected, scores = language.detect(tokens)
    declared = [d for d in (structure.catalog_lang, *_xmp_langs(inv.xmp)) if d]
    inv.language = language.choose(detected, scores, len(tokens), declared)

    letters = total.letters or 1
    inv.complexity = {
        "columns": [s.columns for s in signals],
        "multi_column_pages": multi,
        "multi_column_share": _share(len(multi), n_text),
        "scanned_pages": scans,
        "scanned_share": _share(len(scans), n),
        "image_backed_text_pages": [p.index for p in pages if "image_backed_text" in p.flags],
        "broken_text_layer_pages": broken,
        "zero_text_layer_pages": [p.index for p in pages if "zero_text_layer" in p.flags],
        "vector_region_pages": [
            p.index for p in pages if any(i.kind == "vector" for i in p.images)
        ],
        "rtl_share": _share(total.rtl, letters),
        "cjk_share": _share(total.cjk, letters),
        "vertical_text_share": _share(vertical, words),
        "math_density": _share(total.math, total.chars),
        "math_pages": math_pages,
        "mean_words_per_page": round(words / n, 1) if n else 0.0,
        "page_types": _count(p.page_type for p in pages),
        "unexplained_ink": {
            str(p.index): [[round(v, 4) for v in (b.x0, b.y0, b.x1, b.y1)] for b in s.unexplained]
            for p, s in zip(pages, signals, strict=True)
            if s.unexplained
        },
        "language": {
            "detected": detected,
            "scores": scores,
            "tokens": len(tokens),
            "declared": declared,
        },
        "structure_readable": structure.readable,
    }
    inv.unsupported.extend(_reasons(inv.complexity, n, n_text, len(broken), math_pages))


def _reasons(c: dict[str, Any], n: int, n_text: int, broken: int, math_pages: list[int]) -> list:
    reasons = []
    scans = len(c["scanned_pages"])
    if scans and scans >= 0.5 * n:
        reasons.append(f"mostly scanned pages ({scans} of {n}) need OCR")
    if n_text and broken >= max(2, 0.3 * n_text):
        reasons.append(f"broken text layer on {broken} of {n_text} text pages")
    multi = len(c["multi_column_pages"])
    if n_text and multi >= max(2, 0.25 * n_text):
        reasons.append(f"multi-column layout on {multi} of {n_text} text pages")
    if c["rtl_share"] >= 0.2:
        reasons.append("right-to-left script")
    if c["cjk_share"] >= 0.2 and c["vertical_text_share"] >= 0.3:
        reasons.append("vertical writing")
    if c["math_density"] >= 0.02 or (n_text and len(math_pages) >= max(3, 0.15 * n_text)):
        reasons.append("equation-heavy")
    return reasons


def _xmp_langs(xmp: dict[str, Any]) -> list[str]:
    value = xmp.get("dc_language")
    return [str(v) for v in value] if isinstance(value, list) else [str(value)] if value else []


def _share(part: int, whole: int) -> float:
    return round(part / whole, 4) if whole else 0.0


def _count(values: Any) -> dict[str, int]:
    out: dict[str, int] = {}
    for v in values:
        out[v] = out.get(v, 0) + 1
    return out
