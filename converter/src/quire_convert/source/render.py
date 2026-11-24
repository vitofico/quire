"""Render one page, or a normalized region of it, for figure and report crops."""

from __future__ import annotations

import math
from pathlib import Path
from typing import Any

from ..models import Box, StageError
from . import poppler
from .structure import geometry_from_boxes

DEFAULT_TIMEOUT_S = 120.0


def render_region(
    pdf: Path,
    page: int,
    box: Box | None,
    dpi: int,
    out_png: Path,
    toolchain: Any = None,
    max_pixels: int | None = None,
) -> Path:
    """Render 0-based ``page`` (or the crop-box-normalized ``box`` on it) to ``out_png``.

    Coordinates follow the inventory: crop box, displayed orientation, top-left
    origin. ``max_pixels`` defaults to ``Limits().max_decoded_pixels``.
    """
    from .preflight import Limits

    pdf, out_png = Path(pdf), Path(out_png)
    info_bin = poppler.tool_path(toolchain, "pdfinfo")
    if page < 0:
        raise StageError("render", f"page {page} out of range")
    n = str(page + 1)
    try:
        boxes = poppler.page_boxes(info_bin, pdf, page + 1, page + 1, DEFAULT_TIMEOUT_S, "render")
    except poppler.PasswordRequired as exc:
        raise StageError("render", "PDF requires a password") from exc
    if not boxes[0]:
        raise StageError("render", f"page {page} out of range")
    geom = geometry_from_boxes(boxes)[0]
    scale = dpi / 72
    page_w, page_h = geom.width * scale, geom.height * scale
    cmd = [poppler.tool_path(toolchain, "pdftoppm"), "-f", n, "-l", n, "-r", str(dpi)]
    cmd += ["-cropbox", "-png", "-singlefile"]
    if box is None:
        width, height = math.ceil(page_w), math.ceil(page_h)
    else:
        x0, y0 = math.floor(box.x0 * page_w), math.floor(box.y0 * page_h)
        width = max(1, math.ceil(box.x1 * page_w) - x0)
        height = max(1, math.ceil(box.y1 * page_h) - y0)
        cmd += ["-x", str(x0), "-y", str(y0), "-W", str(width), "-H", str(height)]
    limit = max_pixels if max_pixels is not None else Limits().max_decoded_pixels
    if width * height > limit:
        raise StageError("render", f"render of {width * height} pixels exceeds limit {limit}")
    out_png.parent.mkdir(parents=True, exist_ok=True)
    prefix = out_png.with_suffix("")
    try:
        poppler.run([*cmd, str(pdf), str(prefix)], DEFAULT_TIMEOUT_S, "render")
    except poppler.PasswordRequired as exc:
        raise StageError("render", "PDF requires a password") from exc
    written = prefix.with_suffix(".png")
    if written != out_png:
        written.replace(out_png)
    return out_png
