"""Shared data contract between the converter stages.

Every stage reads and writes these types, so they stay plain dataclasses that
serialize with ``to_dict``. Coordinates are always normalized to the page's
crop box in displayed orientation (rotation already applied): ``x`` grows to
the right, ``y`` grows downward, both in ``[0, 1]``. Pages are identified by
their 0-based physical index; printed labels are kept separately.
"""

from __future__ import annotations

import dataclasses
import enum
from dataclasses import dataclass, field
from typing import Any

JOB_FORMAT_VERSION = 1


def to_dict(obj: Any) -> Any:
    """Recursively convert dataclasses and enums into JSON-ready values."""
    if dataclasses.is_dataclass(obj) and not isinstance(obj, type):
        return {f.name: to_dict(getattr(obj, f.name)) for f in dataclasses.fields(obj)}
    if isinstance(obj, enum.Enum):
        return obj.value
    if isinstance(obj, dict):
        return {str(k): to_dict(v) for k, v in obj.items()}
    if isinstance(obj, (list, tuple)):
        return [to_dict(v) for v in obj]
    return obj


# --------------------------------------------------------------------------- geometry


@dataclass(frozen=True)
class Box:
    x0: float
    y0: float
    x1: float
    y1: float

    @property
    def width(self) -> float:
        return max(0.0, self.x1 - self.x0)

    @property
    def height(self) -> float:
        return max(0.0, self.y1 - self.y0)

    @property
    def area(self) -> float:
        return self.width * self.height

    def union(self, other: Box) -> Box:
        return Box(
            min(self.x0, other.x0),
            min(self.y0, other.y0),
            max(self.x1, other.x1),
            max(self.y1, other.y1),
        )

    def intersection_area(self, other: Box) -> float:
        w = min(self.x1, other.x1) - max(self.x0, other.x0)
        h = min(self.y1, other.y1) - max(self.y0, other.y0)
        return w * h if w > 0 and h > 0 else 0.0


# --------------------------------------------------------------------------- source side


@dataclass
class Word:
    text: str
    box: Box
    font_size: float | None = None  # points, when the extractor reports it
    bold: bool = False
    italic: bool = False
    font: str | None = None


@dataclass
class Line:
    words: list[Word]
    box: Box

    @property
    def text(self) -> str:
        return " ".join(w.text for w in self.words)


@dataclass
class TextBlock:
    id: str  # "s{page}-b{n}", stable for one inventory run
    page: int
    lines: list[Line]
    box: Box
    # Filled by preflight/repair classification: "body", "heading", "running_header",
    # "running_footer", "folio", "caption", "note", "toc", "index", "unknown".
    role: str = "unknown"

    @property
    def text(self) -> str:
        return "\n".join(line.text for line in self.lines)


@dataclass
class ImageRegion:
    id: str  # "s{page}-i{n}"
    page: int
    box: Box
    kind: str  # "raster" (embedded image), "vector" (drawn ink), "unexplained_ink"
    width_px: int | None = None
    height_px: int | None = None


@dataclass
class PageInventory:
    index: int  # physical, 0-based
    label: str | None  # from the PDF /PageLabels tree
    folio_candidates: list[str]  # printed page numbers seen in the margins
    width_pt: float  # crop box, displayed orientation
    height_pt: float
    rotation: int  # 0, 90, 180, 270
    crop_box: tuple[float, float, float, float]  # PDF user space
    media_box: tuple[float, float, float, float]
    blocks: list[TextBlock]
    images: list[ImageRegion]
    text_source: str  # "native", "none", "ocr"
    ink_coverage: float  # share of rendered pixels that are ink
    text_coverage: float  # share of ink pixels explained by text boxes
    page_type: str  # "text", "blank", "image", "scan", "mixed"
    flags: list[str] = field(default_factory=list)  # "broken_text_layer", "rotated", ...


@dataclass
class Bookmark:
    title: str
    level: int  # 1 = top level
    page: int | None  # physical index of the destination
    y: float | None  # normalized destination y when known


@dataclass
class SourceInventory:
    path: str
    sha256: str
    page_count: int
    encrypted: bool
    needs_password: bool
    info: dict[str, str]  # PDF document info dictionary, cleaned keys (title, author, ...)
    xmp: dict[str, Any]  # selected XMP fields (dc:title, dc:creator list, dc:language, ...)
    language: str | None  # BCP 47 guess, e.g. "en", "it"
    pages: list[PageInventory]
    bookmarks: list[Bookmark]
    named_dests: dict[str, tuple[int, float | None]]  # name -> (page, y)
    complexity: dict[str, Any]  # measured signals: columns, scanned share, rtl share, ...
    unsupported: list[str]  # reasons reflow is unsuitable; empty when in scope
    resource: dict[str, Any]  # file size, page count, decoded pixel estimate vs limits


@dataclass(frozen=True)
class SourceSpan:
    page: int
    box: Box
    text: str | None = None
    image_ref: str | None = None  # ImageRegion id


# --------------------------------------------------------------------------- output side

# Output blocks inside EPUB content documents carry stable ids with this prefix,
# assigned by quire_convert.epub.book.Book.ensure_block_ids().
BLOCK_ID_PREFIX = "qc-"
PAGE_ANCHOR_PREFIX = "qc-page-"


@dataclass
class OutputBlock:
    id: str  # element id in the content document
    href: str  # content document path inside the EPUB, e.g. "OEBPS/index_split_000.html"
    tag: str  # local element name: p, h1..h6, li, figure, img, ...
    text: str
    spans: list[SourceSpan] = field(default_factory=list)  # ordered source spans
    alignment: str = "unaligned"  # "exact", "fuzzy", "ambiguous", "generated", "unaligned"


# --------------------------------------------------------------------------- issues and outcomes


class Severity(enum.StrEnum):
    CRITICAL = "critical"  # material fidelity loss: missing/invented passage, wrong target
    MAJOR = "major"  # review needed: unresolved structure, image fallback
    MINOR = "minor"  # cosmetic or recoverable
    INFO = "info"  # recorded repair with evidence


class Outcome(enum.StrEnum):
    READY = "ready"
    REVIEW = "review"
    UNSUPPORTED = "unsupported"
    FAILED = "failed"

    @property
    def exit_code(self) -> int:
        return {"ready": 0, "failed": 1, "review": 2, "unsupported": 3}[self.value]


USAGE_EXIT_CODE = 64  # argparse usage errors; 2 is taken by REVIEW


@dataclass
class Issue:
    code: str  # dotted family, e.g. "nav.missing_chapter", "figure.lost", "meta.conflict"
    severity: Severity
    message: str  # short human summary, no book text
    page: int | None = None  # physical index
    page_label: str | None = None
    region: Box | None = None
    output_anchor: str | None = None  # "href#id"
    evidence: str = ""  # ids and measurements; book text only in local diagnostics
    action: str = ""  # what the converter did: "repaired", "image_fallback", "flagged", ...
    uncertainty: str = ""  # what remains unverified
    material: bool = False  # True blocks READY

    @property
    def blocks_ready(self) -> bool:
        return self.material or self.severity in (Severity.CRITICAL, Severity.MAJOR)


@dataclass
class Repair:
    """One applied change, recorded with before/after so it can be replayed or audited."""

    family: str  # "chapters", "figures", "pages", "index", "notes", "characters", "metadata"
    action: str  # e.g. "promote_heading", "attach_caption", "set_title"
    target: str  # output anchor or metadata field
    evidence: list[str]  # source/output ids
    before: str | None = None
    after: str | None = None


@dataclass
class MetadataField:
    name: str  # "title", "authors", "language", "publisher", "date", "identifiers", ...
    value: Any
    source: str  # "catalog", "title_page", "copyright_page", "xmp", "info", "detected"
    evidence: str = ""


class StageError(Exception):
    """A pipeline stage failed. Maps to Outcome.FAILED."""

    def __init__(self, stage: str, reason: str, retryable: bool = False):
        super().__init__(f"{stage}: {reason}")
        self.stage = stage
        self.reason = reason
        self.retryable = retryable
