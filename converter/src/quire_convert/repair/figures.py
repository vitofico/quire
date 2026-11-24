"""Restore figures Calibre dropped or flattened, with their captions, and mark facsimiles.

For every raster or vector region of the source inventory:

* a scanned insert keeps (or gets) a full-page facsimile image marked with its region ids
  and is reported, because its text exists only as pixels;
* a region the EPUB already shows keeps its embedded image when that image is the whole
  figure (a raster with no labels drawn over it that decodes to visible content); otherwise
  the bounded region is rendered with its labels and replaces it;
* a region the EPUB lost is rendered and inserted as a ``<figure>`` where the source order
  puts it: after the output text of the last source text above it on the page, before the
  next;
* a tinted box or panel holding body text is not a figure: its text already carries it.

A caption is matched by figure number, distance, order and the prose's references to it,
and moved (never copied) into the ``figcaption``. Label words Calibre emitted as loose body
text are cut from the body once the rendered image shows them. Alt text stays empty: an
image fallback never claims to supply equivalent text.
"""

from __future__ import annotations

import io
import logging
import math
import posixpath
import re
import statistics
from bisect import bisect_left
from collections import Counter
from collections.abc import Callable
from dataclasses import dataclass
from typing import TYPE_CHECKING

from lxml import etree
from PIL import Image

from ..align import align
from ..align.coverage import FIGURE_TEXT_SHARE, note_blocks, running_furniture, text_panels
from ..epub.book import remove_keep_tail
from ..epub.inline import merge, split_at, text_of
from ..epub.package import XHTML_NS, local_name, relative_href, resolve_href
from ..models import (
    PAGE_ANCHOR_PREFIX,
    Box,
    ImageRegion,
    PageInventory,
    Severity,
    SourceInventory,
    StageError,
    TextBlock,
)
from ..source.render import render_region

if TYPE_CHECKING:
    from ..context import ConvertContext

log = logging.getLogger(__name__)

FAMILY = "figures"
DPI = 200
MIN_DPI = 72
MAX_PIXELS = 6_000_000  # per rendered figure or facsimile
PAD = 0.004  # page share added around a rendered region
FULL_PAGE = 0.9  # a scan's embedded image covering this share of the page is a facsimile
CAPTION_GAP = 0.08  # farthest a caption sits from its figure, as a page share
UNNUMBERED_GAP = 0.03  # an unlabelled caption must sit this close
CAPTION_TIE = 0.015  # candidates closer than this to each other are equally plausible
_INK_SHARE, _INK_DELTA = 0.0005, 24  # same blank test as epub.validate
_CAPTION = re.compile(
    r"^(?P<label>(?:figure|figura|fig|tavola|tav|plate|illustration|table|tabella|tab)\b\.?)"
    r"\s*(?P<num>\d{1,3}[a-z]?\b|[ivxlc]{1,6}\b)?\s*(?P<sep>[.:)\-–—])?",
    re.I,
)
_MENTION = re.compile(
    r"\b(?:figure|figura|fig|tavola|tav|plate|table|tabella|tab)\.?\s*(\d+)", re.I
)
_ROMAN = {"i": 1, "v": 5, "x": 10, "l": 50, "c": 100}
_MEDIA = frozenset({"img", "svg", "video", "audio", "object", "embed", "iframe", "math"})
# Parents a figure cannot be inserted into as a sibling of the block: climb past them.
_ENCLOSING = frozenset(
    {"ol", "ul", "dl", "li", "dt", "dd", "table", "thead", "tbody", "tfoot", "tr", "td", "th"}
    | {"figure", "figcaption", "aside"}
)
SOURCE_ATTR = align.SOURCE_ATTR


def apply(ctx: ConvertContext) -> None:
    _Figures(ctx).run()


# --------------------------------------------------------------------------- shared editing


def cut(el: etree._Element, start: int, end: int) -> etree._Element:
    """Remove ``[start, end)`` of the block's text (``text_of`` offsets) and return it as a
    detached copy of the block holding just that part, inline markup kept."""
    right = split_at(el, end)
    mid = split_at(el, start)
    mid.getparent().remove(mid)
    merge(el, right)
    return mid


def wrap(el: etree._Element, start: int, end: int, wrapper: etree._Element) -> None:
    """Move ``[start, end)`` of the block's text, markup included, into ``wrapper``."""
    right = split_at(el, end)
    mid = split_at(el, start)
    move_content(mid, wrapper)
    mid.getparent().remove(mid)
    el.append(wrapper)
    merge(el, right)


def move_content(src: etree._Element, dest: etree._Element) -> None:
    """Append ``src``'s text and children to ``dest``."""
    if len(dest):
        dest[-1].tail = (dest[-1].tail or "") + (src.text or "")
    else:
        dest.text = (dest.text or "") + (src.text or "")
    for child in list(src):
        dest.append(child)


def widen(text: str, start: int, end: int) -> tuple[int, int]:
    """Grow a word range over the punctuation glued to it ("(1)", "1.", "dawn.")."""
    while start > 0 and not text[start - 1].isspace() and not text[start - 1].isalnum():
        start -= 1
    while end < len(text) and not text[end].isspace() and not text[end].isalnum():
        end += 1
    return start, end


def xhtml(tag: str, **attrs: str) -> etree._Element:
    return etree.Element(f"{{{XHTML_NS}}}{tag}", attrs)


def body_font_size(source: SourceInventory, furniture: set[str]) -> float | None:
    sizes = Counter(
        w.font_size
        for page in source.pages
        for b in page.blocks
        if b.id not in furniture
        for line in b.lines
        for w in line.words
        if w.font_size
    )
    return sizes.most_common(1)[0][0] if sizes else None


@dataclass
class _Op:
    block: str
    offset: int
    priority: int  # at one offset, range edits (1) run before inserts (0)
    seq: int
    run: Callable[[etree._Element], None]


class Edits:
    """Edits collected per output block in the offsets of its original text, applied right
    to left so every offset still means what it meant when it was planned."""

    def __init__(self, ctx: ConvertContext):
        self.book = ctx.book
        self.blocks = {b.id: b for b in ctx.blocks}
        self._ops: list[_Op] = []
        self._ranges: dict[str, list[tuple[int, int]]] = {}
        self._inserts: dict[tuple[str, int], list[etree._Element]] = {}
        self.touched: set[str] = set()

    def text(self, block_id: str) -> str:
        return self.blocks[block_id].text

    def free(self, block_id: str, start: int, end: int) -> bool:
        return all(end <= a or start >= b for a, b in self._ranges.get(block_id, []))

    def cut(self, block_id: str, start: int, end: int, take=None) -> bool:
        """Plan removing a range; ``take(mid)`` receives the removed part."""
        if not self.free(block_id, start, end):
            return False

        def run(el: etree._Element) -> None:
            mid = cut(el, start, end)
            if take is not None:
                take(mid)

        self._range(block_id, start, end, run)
        self.touched.add(block_id)
        return True

    def wrap(self, block_id: str, start: int, end: int, wrapper: etree._Element) -> bool:
        if not self.free(block_id, start, end):
            return False
        self._range(block_id, start, end, lambda el: wrap(el, start, end, wrapper))
        return True

    def insert(self, block_id: str, offset: int, element: etree._Element) -> None:
        """Plan inserting a block element at ``offset`` of a block: before it at 0, after it
        at its end, else between the two halves of a split. Same-spot inserts keep order."""
        key = (block_id, offset)
        if key not in self._inserts:
            self._inserts[key] = []
            n = len(self.text(block_id))
            self._ops.append(
                _Op(block_id, offset, 0, len(self._ops), lambda el: self._place(el, key, n))
            )
        self._inserts[key].append(element)

    def append(self, href: str, element: etree._Element) -> None:
        self.book.doc(href).body.append(element)

    def apply(self) -> None:
        by_block: dict[str, list[_Op]] = {}
        for op in self._ops:
            by_block.setdefault(op.block, []).append(op)
        for block_id, ops in by_block.items():
            _, el = self.book.element(f"{self.blocks[block_id].href}#{block_id}")
            for op in sorted(ops, key=lambda o: (o.offset, o.priority, -o.seq), reverse=True):
                op.run(el)
        self._cleanup()

    def _range(self, block_id: str, start: int, end: int, run) -> None:
        self._ranges.setdefault(block_id, []).append((start, end))
        self._ops.append(_Op(block_id, start, 1, len(self._ops), run))

    def _place(self, el: etree._Element, key: tuple[str, int], length: int) -> None:
        offset = key[1]
        anchor = _container(el)
        if offset <= 0:
            for item in self._inserts[key]:
                anchor.addprevious(item)
            return
        if offset < length and anchor is el:
            split_at(el, offset)
        for item in self._inserts[key]:
            anchor.addnext(item)
            anchor = item

    def _cleanup(self) -> None:
        """Drop blocks the edits emptied, unless something points at them."""
        targets = {t for *_, t in self.book.iter_links() if "#" in t}
        targets |= {h for h in _nav_hrefs(self.book) if "#" in h}
        for block_id in self.touched:
            href = self.blocks[block_id].href
            try:
                _, el = self.book.element(f"{href}#{block_id}")
            except KeyError:
                continue
            if not _empty(el) or _pinned(el, href, targets):
                continue
            remove_keep_tail(el)


def _nav_hrefs(book) -> list[str]:
    hrefs = [href for _, href in book.page_list] + [m.href for m in book.landmarks]
    stack = list(book.toc)
    while stack:
        entry = stack.pop()
        hrefs.append(entry.href)
        stack.extend(entry.children)
    return hrefs


def _container(el: etree._Element) -> etree._Element:
    node = el
    while (parent := node.getparent()) is not None and local_name(parent) in _ENCLOSING:
        node = parent
    return node


def _empty(el: etree._Element) -> bool:
    if "".join(el.itertext()).strip():
        return False
    return not any(isinstance(d.tag, str) and local_name(d) in _MEDIA for d in el.iter())


def _pinned(el: etree._Element, href: str, targets: set[str]) -> bool:
    for node in el.iter(etree.Element):
        ident = node.get("id")
        if ident is None:
            continue
        if node is not el or f"{href}#{ident}" in targets:
            return True
        if ident.startswith(("page_", PAGE_ANCHOR_PREFIX)):
            return True
    return False


def visible_size(data: bytes) -> tuple[int, int] | None:
    """Pixel size of an image that decodes and shows ink; None when broken or blank."""
    try:
        with Image.open(io.BytesIO(data)) as img:
            img.load()
            size = img.size
            if img.mode in ("RGBA", "LA", "PA") or "transparency" in img.info:
                rgba = img.convert("RGBA")
                white = Image.new("RGBA", rgba.size, (255, 255, 255, 255))
                gray = Image.alpha_composite(white, rgba).convert("L")
            else:
                gray = img.convert("L")
    except Exception:  # noqa: BLE001 - any decoder failure means the asset is unusable
        return None
    hist = gray.histogram()
    total = sum(hist)
    mode = max(range(256), key=hist.__getitem__)
    near = sum(hist[max(0, mode - _INK_DELTA) : mode + _INK_DELTA + 1])
    return size if total and (total - near) / total >= _INK_SHARE else None


# --------------------------------------------------------------------------- figures


@dataclass
class _Caption:
    block: TextBlock
    number: int | None
    labelled: bool
    table: bool
    distance: float
    referenced: bool
    mismatch: bool = False


class _Figures:
    def __init__(self, ctx: ConvertContext):
        self.ctx = ctx
        self.al = ctx.alignment
        self.source = ctx.source
        self.book = ctx.book
        self.furniture = running_furniture(ctx.source)
        self.panels = text_panels(ctx.source)
        self.notes = note_blocks(ctx.source, self.furniture)
        self.body_size = body_font_size(ctx.source, self.furniture)
        self.edits = Edits(ctx)
        self.blocks = self.edits.blocks
        self.out_of_src: dict[int, list[int]] = {}  # source token -> output tokens, dupes too
        for j, i in enumerate(self.al.out_to_src):
            if i >= 0:
                self.out_of_src.setdefault(i, []).append(j)
        self.token_pages = [t.page for t in self.al.source]
        self.used: set[str] = set()  # caption blocks already given to a figure
        self.last_number: int | None = None
        self.ordinal = 0
        self.images: list[tuple[etree._Element, str]] = []  # (img, asset href) to point
        self.asset_dir = posixpath.dirname(ctx.book.docs[0].href) if ctx.book.docs else ""
        self.changed = False

    def run(self) -> None:
        for page in self.source.pages:
            regions = [r for r in page.images if r.kind in ("raster", "vector")]
            if not regions:
                continue
            if page.page_type == "scan" or "zero_text_layer" in page.flags:
                self._facsimile(page, regions)
                continue
            for region in regions:
                self._region(page, region)
        if not self.changed:
            return
        self.edits.apply()
        for img, asset in self.images:
            root = img.getroottree().getroot()
            doc = next(d for d in self.book.docs if d.tree is root)
            img.set("src", relative_href(doc.href, asset))
        align.refresh(self.ctx)

    # ---- scanned inserts

    def _facsimile(self, page: PageInventory, regions: list[ImageRegion]) -> None:
        ids = " ".join(r.id for r in regions)
        shown = [r for r in regions if r.id in self.al.images]
        full = len(regions) == 1 and regions[0].box.area >= FULL_PAGE
        img = self._shown_img(shown[0]) if shown else None
        if img is not None and img.get(SOURCE_ATTR) is not None:
            return  # marked by an earlier pass
        anchor = None
        if img is not None and full and self._asset_ok(img):
            img.set(SOURCE_ATTR, ids)
            anchor = self._anchor_of(img)
            self._record("mark_facsimile", anchor, [*ids.split(), f"page={page.index}"])
        else:
            rendered = self._render(page, regions[0], full_page=True)
            if rendered is None:
                return
            asset, size, dpi = rendered
            evidence = [*ids.split(), f"page={page.index}", f"dpi={dpi}", f"px={size[0]}x{size[1]}"]
            if img is not None:
                self._replace(img, asset)
                img.set(SOURCE_ATTR, ids)
                anchor = self._anchor_of(img)
                self._record("replace_facsimile", anchor, evidence)
            else:
                figure = xhtml("figure", id=f"qc-fig-{regions[0].id}")
                figure.set(SOURCE_ATTR, ids)
                image = etree.SubElement(figure, f"{{{XHTML_NS}}}img", alt="")
                self.images.append((image, asset))
                anchor = self._insert(page, regions[0].box, set(), figure)
                self._record("insert_facsimile", anchor, evidence)
        self.changed = True
        self._issue(
            "figure.facsimile_page",
            Severity.MAJOR,
            "A scanned page is kept as a full-page image; its text is not available as text",
            page,
            regions[0],
            anchor,
            f"{ids} page={page.index}",
            "image_fallback",
            "The page's words are only pixels: they cannot be searched, reflowed or read aloud.",
        )

    # ---- one figure region

    def _region(self, page: PageInventory, region: ImageRegion) -> None:
        inside = self._inside(page, region)
        caption, ambiguous = self._caption(page, region, inside)
        if caption is not None and caption.table:
            self._use(caption)
            self._table(page, region, caption)
            return
        if region.id in self.panels:
            return  # a panel of body text: coverage counts its reflowed text as showing it
        self.ordinal += 1
        if caption is not None:
            self._use(caption)
        labels = [b for b in inside if caption is None or b.id != caption.block.id]
        if region.id in self.al.images:
            self._shown(page, region, labels, caption)
        else:
            self._lost(page, region, labels, caption)
        if ambiguous:
            self._issue(
                "figure.caption_unmatched",
                Severity.MAJOR,
                "Several captions fit this figure equally well; none was attached",
                page,
                region,
                self._anchor_near(page),
                f"{region.id} " + " ".join(f"candidate={b.id}" for b in ambiguous),
                "flagged",
                "The captions stay where Calibre put them.",
            )

    def _inside(self, page: PageInventory, region: ImageRegion) -> list[TextBlock]:
        return [
            b
            for b in page.blocks
            if b.box.area
            and b.id not in self.furniture
            and region.box.intersection_area(b.box) >= FIGURE_TEXT_SHARE * b.box.area
        ]

    def _table(self, page: PageInventory, region: ImageRegion, caption: _Caption) -> None:
        if region.id in self.al.images:
            owner = self.al.images[region.id]
            _, el = self.book.element(f"{self.blocks[owner].href}#{owner}")
            if any(local_name(a) == "table" for a in [el, *el.iterancestors()]):
                return
        self._issue(
            "figure.table_unverified",
            Severity.MAJOR,
            "A table was found but its rows and cells could not be verified",
            page,
            region,
            self._anchor_near(page),
            f"{region.id} caption={caption.block.id} page={page.index}",
            "flagged",
            "Cell order and header assignment in the EPUB are unchecked.",
        )

    def _shown(self, page, region, labels, caption) -> None:
        img = self._shown_img(region)
        if img is None or img.get(SOURCE_ATTR) is not None:
            return
        ids = " ".join([region.id, *(b.id for b in labels)])
        evidence = ids.split()
        if region.kind != "raster" or labels or not self._asset_ok(img):
            rendered = self._render(page, region)
            if rendered is not None:
                asset, size, dpi = rendered
                self._replace(img, asset)
                evidence += [f"dpi={dpi}", f"px={size[0]}x{size[1]}"]
                self._record("replace_image", self._anchor_of(img), evidence)
        else:
            self._record("keep_image", self._anchor_of(img), evidence)
        img.set(SOURCE_ATTR, ids)
        self.changed = True
        figure = self._wrap_shown(img, region) if caption else None
        if figure is not None:
            self._attach(figure, region, caption)
        anchor = self._anchor_of(figure if figure is not None else img)
        self._cut_labels(region, labels, anchor)
        self._image_only(page, region, anchor)

    def _wrap_shown(self, img: etree._Element, region: ImageRegion) -> etree._Element | None:
        """Put a lone image into a figure so a caption can join it; None when it shares a
        block with text or already sits in a figure."""
        if any(local_name(a) == "figure" for a in img.iterancestors()):
            return None
        holder = next((a for a in img.iterancestors() if a.get("id") in self.blocks), None)
        if holder is not None and text_of(holder):
            return None
        figure = xhtml("figure", id=f"qc-fig-{region.id}")
        if holder is None:
            img.addprevious(figure)
            figure.tail = img.tail
        else:
            holder.addnext(figure)  # the emptied holder goes unless something points at it
            self.edits.touched.add(holder.get("id"))
        img.tail = None
        figure.append(img)
        return figure

    def _lost(self, page, region, labels, caption) -> None:
        rendered = self._render(page, region)
        if rendered is None:
            return
        asset, size, dpi = rendered
        ids = " ".join([region.id, *(b.id for b in labels)])
        figure = xhtml("figure", id=f"qc-fig-{region.id}")
        figure.set(SOURCE_ATTR, ids)
        img = etree.SubElement(figure, f"{{{XHTML_NS}}}img", alt="")
        self.images.append((img, asset))
        exclude = {b.id for b in labels} | ({caption.block.id} if caption else set())
        anchor = self._insert(page, region.box, exclude, figure)
        self.changed = True
        if caption:
            self._attach(figure, region, caption)
        self._cut_labels(region, labels, anchor)
        evidence = [*ids.split(), f"dpi={dpi}", f"px={size[0]}x{size[1]}"]
        self._record("insert_figure", anchor, evidence)
        self._image_only(page, region, anchor)

    # ---- captions

    def _caption(self, page, region, inside) -> tuple[_Caption | None, list[TextBlock]]:
        """The caption for ``region``, or (None, rivals) when several fit equally well."""
        inside_ids = {b.id for b in inside}
        refs = self._mentions(page.index)
        found = [
            c
            for b in page.blocks
            if (c := self._candidate(b, region.box, b.id in inside_ids, refs)) is not None
        ]
        if not found:
            found = self._next_page_caption(page, region, refs)
        if not found:
            return None, []
        expected = self.last_number + 1 if self.last_number else self.ordinal + 1
        for c in found:
            c.mismatch = c.number is not None and c.number != expected
        found.sort(key=lambda c: (c.mismatch, c.distance))
        best = found[0]
        rivals = [
            c
            for c in found[1:]
            if c.mismatch == best.mismatch and c.distance - best.distance <= CAPTION_TIE
        ]
        if rivals:
            referenced = [c for c in [best, *rivals] if c.referenced]
            if len(referenced) != 1:
                return None, [c.block for c in [best, *rivals]]
            best = referenced[0]
        return best, []

    def _use(self, caption: _Caption) -> None:
        self.used.add(caption.block.id)
        if caption.number is not None:
            self.last_number = caption.number

    def _candidate(self, block, box, inside, refs, distance=None) -> _Caption | None:
        if block.id in self.used or block.id in self.furniture or block.id in self.notes:
            return None
        text = block.text.strip()
        match = _CAPTION.match(text)
        styled = self._styled(block)
        if distance is None:
            distance = _vertical_gap(block.box, box)
        if match:
            rest = text[match.end() :].strip()
            if not (match.group("sep") or not rest or styled):
                return None  # prose that mentions a figure: "Figure 1 shows ..."
            number = _number(match.group("num"))
            label = match.group("label").lower()
        elif inside or not styled or len(block.lines) > 2 or distance > UNNUMBERED_GAP:
            return None
        elif abs((block.box.x0 + block.box.x1) - (box.x0 + box.x1)) > 0.2:
            return None
        else:
            number, label = None, ""
        if inside and number is None:
            return None
        if distance > CAPTION_GAP:
            return None
        return _Caption(
            block,
            number,
            bool(match),
            label.startswith("tab") and not label.startswith("tav"),
            distance,
            number is not None and number in refs,
        )

    def _next_page_caption(self, page, region, refs) -> list[_Caption]:
        """A labelled caption opening the next page, when nothing follows the figure here."""
        below = [
            b
            for b in page.blocks
            if b.box.y0 >= region.box.y1
            and b.id not in self.furniture
            and b.id not in self.notes
            and not _ink_inside(region.box, b.box)
        ]
        if below or page.index + 1 >= len(self.source.pages):
            return []
        nxt = self.source.pages[page.index + 1]
        first = next((b for b in nxt.blocks if b.id not in self.furniture), None)
        if first is None:
            return []
        gap = (1 - region.box.y1) + first.box.y0
        c = self._candidate(first, region.box, False, refs, distance=min(gap, CAPTION_GAP))
        return [c] if c is not None and c.labelled else []

    def _mentions(self, page: int) -> set[int]:
        found: set[int] = set()
        for p in range(max(0, page - 1), min(len(self.source.pages), page + 2)):
            for block in self.source.pages[p].blocks:
                text = block.text
                match = _CAPTION.match(text.strip())
                if match and (match.group("sep") or self._styled(block)):
                    text = text.strip()[match.end() :]  # a caption names itself
                found |= {int(n) for n in _MENTION.findall(text)}
        return found

    def _styled(self, block: TextBlock) -> bool:
        words = [w for line in block.lines for w in line.words]
        if not words:
            return False
        if sum(w.italic for w in words) * 2 > len(words):
            return True
        sizes = [w.font_size for w in words if w.font_size]
        return bool(sizes and self.body_size and statistics.median(sizes) < 0.95 * self.body_size)

    def _attach(self, figure: etree._Element, region: ImageRegion, caption: _Caption) -> None:
        """Plan moving the caption's output text into the figure's figcaption."""
        block = caption.block
        tokens = self.al.source_block_tokens(block.id)
        own = set(tokens)
        js = [j for i in tokens for j in self.out_of_src.get(i, [])]
        outs = {self.al.output[j].block for j in js}
        ok = len(js) >= 0.8 * len(tokens) and len(outs) == 1
        if ok:
            out = outs.pop()
            start = min(self.al.output[j].start for j in js)
            end = max(self.al.output[j].end for j in js)
            start, end = widen(self.edits.text(out), start, end)
            ok = not any(
                start <= self.al.output[j].start < end and self.al.out_to_src[j] not in own
                for j in self.al.output_block_tokens(out)
                if self.al.out_to_src[j] >= 0
            )
        figcaption = xhtml("figcaption")
        if ok and self.edits.cut(out, start, end, lambda mid: move_content(mid, figcaption)):
            if block.box.y1 <= region.box.y0:
                figure.insert(0, figcaption)
            else:
                figure.append(figcaption)
            self._record(
                "attach_caption",
                f"{self.blocks[out].href}#{figure.get('id')}",
                [block.id, region.id, out],
                before=block.text,
            )
            return
        self._issue(
            "figure.caption_unmatched",
            Severity.MAJOR,
            "The figure's caption could not be moved into the figure",
            self.source.pages[region.page],
            region,
            self._anchor_near(self.source.pages[region.page]),
            f"{region.id} caption={block.id} outputs={len(outs)}",
            "flagged",
            "The caption text stays where Calibre put it, apart from its figure.",
        )

    # ---- labels, placement, rendering

    def _cut_labels(self, region: ImageRegion, labels: list[TextBlock], figure: str) -> None:
        """Cut label words the image now shows out of the body text."""
        for block in labels:
            cuts: dict[str, int] = {}
            for i in self.al.source_block_tokens(block.id):
                for j in self.out_of_src.get(i, []):
                    tok = self.al.output[j]
                    if self.edits.cut(tok.block, tok.start, tok.end):
                        cuts[tok.block] = cuts.get(tok.block, 0) + 1
            for out, n in cuts.items():
                self._record(
                    "remove_label",
                    figure,
                    [block.id, region.id, out, f"words={n}"],
                    before=block.text,
                )

    def _insert(self, page, box: Box, exclude: set[str], element) -> str:
        """Plan inserting ``element`` at the source position of ``box``; return its anchor."""
        exclude = exclude | self.furniture | self.notes
        order = [b for b in align.reading_order(page) if b.id not in exclude]
        k = next(
            (n for n, b in enumerate(order) if b.box.y0 >= box.y0 - 0.005 and _x_overlap(b, box)),
            len(order),
        )
        before = [i for b in order[:k] for i in self.al.source_block_tokens(b.id)]
        after = [i for b in order[k:] for i in self.al.source_block_tokens(b.id)]
        lo = bisect_left(self.token_pages, page.index)
        hi = bisect_left(self.token_pages, page.index + 1)
        prev = self._matched(reversed(before)) or self._matched(range(lo - 1, -1, -1), exclude)
        nxt = self._matched(after) or self._matched(range(hi, len(self.al.source)), exclude)
        out = self.al.output
        jp = self.al.src_to_out[prev] if prev is not None else None
        jn = self.al.src_to_out[nxt] if nxt is not None else None
        ident = element.get("id")
        if jp is not None and jn is not None and out[jp].block == out[jn].block:
            if out[jn].start >= out[jp].end:
                self.edits.insert(out[jn].block, out[jn].start, element)
                return f"{self.blocks[out[jn].block].href}#{ident}"
        if jp is not None:
            block = out[jp].block
            self.edits.insert(block, len(self.edits.text(block)), element)
        elif jn is not None:
            block = out[jn].block
            self.edits.insert(block, 0, element)
        else:
            href = self.book.docs[-1].href
            self.edits.append(href, element)
            return f"{href}#{ident}"
        return f"{self.blocks[block].href}#{ident}"

    def _matched(self, indices, exclude: set[str] | None = None) -> int | None:
        for n, i in enumerate(indices):
            if n > 5000:
                return None
            if self.al.src_to_out[i] >= 0 and (
                exclude is None or self.al.source[i].block not in exclude
            ):
                return i
        return None

    def _render(self, page: PageInventory, region: ImageRegion, full_page: bool = False):
        """Render the region (or the page) to a new PNG asset: (href, size, dpi) or None."""
        b = region.box
        box = None
        if not full_page:
            box = Box(
                max(0.0, b.x0 - PAD),
                max(0.0, b.y0 - PAD),
                min(1.0, b.x1 + PAD),
                min(1.0, b.y1 + PAD),
            )
        width = (box.width if box else 1.0) * page.width_pt / 72
        height = (box.height if box else 1.0) * page.height_pt / 72
        dpi = DPI
        if width * height * dpi * dpi > MAX_PIXELS:
            dpi = int(math.sqrt(MAX_PIXELS / (width * height)))
        reason = "low_dpi"
        data = size = None
        if dpi >= MIN_DPI:
            out = self.ctx.workdir / "figures" / f"{region.id}.png"
            try:
                render_region(
                    self.ctx.pdf_path,
                    page.index,
                    box,
                    dpi,
                    out,
                    self.ctx.toolchain,
                    max_pixels=int(MAX_PIXELS * 1.05),
                )
                data = out.read_bytes()
                size = visible_size(data)
                reason = "blank_or_undecodable"
            except (StageError, OSError) as exc:
                reason = "render_error"
                log.info("render of %s failed: %s", region.id, type(exc).__name__)
        if size is None:
            self._issue(
                "figure.render_failed",
                Severity.MAJOR,
                "A figure could not be rendered to a usable image",
                page,
                region,
                self._anchor_near(page),
                f"{region.id} page={page.index} dpi={dpi} reason={reason}",
                "flagged",
                "The figure stays missing from the EPUB.",
            )
            return None
        stem = posixpath.join(self.asset_dir, f"qc-figure-{region.id}")
        href, n = f"{stem}.png", 1
        while href in self.book.assets:
            n += 1
            href = f"{stem}-{n}.png"
        self.book.add_asset(href, data, "image/png")
        return href, size, dpi

    def _replace(self, img: etree._Element, asset: str) -> None:
        old = self._img_href(img)
        self.images.append((img, asset))
        img.set("src", "")  # pointed at the new asset once the document is final
        if old in self.book.assets and not any(t == old for *_, t in self.book.iter_links()):
            self.book.remove_asset(old)

    def _shown_img(self, region: ImageRegion) -> etree._Element | None:
        owner = self.al.images[region.id]
        _, el = self.book.element(f"{self.blocks[owner].href}#{owner}")
        if local_name(el) == "img":
            return el
        return next(el.iter(f"{{{XHTML_NS}}}img"), None)

    def _img_href(self, img: etree._Element) -> str:
        doc = next(d for d in self.book.docs if d.tree is img.getroottree().getroot())
        return resolve_href(doc.href, img.get("src", "")).partition("#")[0]

    def _asset_ok(self, img: etree._Element) -> bool:
        asset = self.book.assets.get(self._img_href(img))
        return asset is not None and visible_size(asset.data) is not None

    # ---- reporting

    def _anchor_of(self, el: etree._Element) -> str | None:
        root = el.getroottree().getroot()
        doc = next((d for d in self.book.docs if d.tree is root), None)
        node = next((a for a in [el, *el.iterancestors()] if a.get("id")), None)
        if doc is None:
            return None
        return f"{doc.href}#{node.get('id')}" if node is not None else doc.href

    def _anchor_near(self, page: PageInventory) -> str | None:
        for p in [page.index, *range(page.index - 1, -1, -1)]:
            if (pos := self.al.first_output_position_on_page(p)) is not None:
                return f"{self.blocks[pos[0]].href}#{pos[0]}"
        return None

    def _image_only(self, page, region, anchor) -> None:
        self._issue(
            "figure.image_only",
            Severity.INFO,
            "A figure is shown as an image only, with no equivalent text",
            page,
            region,
            anchor,
            f"{region.id} page={page.index}",
            "image_fallback",
            "Words drawn inside the image are not available to search or assistive reading.",
        )

    def _record(self, action: str, target: str | None, evidence: list[str], **kw) -> None:
        self.ctx.record(FAMILY, action, target or "", evidence, **kw)

    def _issue(self, code, severity, message, page, region, anchor, evidence, action, why):
        label = page.label or (page.folio_candidates[0] if page.folio_candidates else None)
        self.ctx.issue(
            code,
            severity,
            message,
            page=page.index,
            page_label=label,
            region=region.box,
            output_anchor=anchor,
            evidence=evidence,
            action=action,
            uncertainty=why,
            material=False,
        )


def _vertical_gap(a: Box, b: Box) -> float:
    if a.y0 >= b.y1:
        return a.y0 - b.y1
    if b.y0 >= a.y1:
        return b.y0 - a.y1
    return 0.0


def _x_overlap(block: TextBlock, box: Box) -> bool:
    return min(block.box.x1, box.x1) > max(block.box.x0, box.x0)


def _ink_inside(region: Box, box: Box) -> bool:
    return box.area > 0 and region.intersection_area(box) >= FIGURE_TEXT_SHARE * box.area


def _number(value: str | None) -> int | None:
    if not value:
        return None
    digits = re.match(r"\d+", value)
    if digits:
        return int(digits.group())
    total, prev = 0, 0
    for ch in reversed(value.lower()):
        n = _ROMAN[ch]
        total, prev = (total - n, prev) if n < prev else (total + n, n)
    return total
