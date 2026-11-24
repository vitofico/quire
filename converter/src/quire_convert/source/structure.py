"""Page geometry and the PDF's own structure: labels, outline, destinations, metadata.

Geometry comes from ``pdfinfo -box`` so it matches what the Poppler renderers
use. Everything else is read with pypdf and degrades to empty values when the
structure cannot be parsed (for example AES encryption without ``cryptography``).
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from datetime import date, datetime
from pathlib import Path
from typing import Any

from pypdf import PdfReader
from pypdf.errors import DependencyError, PdfReadError
from pypdf.generic import ContentStream

from ..models import Bookmark

log = logging.getLogger(__name__)

Rect = tuple[float, float, float, float]
Matrix = tuple[float, float, float, float, float, float]
_IDENTITY: Matrix = (1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
_MAX_FORM_DEPTH = 8
_INFO_KEYS = ("title", "author", "subject", "keywords", "creator", "producer", "creationdate")


@dataclass(frozen=True)
class PageGeometry:
    media_box: Rect
    crop_box: Rect  # clipped to the media box
    rotation: int  # 0, 90, 180, 270 clockwise

    @property
    def width(self) -> float:
        """Displayed crop-box width in points."""
        w, h = self.crop_box[2] - self.crop_box[0], self.crop_box[3] - self.crop_box[1]
        return h if self.rotation in (90, 270) else w

    @property
    def height(self) -> float:
        w, h = self.crop_box[2] - self.crop_box[0], self.crop_box[3] - self.crop_box[1]
        return w if self.rotation in (90, 270) else h

    @property
    def cropped(self) -> bool:
        return any(abs(a - b) > 0.5 for a, b in zip(self.crop_box, self.media_box, strict=True))

    def display(self, x: float, y: float, box: Rect | None = None) -> tuple[float, float]:
        """User-space point to displayed points from the top-left of ``box`` (crop box)."""
        x0, y0, x1, y1 = box or self.crop_box
        if self.rotation == 90:
            return y - y0, x - x0
        if self.rotation == 180:
            return x1 - x, y - y0
        if self.rotation == 270:
            return y1 - y, x1 - x
        return x - x0, y1 - y

    @property
    def crop_offset(self) -> tuple[float, float]:
        """Top-left of the crop box in displayed media-box points (pdftohtml's frame)."""
        x0, y0, x1, y1 = self.crop_box
        corners = [self.display(x, y, self.media_box) for x in (x0, x1) for y in (y0, y1)]
        return min(c[0] for c in corners), min(c[1] for c in corners)

    def normalize(self, rect: Rect) -> tuple[float, float, float, float]:
        """User-space rectangle to a [0, 1] crop-box box in displayed orientation."""
        corners = [self.display(x, y) for x in (rect[0], rect[2]) for y in (rect[1], rect[3])]
        xs, ys = [c[0] / self.width for c in corners], [c[1] / self.height for c in corners]

        def clamp(v: float) -> float:
            return round(min(1.0, max(0.0, v)), 5)

        return clamp(min(xs)), clamp(min(ys)), clamp(max(xs)), clamp(max(ys))

    def normalized_y(self, x: float | None, y: float | None) -> float | None:
        if y is None:
            return None
        _, dy = self.display(self.crop_box[0] if x is None else x, y)
        return round(min(1.0, max(0.0, dy / self.height)), 4) if self.height else None


def geometry_from_boxes(boxes: list[dict[str, Any]]) -> list[PageGeometry]:
    result = []
    for entry in boxes:
        media = entry.get("MediaBox") or (0.0, 0.0, 612.0, 792.0)
        crop = entry.get("CropBox") or media
        crop = (
            max(crop[0], media[0]),
            max(crop[1], media[1]),
            min(crop[2], media[2]),
            min(crop[3], media[3]),
        )
        if crop[2] <= crop[0] or crop[3] <= crop[1]:
            crop = media
        rotation = (round(entry.get("rotation", 0) / 90) * 90) % 360
        result.append(PageGeometry(media, crop, rotation))
    return result


@dataclass
class Structure:
    readable: bool = True
    labels: list[str] | None = None
    bookmarks: list[Bookmark] = field(default_factory=list)
    named_dests: dict[str, tuple[int, float | None]] = field(default_factory=dict)
    info: dict[str, str] = field(default_factory=dict)
    xmp: dict[str, Any] = field(default_factory=dict)
    catalog_lang: str | None = None
    # Placed raster images by page: (user-space bounding rect, width px, height px).
    images: dict[int, list[tuple[Rect, int | None, int | None]]] = field(default_factory=dict)


def read_structure(
    pdf: Path, geometry: list[PageGeometry], image_pages: set[int] | None = None
) -> Structure:
    """Read labels, outline, destinations, metadata and image placements; never raises.

    Image placements are read only for ``image_pages`` (from ``pdfimages -list``), by
    following the content stream's transformation matrix to each image draw.
    """
    try:
        reader = PdfReader(str(pdf))
        if reader.is_encrypted and not reader.decrypt(""):
            return Structure(readable=False)
        root = reader.trailer["/Root"]
    except DependencyError:
        log.warning("pdf structure skipped: encryption needs the cryptography package")
        return Structure(readable=False)
    except (PdfReadError, ValueError, KeyError, TypeError, OSError) as exc:
        log.warning("pdf structure unreadable: %s", type(exc).__name__)
        return Structure(readable=False)
    out = Structure()
    for name, fn in (
        ("labels", _labels),
        ("bookmarks", _bookmarks),
        ("named_dests", _named_dests),
        ("info", _info),
        ("xmp", _xmp),
        ("catalog_lang", _catalog_lang),
    ):
        try:
            value = fn(reader, root, geometry)
        except Exception as exc:  # noqa: BLE001 - malformed structure must not stop preflight
            log.warning("pdf %s unreadable: %s", name, type(exc).__name__)
            continue
        setattr(out, name, value)
    for index in sorted(image_pages or ()):
        try:
            out.images[index] = image_placements(reader, index)
        except Exception as exc:  # noqa: BLE001 - one bad content stream must not stop preflight
            log.warning("image placements unreadable on page %d: %s", index, type(exc).__name__)
    return out


def _mul(m: Matrix, n: Matrix) -> Matrix:
    a, b, c, d, e, f = m
    A, B, C, D, E, F = n  # noqa: N806 - PDF matrix names
    return (
        a * A + b * C,
        a * B + b * D,
        c * A + d * C,
        c * B + d * D,
        e * A + f * C + E,
        e * B + f * D + F,
    )


def _unit_rect(m: Matrix) -> Rect:
    a, b, c, d, e, f = m
    xs = [e, a + e, c + e, a + c + e]
    ys = [f, b + f, d + f, b + d + f]
    return min(xs), min(ys), max(xs), max(ys)


def image_placements(reader: PdfReader, index: int) -> list[tuple[Rect, int | None, int | None]]:
    """Bounding rects (user space) and pixel sizes of the raster images drawn on a page."""
    page = reader.pages[index]
    contents = page.get_contents()
    if contents is None:
        return []
    found: list[tuple[Rect, int | None, int | None]] = []
    _walk_stream(reader, contents.operations, page.get("/Resources"), _IDENTITY, found, set(), 0)
    return found


def _walk_stream(reader, operations, resources, ctm: Matrix, found, seen: set, depth: int) -> None:
    stack: list[Matrix] = []
    resources = resources.get_object() if resources is not None else None
    xobjects = resources.get("/XObject") if resources is not None else None
    xobjects = xobjects.get_object() if xobjects is not None else {}
    for operands, op in operations:
        if op == b"q":
            stack.append(ctm)
        elif op == b"Q":
            ctm = stack.pop() if stack else ctm
        elif op == b"cm" and len(operands) == 6:
            ctm = _mul(tuple(float(v) for v in operands), ctm)  # type: ignore[arg-type]
        elif op == b"INLINE IMAGE":
            settings = operands.get("settings", {}) if isinstance(operands, dict) else {}
            width = settings.get("/W", settings.get("/Width"))
            height = settings.get("/H", settings.get("/Height"))
            found.append((_unit_rect(ctm), _int(width), _int(height)))
        elif op == b"Do" and operands and str(operands[0]) in xobjects:
            ref = xobjects[str(operands[0])]
            obj = ref.get_object()
            subtype = obj.get("/Subtype")
            if subtype == "/Image":
                found.append((_unit_rect(ctm), _int(obj.get("/Width")), _int(obj.get("/Height"))))
            elif subtype == "/Form" and depth < _MAX_FORM_DEPTH:
                key = getattr(ref, "idnum", id(obj))
                if key in seen:
                    continue
                matrix = obj.get("/Matrix") or _IDENTITY
                inner = _mul(tuple(float(v) for v in matrix), ctm)  # type: ignore[arg-type]
                stream = ContentStream(obj, reader)
                _walk_stream(
                    reader,
                    stream.operations,
                    obj.get("/Resources") or resources,
                    inner,
                    found,
                    seen | {key},
                    depth + 1,
                )


def _int(value: Any) -> int | None:
    try:
        return int(value) if value is not None else None
    except (TypeError, ValueError):
        return None


def _labels(reader: PdfReader, root: Any, geometry: list[PageGeometry]) -> list[str] | None:
    if "/PageLabels" not in root:
        return None
    return [str(label) for label in reader.page_labels]


def _destination(reader: PdfReader, dest: Any, geometry: list[PageGeometry]):
    try:
        page = reader.get_destination_page_number(dest)
    except Exception:  # noqa: BLE001 - outline items may point at actions or broken refs
        return None, None
    if page is None or not 0 <= page < len(geometry):
        return None, None
    left, top = _num(dest.get("/Left")), _num(dest.get("/Top"))
    return page, geometry[page].normalized_y(left, top)


def _num(value: Any) -> float | None:
    try:
        return float(value) if value is not None else None
    except (TypeError, ValueError):
        return None


def _bookmarks(reader: PdfReader, root: Any, geometry: list[PageGeometry]) -> list[Bookmark]:
    result: list[Bookmark] = []

    def walk(items: list[Any], level: int) -> None:
        for item in items:
            if isinstance(item, list):
                walk(item, level + 1)
                continue
            page, y = _destination(reader, item, geometry)
            title = " ".join(str(item.title or "").split())
            result.append(Bookmark(title=title, level=level, page=page, y=y))

    walk(reader.outline, 1)
    return result


def _named_dests(reader: PdfReader, root: Any, geometry: list[PageGeometry]):
    result = {}
    for name, dest in reader.named_destinations.items():
        page, y = _destination(reader, dest, geometry)
        if page is not None:
            result[str(name)] = (page, y)
    return result


def _info(reader: PdfReader, root: Any, geometry: list[PageGeometry]) -> dict[str, str]:
    meta = reader.metadata or {}
    result = {}
    for key, value in meta.items():
        clean = str(key).lstrip("/").lower()
        text = " ".join(str(value).split()) if value is not None else ""
        if clean in _INFO_KEYS and text:
            result[clean] = text
    return result


def _plain(value: Any) -> Any:
    if isinstance(value, (datetime, date)):
        return value.isoformat()
    if isinstance(value, dict):
        return value.get("x-default") or next(iter(value.values()), None)
    if isinstance(value, (list, tuple)):
        return [_plain(v) for v in value if v not in (None, "")]
    return str(value) if value is not None else None


def _xmp(reader: PdfReader, root: Any, geometry: list[PageGeometry]) -> dict[str, Any]:
    xmp = reader.xmp_metadata
    if xmp is None:
        return {}
    result = {}
    for name in ("dc_title", "dc_creator", "dc_language", "dc_publisher", "dc_date"):
        value = _plain(getattr(xmp, name, None))
        if value:
            result[name] = value
    identifier = _plain(getattr(xmp, "dc_identifier", None))
    if identifier:
        result["dc_identifier"] = identifier
    return result


def _catalog_lang(reader: PdfReader, root: Any, geometry: list[PageGeometry]) -> str | None:
    lang = root.get("/Lang")
    return str(lang).strip() or None if lang is not None else None
