"""Compare a low-resolution render with the text and image boxes Poppler reported.

Ink that no word box or raster image explains is drawn art, a broken text
layer, or marginalia. Connected areas of it become candidate vector figures;
image-object counts alone cannot see them.
"""

from __future__ import annotations

from collections import deque
from dataclasses import dataclass, field
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter

from ..models import Box

RENDER_DPI = 50
INK_THRESHOLD = 200  # gray level below which a pixel counts as ink (catches thin antialiased lines)
_INK_LUT = [255 if v < INK_THRESHOLD else 0 for v in range(256)]
TEXT_PAD = 0.2  # word boxes grow by this share of their height before explaining ink
MERGE_CELLS = 7  # max filter size on the cell grid: joins parts of a drawing split by labels

# A component becomes a vector region when its box is at least this big (page shares).
MIN_REGION_SIDE = 0.02
MIN_REGION_AREA = 0.003
MIN_REGION_INK = 0.0004  # unexplained ink pixels / page pixels


@dataclass
class InkResult:
    ink_coverage: float = 0.0
    text_coverage: float = 0.0
    unexplained: list[Box] = field(default_factory=list)  # every merged component, raw
    regions: list[Box] = field(default_factory=list)  # components big enough to be art
    lines_checked: int = 0
    lines_without_ink: int = 0


def _rect(box: Box, w: int, h: int, pad: float = 0.0) -> tuple[int, int, int, int]:
    return (
        max(0, int(box.x0 * w - pad)),
        max(0, int(box.y0 * h - pad)),
        min(w - 1, int(box.x1 * w + pad + 0.999)),
        min(h - 1, int(box.y1 * h + pad + 0.999)),
    )


def _count(img: Image.Image) -> int:
    return img.histogram()[255]


def analyze(
    render: Path,
    words: list[Box],
    lines: list[Box],
    rasters: list[Box],
) -> InkResult:
    """Ink coverage, text coverage and unexplained-ink regions of one rendered page."""
    with Image.open(render) as src:
        ink = src.convert("L").point(_INK_LUT)
    w, h = ink.size
    total_ink = _count(ink)
    result = InkResult(ink_coverage=round(total_ink / (w * h), 5))

    text_mask = Image.new("L", (w, h), 0)
    draw = ImageDraw.Draw(text_mask)
    for box in words:
        draw.rectangle(_rect(box, w, h, max(1.0, TEXT_PAD * box.height * h)), fill=255)
    if total_ink:
        explained = _count(ImageChops.darker(ink, text_mask))
        result.text_coverage = round(explained / total_ink, 4)

    for box in lines:
        if box.width <= 0 or box.height <= 0:
            continue
        result.lines_checked += 1
        if ink.crop(_rect(box, w, h)).getbbox() is None:
            result.lines_without_ink += 1

    for box in rasters:
        draw.rectangle(_rect(box, w, h, 1), fill=255)
    unexplained = ImageChops.subtract(ink, text_mask)
    if _count(unexplained) == 0:
        return result
    _components(unexplained, result)
    return result


def _components(unexplained: Image.Image, result: InkResult) -> None:
    w, h = unexplained.size
    cell = max(2, round(min(w, h) / 120))
    grid = unexplained.reduce(cell).point(lambda v: 255 if v > 0 else 0)
    merged = grid.filter(ImageFilter.MaxFilter(MERGE_CELLS))
    gw, gh = grid.size
    on = grid.tobytes()
    reach = bytearray(merged.tobytes())
    page_px = w * h
    for start in range(gw * gh):
        if not reach[start]:
            continue
        reach[start] = 0
        queue = deque([start])
        x0 = y0 = 1 << 30
        x1 = y1 = -1
        while queue:
            i = queue.popleft()
            cy, cx = divmod(i, gw)
            if on[i]:
                x0, y0, x1, y1 = min(x0, cx), min(y0, cy), max(x1, cx), max(y1, cy)
            for j in (i - 1 if cx else -1, i + 1 if cx + 1 < gw else -1, i - gw, i + gw):
                if 0 <= j < gw * gh and reach[j]:
                    reach[j] = 0
                    queue.append(j)
        if x1 < 0:
            continue
        px = (x0 * cell, y0 * cell, min(w, (x1 + 1) * cell), min(h, (y1 + 1) * cell))
        box = Box(px[0] / w, px[1] / h, px[2] / w, px[3] / h)
        result.unexplained.append(box)
        ink_px = _count(unexplained.crop(px))
        if (
            min(box.width, box.height) >= MIN_REGION_SIDE
            and box.area >= MIN_REGION_AREA
            and ink_px >= MIN_REGION_INK * page_px
        ):
            result.regions.append(box)
