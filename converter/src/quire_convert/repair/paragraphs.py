"""Re-segment Calibre's paragraphs from the source lines and remove page furniture.

Calibre often runs a page's lines together, cuts a paragraph at every page break, keeps
running headers and folios, and keeps text the crop box hides. This family reads the
source lines (indentation, vertical gaps, short last lines, case and punctuation at the
join, font size) and groups them into units: a paragraph, or one line of verse or of a
list. It then edits the output to match, using the word alignment to find each unit:

1. furniture (``coverage.running_furniture``) and text that only the media box holds are
   removed, their ids moved onto the next block so links and ``page_N`` anchors survive;
2. a block holding several units is split at each unit's first word (a line break is
   inserted instead between verse or list lines);
3. consecutive blocks holding one unit are merged, across pages and documents, and a
   line-end hyphen at the join is kept or removed on evidence from the rest of the book;
4. a scene break shown only by extra vertical space gets a generated ``hr``.

Boundaries the evidence cannot settle are left as Calibre made them and reported.
"""

from __future__ import annotations

import logging
import re
import statistics
from collections import Counter
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import TYPE_CHECKING

from lxml import etree

from ..align.align import DUPLICATE, MOVED, reading_order, refresh
from ..align.coverage import FIGURE_TEXT_SHARE, note_blocks, running_furniture
from ..align.normalize import tokenize
from ..epub import inline
from ..epub.book import ContentDoc, is_block, remove_keep_tail
from ..epub.package import XHTML_NS, local_name
from ..models import (
    BLOCK_ID_PREFIX,
    Box,
    Line,
    PageInventory,
    Severity,
    SourceInventory,
    StageError,
    TextBlock,
)
from ..source import poppler

if TYPE_CHECKING:
    from ..context import ConvertContext

log = logging.getLogger(__name__)

FAMILY = "paragraphs"
INDENT_EM = 0.8  # a first-line indent, in body font sizes
SHORT_LINE = 0.12  # share of the measure a line leaves empty to count as short
VERSE_WIDTH = 0.75  # every line of a verse or list block is narrower than this share
GAP_LINES = 0.5  # extra space between blocks, in line heights, that starts a paragraph
SCENE_LINES = (1.5, 8.0)  # extra space, in line heights, that reads as a scene break
HEADING_RATIO = 1.15
MAX_LEAD = 2  # unmatched words allowed before a unit's first matched output word
MOVED_ON_PAGE = 3  # moved words on a multi-column page that make its order disputed
TOOL_TIMEOUT = 120.0

START, LINE, UNSURE = "start", "line", "unsure"
HYPHENS = "-‐­"
TERMINAL = '.!?…:;"”’»)]'
SCENE_MARKS = frozenset("*#⁂~•·✱✻❦")
_PUNCT = ".,;:!?\"'()[]“”‘’«»…"
_MEDIA = frozenset({"img", "svg", "video", "audio", "object", "embed", "iframe", "math", "canvas"})
_MERGEABLE = frozenset({"p", "div"})
_FLOW_PARENTS = frozenset({"body", "div", "section"})
_GENERATED_ID = re.compile(rf"{BLOCK_ID_PREFIX}b\d+")


# --------------------------------------------------------------------------- text edits


def replace_text(el: etree._Element, start: int, end: int, new: str) -> bool:
    """Replace ``text_of(el)[start:end]`` with ``new`` when the range lies in one text
    node; returns False (and changes nothing) otherwise."""
    node, attr, i = inline.locate(el, start)
    node2, attr2, i2 = inline.locate(el, end) if end > start else (node, attr, i)
    if node is not node2 or attr != attr2:
        return False
    value = (node.text if attr == "text" else node.tail) or ""
    value = value[:i] + new + value[i2:]
    if attr == "text":
        node.text = value or None
    else:
        node.tail = value or None
    return True


# --------------------------------------------------------------------------- source units


@dataclass
class _Ln:
    page: PageInventory
    block: TextBlock
    k: int  # line index in the block
    left: float  # column bounds of the line's block
    right: float
    heading: bool
    note: bool
    verse: bool

    @property
    def line(self) -> Line:
        return self.block.lines[self.k]

    @property
    def text(self) -> str:
        return self.line.text


@dataclass
class _Unit:
    index: int
    page: int
    block: str  # source block of the first line
    boundary: str  # START, LINE (break inside verse or a list) or UNSURE
    rule: str
    region: Box
    note: bool
    scene_before: bool = False


@dataclass
class _Hyphen:
    left: tuple[str, int, int]  # (source block, line, word) of the "conti-" word
    right: tuple[str, int, int]  # of the word that continues it
    join: bool
    rule: str
    counts: str  # evidence: "joined=N hyphenated=M"


@dataclass
class _Segmentation:
    unit_of: dict[tuple[str, int], int] = field(default_factory=dict)  # (block, line)
    units: list[_Unit] = field(default_factory=list)
    joins: dict[tuple[str, int], str] = field(default_factory=dict)  # continued line -> rule
    hyphens: list[_Hyphen] = field(default_factory=list)


def _ends_hyphen(text: str) -> bool:
    word = text.split()[-1] if text.split() else ""
    return len(word) >= 2 and word[-1] in HYPHENS and word[-2].isalpha()


def _first_alpha(text: str) -> str:
    return next((ch for ch in text if ch.isalpha()), "")


def _core(word: str) -> str:
    return word.strip(_PUNCT).casefold()


def _is_symbol(text: str) -> bool:
    chars = set(text.replace(" ", ""))
    return bool(chars) and chars <= SCENE_MARKS


class _Segmenter:
    """Groups source lines into units, book-wide, in reading order."""

    def __init__(self, ctx: ConvertContext, furniture: set[str], notes: set[str]):
        self.source = ctx.source
        self.furniture = furniture
        self.notes = notes
        self.out = _Segmentation()
        self.figure_text = _figure_text(self.source)
        body_lines = [
            line
            for page in self.source.pages
            for block in page.blocks
            if self._body(block)
            for line in block.lines
        ]
        sizes = Counter(w.font_size for ln in body_lines for w in ln.words if w.font_size)
        self.body_size = sizes.most_common(1)[0][0] if sizes else 12.0
        plain = [ln for ln in body_lines if _size(ln) == self.body_size] or body_lines
        widths = sorted(ln.box.width for ln in plain)
        self.measure = widths[int(0.9 * (len(widths) - 1))] if widths else 0.8
        self.line_height = statistics.median(ln.box.height for ln in plain) if plain else 0.02
        gaps = [
            b.lines[k].box.y0 - b.lines[k - 1].box.y1
            for page in self.source.pages
            for b in page.blocks
            if self._body(b)
            for k in range(1, len(b.lines))
        ]
        self.line_gap = statistics.median(gaps) if gaps else 0.0
        self.vocab = self._vocabulary()
        self.columns_seen: Counter[tuple[float, float]] = Counter()

    def _body(self, block: TextBlock) -> bool:
        return block.id not in self.furniture and block.id not in self.figure_text

    # ---- metrics

    def _vocabulary(self) -> Counter[str]:
        return mid_line_vocabulary(self.source, self._body)

    def _indent_tol(self, page: PageInventory) -> float:
        return INDENT_EM * self.body_size / (page.width_pt or 1.0)

    def _verse(self, block: TextBlock) -> bool:
        return (
            len(block.lines) >= 2
            and all(ln.box.width < VERSE_WIDTH * self.measure for ln in block.lines)
            and not any(_ends_hyphen(ln.text) for ln in block.lines)
        )

    def _heading(self, block: TextBlock) -> bool:
        sizes = [w.font_size for ln in block.lines for w in ln.words if w.font_size]
        if sizes and statistics.median(sizes) >= self.body_size * HEADING_RATIO:
            return True
        words = [w for ln in block.lines for w in ln.words]
        return len(block.lines) == 1 and bool(words) and all(w.bold for w in words)

    def _columns(self, page: PageInventory, blocks: list[TextBlock]) -> list[tuple[float, float]]:
        return [
            (min(ln.box.x0 for ln in b.lines), max(ln.box.x1 for ln in b.lines))
            for b in blocks
            if len(b.lines) >= 2 and not self._verse(b) and not self._heading(b)
        ]

    def _bounds(self, block: TextBlock, columns, page: PageInventory) -> tuple[float, float]:
        if len(block.lines) >= 2 and not self._verse(block):
            return min(ln.box.x0 for ln in block.lines), max(ln.box.x1 for ln in block.lines)
        tol = self._indent_tol(page)
        x0 = block.box.x0
        fits = [c for c in columns if c[0] - tol <= x0 < c[1]]
        if fits:
            return max(fits, key=lambda c: c[0])
        if self.columns_seen:
            return self.columns_seen.most_common(1)[0][0]
        return x0, x0 + self.measure

    # ---- segmentation

    def run(self) -> _Segmentation:
        pages = []
        for page in self.source.pages:
            blocks = [b for b in reading_order(page) if self._body(b) and b.lines]
            columns = self._columns(page, blocks)
            self.columns_seen.update((round(a, 2), round(b, 2)) for a, b in columns)
            pages.append((page, blocks, columns))
        self.indent_style = self._indent_style(pages)
        self.para_gap = self._paragraph_gap(pages)
        prev: _Ln | None = None
        for page, blocks, columns in pages:
            prev_body = prev
            for block in blocks:
                left, right = self._bounds(block, columns, page)
                lines = [
                    _Ln(
                        page,
                        block,
                        k,
                        left,
                        right,
                        self._heading(block),
                        block.id in self.notes,
                        self._verse(block),
                    )
                    for k in range(len(block.lines))
                ]
                for ln in lines:
                    after = prev_body if not ln.note else None
                    self._place(after if ln.k == 0 else lines[ln.k - 1], ln)
                    if not ln.note:
                        prev_body = ln
            prev = prev_body
        return self.out

    def _indent_style(self, pages) -> bool:
        """Whether the book starts paragraphs with a first-line indent."""
        indented = blocks = 0
        for page, page_blocks, _ in pages:
            tol = self._indent_tol(page)
            for b in page_blocks:
                if len(b.lines) < 2 or self._verse(b) or self._heading(b):
                    continue
                blocks += 1
                left = min(ln.box.x0 for ln in b.lines)
                xs = [ln.box.x0 - left >= tol for ln in b.lines]
                last = len(xs) - 1
                indented += sum(1 for k, x in enumerate(xs) if x and (k == last or not xs[k + 1]))
        return indented >= max(2, 0.3 * blocks)

    def _paragraph_gap(self, pages) -> float:
        """Usual space above a paragraph: the line spacing when paragraphs are indented,
        else the median space between body blocks. A scene break stands clearly above it."""
        if self.indent_style:
            return self.line_gap
        gaps = []
        for _, blocks, _ in pages:
            plain = [b for b in blocks if self._running(b)]
            for a, b in zip(plain, plain[1:], strict=False):
                if a.id in self.notes or b.id in self.notes or b.box.y0 < a.box.y1:
                    continue
                gaps.append(b.lines[0].box.y0 - a.lines[-1].box.y1)
        return statistics.median(gaps) if gaps else self.line_gap

    def _running(self, block: TextBlock) -> bool:
        """Body prose: not a heading, verse, ornament or a lone short line."""
        if self._heading(block) or self._verse(block) or _is_symbol(block.text):
            return False
        return len(block.lines) >= 2 or block.lines[0].box.width >= 0.5 * self.measure

    def _place(self, prev: _Ln | None, ln: _Ln) -> None:
        if prev is None or prev.note != ln.note:
            kind, rule = START, "first_line"
        else:
            kind, rule = self._boundary(prev, ln)
        out = self.out
        key = (ln.block.id, ln.k)
        if kind is None:
            out.unit_of[key] = out.unit_of[(prev.block.id, prev.k)]
            if prev.block is not ln.block:
                out.joins[key] = rule
            if _ends_hyphen(prev.text) and _first_alpha(ln.text).islower():
                out.hyphens.append(self._hyphen(prev, ln))
            return
        region = ln.line.box
        if prev is not None and prev.page is ln.page:
            region = prev.line.box.union(region)
        unit = _Unit(len(out.units), ln.page.index, ln.block.id, kind, rule, region, ln.note)
        unit.scene_before = kind == START and self._scene_gap(prev, ln)
        out.units.append(unit)
        out.unit_of[key] = unit.index

    def _boundary(self, prev: _Ln, ln: _Ln) -> tuple[str | None, str]:
        """(kind, rule) of the boundary before ``ln``; kind None continues the unit."""
        same_block = prev.block is ln.block
        if ln.verse or prev.verse:
            return (LINE, "verse_line") if same_block else (START, "verse_line")
        if prev.heading or ln.heading:
            kind = None if same_block and prev.heading == ln.heading else START
            return kind, "heading_edge"
        if _is_symbol(prev.text) or _is_symbol(ln.text):
            return START, "ornament_line"
        if not same_block:
            if prev.page is ln.page and not self._new_column(prev, ln):
                gap = ln.line.box.y0 - prev.line.box.y1
                if gap > self.line_gap + GAP_LINES * self.line_height:
                    return START, "block_gap"
                return START, "new_block"
        indented = ln.line.box.x0 - ln.left >= self._indent_tol(ln.page)
        if indented:
            return START, "first_indent"
        if _ends_hyphen(prev.text) and _first_alpha(ln.text).islower():
            return None, "hyphen_break"
        short = prev.right - prev.line.box.x1 > SHORT_LINE * (prev.right - prev.left)
        terminal = prev.text.rstrip().endswith(tuple(TERMINAL))
        if short:
            if not terminal and _first_alpha(ln.text).islower():
                return (None, "open_sentence") if self.indent_style else (UNSURE, "short_open")
            if self.indent_style:
                return UNSURE, "short_unindented"
            return START, "short_line"
        if not terminal:
            return None, "open_sentence"
        if same_block:
            return None, "full_line"
        if self.indent_style:
            return None, "no_indent"
        return UNSURE, "full_terminal"

    @staticmethod
    def _new_column(prev: _Ln, ln: _Ln) -> bool:
        return ln.block.box.y0 < prev.block.box.y0 and ln.block.box.x0 > prev.block.box.x1 - 0.02

    def _scene_gap(self, prev: _Ln | None, ln: _Ln) -> bool:
        """Extra vertical space between two body paragraphs of one column, with nothing
        drawn in it: an intentional scene break."""
        if prev is None or prev.page is not ln.page or prev.block is ln.block:
            return False
        if prev.heading or ln.heading or prev.verse or ln.verse or self._new_column(prev, ln):
            return False
        if self.body_size not in (_size(prev.line), _size(ln.line)):
            return False
        if _size(prev.line) != _size(ln.line):
            return False
        if min(prev.line.box.width, ln.line.box.width) < 0.5 * (prev.right - prev.left):
            return False
        extra = ln.line.box.y0 - prev.line.box.y1 - max(self.para_gap, self.line_gap)
        lo, hi = (n * self.line_height for n in SCENE_LINES)
        if not lo <= extra <= hi:
            return False
        between = Box(prev.left, prev.line.box.y1, prev.right, ln.line.box.y0)
        return not any(r.box.intersection_area(between) > 0 for r in ln.page.images)

    def _hyphen(self, prev: _Ln, ln: _Ln) -> _Hyphen:
        join, rule, counts = hyphen_join(
            prev.line.words[-1].text, ln.line.words[0].text, self.vocab
        )
        return _Hyphen(
            (prev.block.id, prev.k, len(prev.line.words) - 1),
            (ln.block.id, ln.k, 0),
            join,
            rule,
            counts,
        )


def mid_line_vocabulary(
    source: SourceInventory, keep: Callable[[TextBlock], bool] = lambda block: True
) -> Counter[str]:
    """Words as printed mid-line, so hyphen decisions can consult the rest of the book."""
    vocab: Counter[str] = Counter()
    for page in source.pages:
        for block in page.blocks:
            if not keep(block):
                continue
            broken = False
            for line in block.lines:
                words = [w.text for w in line.words]
                hyphen = _ends_hyphen(line.text)
                lo = 1 if broken else 0
                hi = len(words) - (1 if hyphen else 0)
                vocab.update(_core(w) for w in words[lo:hi])
                broken = hyphen
    return vocab


def hyphen_join(left_word: str, right_word: str, vocab: Counter[str]) -> tuple[bool, str, str]:
    """Whether a line-end hyphen between two words breaks one word, decided on the book's
    own usage: ``(join, rule, counts)``."""
    left = _core(left_word.rstrip(HYPHENS))
    right = _core(right_word)
    joined, hyphenated = vocab[left + right], vocab[f"{left}-{right}"]
    counts = f"joined={joined} hyphenated={hyphenated}"
    if left_word.endswith("\u00ad"):
        return True, "soft_hyphen", counts
    if joined != hyphenated:
        return joined > hyphenated, "book_usage", counts
    if joined:
        return False, "usage_tie", counts
    if vocab[left] and vocab[right]:
        # Both halves are words of the book: read it as a compound ("well-known").
        return False, "compound_parts", counts
    return True, "never_mid_line", counts


def _size(line: Line) -> float | None:
    sizes = [w.font_size for w in line.words if w.font_size]
    return statistics.median(sizes) if sizes else None


def _figure_text(source) -> set[str]:
    inside = set()
    for page in source.pages:
        for block in page.blocks:
            area = block.box.area
            if area and any(
                r.box.intersection_area(block.box) >= FIGURE_TEXT_SHARE * area for r in page.images
            ):
                inside.add(block.id)
    return inside


# --------------------------------------------------------------------------- output edits


@dataclass
class _Seg:
    """A piece of an output block after cutting, in spine order."""

    el: etree._Element
    origin: str  # "href#id" of the output block it came from
    kind: str  # "text", "drop", "empty" (spacer) or "other"
    first: int = -1  # unit of the first and last aligned word
    last: int = -1
    first_tok: int = -1  # output token index of those words
    last_tok: int = -1
    note: bool = False
    reason: str = ""
    evidence: list[str] = field(default_factory=list)
    removed: bool = False


@dataclass
class _Pending:
    action: str
    el: etree._Element
    evidence: list[str]
    before: str | None
    after: str | None


class _Rebuild:
    def __init__(self, ctx: ConvertContext, seg: _Segmentation, furniture: set[str]):
        self.ctx = ctx
        self.al = ctx.alignment
        self.seg = seg
        self.furniture = furniture
        self.blocks = {b.id: b for p in ctx.source.pages for b in p.blocks}
        al = self.al
        self.unit_of = [seg.unit_of.get((t.block, t.line), -1) for t in al.source]
        self.unit_first: dict[int, int] = {}
        for i, u in enumerate(self.unit_of):
            if u >= 0:
                self.unit_first.setdefault(u, i)
        self.word_tokens: dict[tuple[str, int, int], list[int]] = {}
        for i, t in enumerate(al.source):
            self.word_tokens.setdefault((t.block, t.line, t.word), []).append(i)
        self.disputed: list[tuple[int, int]] = []  # (page, moved words)
        self.frozen = self._disputed_pages()
        self.seams: dict[int, tuple[int, _Hyphen]] = {}  # right output token -> (left, hyphen)
        self.pending: list[_Pending] = []
        self.media_tokens: dict[int, list[str] | None] = {}
        self.linked = {target for *_, target in ctx.book.iter_links()}

    def run(self) -> None:
        drops = self._drops()
        inner = self._hyphen_edits()
        segments: list[_Seg] = []
        for block in self.ctx.blocks:
            segments.extend(self._cut(block, drops, inner.get(block.id, [])))
        mapping: dict[str, str] = {}
        self._remove(segments, mapping)
        self._merge(segments)
        self._scene_breaks(segments)
        self._drop_empty_docs(mapping)
        if mapping:
            self.ctx.book.rewrite_links(mapping)
        self.ctx.book.ensure_block_ids()
        for p in self.pending:
            self.ctx.record(
                FAMILY, p.action, self._anchor(p.el), p.evidence, before=p.before, after=p.after
            )

    # ---- token labels

    def _matched(self, j: int) -> int:
        """Source index of an in-order output token, else -1."""
        i = self.al.out_to_src[j]
        return i if i >= 0 and self.al.out_kind[j] not in (MOVED, DUPLICATE) else -1

    def _unit(self, j: int) -> int:
        i = self._matched(j)
        return self.unit_of[i] if i >= 0 else -1

    def _disputed_pages(self) -> set[int]:
        al, frozen = self.al, set()
        for page in self.ctx.source.pages:
            if "multi_column" not in page.flags:
                continue
            moved = [
                i
                for i in al.source_tokens(page.index)
                if al.src_to_out[i] >= 0 and al.out_kind[al.src_to_out[i]] == MOVED
            ]
            if len(moved) < MOVED_ON_PAGE:
                continue
            frozen.add(page.index)
            self.disputed.append((page.index, len(moved)))
        return frozen

    # ---- drops: furniture and out-of-crop text

    def _drops(self) -> dict[int, tuple[str, list[str]]]:
        al, drops = self.al, {}
        for block in self.ctx.blocks:
            js = al.output_block_tokens(block.id)
            if not js or block.alignment == "generated":
                continue
            furn = [
                k
                for k, j in enumerate(js)
                if self._matched(j) >= 0 and al.source[self._matched(j)].block in self.furniture
            ]
            if furn and not self._protected(block.id, [js[k] for k in furn]):
                run = js[furn[0] : furn[-1] + 1]
                if all(self._matched(j) < 0 or self._unit(j) < 0 for j in run):
                    src = al.source[self._matched(js[furn[0]])]
                    for j in run:
                        drops[j] = ("furniture", [src.block, f"page={src.page}"])
            self._crop_drops(block.id, js, drops)
        return drops

    def _protected(self, block_id: str, furn: list[int]) -> bool:
        """Furniture words that equal an unmatched body block on their page are that block:
        a chapter opening that repeats the running title."""
        al = self.al
        text = " ".join(al.output[j].norm for j in furn)
        page = al.source[self._matched(furn[0])].page
        for b in self.ctx.source.pages[page].blocks:
            if b.id in self.furniture or " ".join(t.norm for t in tokenize(b.text)) != text:
                continue
            elsewhere = any(
                al.src_to_out[i] >= 0 and al.output[al.src_to_out[i]].block != block_id
                for i in al.source_block_tokens(b.id)
            )
            if not elsewhere:
                return True
        return False

    def _crop_drops(self, block_id: str, js: list[int], drops) -> None:
        """Unaligned runs that the page's media box holds but its crop box does not."""
        al = self.al
        whole = all(al.out_to_src[j] < 0 for j in js)
        k = 0
        while k < len(js):
            if al.out_to_src[js[k]] >= 0:
                k += 1
                continue
            end = k
            while end < len(js) and al.out_to_src[js[end]] < 0:
                end += 1
            run = js[k:end]
            if whole or len(run) >= 3:
                page = self._outside_crop([al.output[j].norm for j in run], al.page_near(run[0]))
                if page is not None:
                    for j in run:
                        drops[j] = ("out_of_crop", [block_id, f"page={page}", "box=media_box"])
            k = end

    def _outside_crop(self, norms: list[str], near: int | None) -> int | None:
        if near is None:
            return None
        pages = self.ctx.source.pages
        for p in (near, near - 1, near + 1):
            if not 0 <= p < len(pages) or pages[p].crop_box == pages[p].media_box:
                continue
            media = self._media(p)
            crop = [self.al.source[i].norm for i in self.al.source_tokens(p)]
            if media and _contains(media, norms) and not _contains(crop, norms):
                return p
        return None

    def _media(self, page: int) -> list[str] | None:
        """Words of one page as ``pdftotext -bbox-layout`` reads them inside the media box."""
        if page not in self.media_tokens:
            out = self.ctx.workdir / FAMILY / f"media-{page + 1}.xhtml"
            out.parent.mkdir(parents=True, exist_ok=True)
            try:
                tool = poppler.tool_path(self.ctx.toolchain, "pdftotext")
                cmd = [tool, "-f", str(page + 1), "-l", str(page + 1), "-bbox-layout"]
                cmd += ["-enc", "UTF-8", str(self.ctx.pdf_path), str(out)]
                poppler.run(cmd, TOOL_TIMEOUT, stage="repair")
                words = [
                    w.text
                    for blocks in poppler.parse_bbox_layout(out)
                    for b in blocks
                    for ln in b.lines
                    for w in ln.words
                ]
                self.media_tokens[page] = [t.norm for w in words for t in tokenize(w)]
            except (StageError, OSError) as exc:
                log.warning("media-box text of page %d unavailable: %s", page, exc)
                self.media_tokens[page] = None
        return self.media_tokens[page]

    # ---- hyphens inside one output block

    def _hyphen_edits(self) -> dict[str, list[tuple[int, int, str, _Hyphen]]]:
        """Per output block, (start, end, replacement, hyphen) edits; seams between blocks
        are kept in ``self.seams`` for the merge step."""
        al, edits = self.al, {}
        for h in self.seg.hyphens:
            left = [self._out(i) for i in self.word_tokens.get(h.left, [])]
            right = [self._out(i) for i in self.word_tokens.get(h.right, [])]
            if not left or not right or left[-1] < 0 or right[0] < 0:
                continue
            jx, jy = left[-1], right[0]
            tx, ty = al.output[jx], al.output[jy]
            if tx.block != ty.block:
                self.seams[jy] = (jx, h)
                continue
            text = self._block_text(tx.block)
            edit = None
            if jx == jy:
                cut = self._fragment_length(h, text[tx.start : tx.end])
                if not h.join and cut:
                    edit = (tx.start + cut, tx.start + cut, "-")
            else:
                between = text[tx.end : ty.start]
                if h.join and re.fullmatch(rf"[{HYPHENS}]\s*", between):
                    edit = (tx.end, ty.start, "")
                elif not h.join and re.fullmatch(rf"[{HYPHENS}]\s+", between):
                    edit = (tx.end + 1, ty.start, "")
            if edit:
                edits.setdefault(tx.block, []).append((*edit, h))
        return edits

    def _out(self, i: int) -> int:
        j = self.al.src_to_out[i]
        return j if j >= 0 and self.al.out_kind[j] not in (MOVED, DUPLICATE) else -1

    def _fragment_length(self, h: _Hyphen, word: str) -> int | None:
        block, line, k = h.left
        left = self.blocks[block].lines[line].words[k].text.rstrip(HYPHENS).strip(_PUNCT)
        n = len(left)
        return n if 0 < n < len(word) and word[:n].casefold() == left.casefold() else None

    def _block_text(self, block_id: str) -> str:
        return next(b.text for b in self.ctx.blocks if b.id == block_id)

    # ---- cutting blocks into segments

    def _cut(self, block, drops, edits) -> list[_Seg]:
        al = self.al
        _, el = self.ctx.book.element(f"{block.href}#{block.id}")
        origin = f"{block.href}#{block.id}"
        js = al.output_block_tokens(block.id)
        if block.alignment == "generated" or not js:
            return [_Seg(el, origin, "other" if _has_content(el) else "empty")]
        labels = [("drop" if j in drops else self._unit(j)) for j in js]
        events: list[tuple[int, str, object]] = []
        prev = None
        for k, lab in enumerate(labels):
            if lab == -1:
                continue
            if prev is not None and labels[prev] != lab:
                if lab == "drop" or labels[prev] == "drop":
                    at = k if lab == "drop" else prev + 1
                    events.append((al.output[js[at]].start, "cut", None))
                elif isinstance(lab, int) and lab > labels[prev]:
                    cut = self._paragraph_cut(js, k, prev, lab)
                    if cut is not None:
                        events.append(cut)
            prev = k
        for start, end, new, h in edits:
            events.append((start, "edit", (end, new, h)))
        if _is_block_child(el):
            events = [e for e in events if e[1] != "cut"]
        pieces: list[tuple[etree._Element, int]] = []
        text = block.text
        for off, kind, data in sorted(events, key=lambda e: -e[0]):
            if kind == "cut":
                pieces.append((inline.split_at(el, off), off))
            elif kind == "line":
                inline.insert_at(el, off, el.makeelement(f"{{{XHTML_NS}}}br", {}))
            else:
                end, new, h = data
                before = text[max(0, off - 20) : end + 20]
                if replace_text(el, off, end, new):
                    after = text[max(0, off - 20) : off] + new + text[end : end + 20]
                    self._hyphen_record(el, h, before, after)
        pieces.append((el, 0))
        pieces.reverse()
        segs = []
        for n, (piece, start) in enumerate(pieces):
            stop = pieces[n + 1][1] if n + 1 < len(pieces) else len(text) + 1
            inside = [k for k, j in enumerate(js) if start <= al.output[js[k]].start < stop]
            segs.append(self._segment(piece, origin, [js[k] for k in inside], drops))
        return segs

    def _paragraph_cut(self, js, k, prev, unit) -> tuple[int, str, None] | None:
        u = self.seg.units[unit]
        if u.boundary == UNSURE or u.page in self.frozen:
            return None
        lead = self._matched(js[k]) - self.unit_first[unit]
        if lead < 0 or lead > MAX_LEAD or k - lead <= prev:
            if u.boundary == START:
                u.boundary, u.rule = UNSURE, "first_word_unaligned"
            return None
        if any(self.al.out_to_src[js[m]] >= 0 for m in range(k - lead, k)):
            return None
        at = self.al.output[js[k - lead]].start
        return at, "line" if u.boundary == LINE else "cut", None

    def _segment(self, el, origin, js: list[int], drops) -> _Seg:
        if not js:
            return _Seg(el, origin, "other" if _has_content(el) else "empty")
        if all(j in drops for j in js):
            reason, evidence = drops[js[0]]
            return _Seg(el, origin, "drop", reason=reason, evidence=evidence)
        units = [(j, self._unit(j)) for j in js if self._unit(j) >= 0]
        seg = _Seg(el, origin, "text")
        if units:
            seg.first_tok, seg.first = units[0]
            seg.last_tok, seg.last = units[-1]
            seg.note = all(self.seg.units[u].note for _, u in units)
        return seg

    def _hyphen_record(self, el, h: _Hyphen, before: str, after: str) -> None:
        action = "join_hyphenated" if h.join else "keep_hyphen"
        blocks = [h.left[0]] if h.left[0] == h.right[0] else [h.left[0], h.right[0]]
        evidence = [*blocks, f"rule={h.rule}", *h.counts.split()]
        self.pending.append(_Pending(action, el, evidence, before, after))

    # ---- removal

    def _remove(self, segments: list[_Seg], mapping: dict[str, str]) -> None:
        for n, seg in enumerate(segments):
            if seg.kind != "drop":
                continue
            doc = self._doc(seg.el)
            ids = [
                ident
                for x in seg.el.iter(etree.Element)
                if (ident := x.get("id"))
                and (not _GENERATED_ID.fullmatch(ident) or f"{doc.href}#{ident}" in self.linked)
            ]
            target, at_start = self._id_target(segments, n)
            if ids and target is not None:
                tdoc = self._doc(target.el)
                for ident in reversed(ids):
                    span = target.el.makeelement(f"{{{XHTML_NS}}}span", {"id": ident})
                    _place_span(target.el, span, at_start)
                    if tdoc is not doc:
                        mapping[f"{doc.href}#{ident}"] = f"{tdoc.href}#{ident}"
            action = "remove_furniture" if seg.reason == "furniture" else "remove_out_of_crop"
            self.ctx.record(
                FAMILY, action, seg.origin, seg.evidence, before=inline.text_of(seg.el), after=None
            )
            remove_keep_tail(seg.el)
            seg.removed = True

    @staticmethod
    def _id_target(segments: list[_Seg], n: int) -> tuple[_Seg | None, bool]:
        for seg in segments[n + 1 :]:
            if not seg.removed and seg.kind in ("text", "other"):
                return seg, True
        for seg in reversed(segments[:n]):
            if not seg.removed and seg.kind in ("text", "other"):
                return seg, False
        return None, True

    # ---- merging

    def _merge(self, segments: list[_Seg]) -> None:
        prev: _Seg | None = None
        for seg in segments:
            if seg.removed or seg.kind == "empty" or (seg.kind == "text" and seg.note):
                continue
            if seg.kind != "text" or not _mergeable(seg.el) or seg.first < 0:
                prev = None
                continue
            u = self.seg.units[seg.first]
            if (
                prev is not None
                and prev.last == seg.first
                and u.page not in self.frozen
                and self._page_of(seg.first_tok) not in self.frozen
            ):
                self._join(prev, seg)
                continue
            prev = seg

    def _page_of(self, j: int) -> int:
        return self.al.source[self._matched(j)].page

    def _join(self, prev: _Seg, seg: _Seg) -> None:
        left, right = inline.text_of(prev.el), inline.text_of(seg.el)
        sep, edit = " ", None
        seam = self.seams.get(seg.first_tok)
        if seam and seam[0] == prev.last_tok and left and left[-1] in HYPHENS:
            sep, h = "", seam[1]
            if h.join:
                replace_text(prev.el, len(left) - 1, len(left), "")
            edit = h
        src = self.al.source[self._matched(seg.first_tok)]
        rule = self.seg.joins.get((src.block, src.line), "same_unit")
        before = f"{left[-30:]} ¶ {right[:30]}"
        joined = (left[:-1] if edit and edit.join else left)[-30:] + sep + right[:30]
        self.pending.append(
            _Pending(
                "merge_paragraph",
                prev.el,
                [src.block, f"page={src.page}", f"rule={rule}"],
                before,
                joined,
            )
        )
        if edit:
            self._hyphen_record(prev.el, edit, f"{left[-20:]}|{right[:20]}", joined[-50:])
        inline.merge(prev.el, seg.el, sep)
        for p in self.pending:
            if p.el is seg.el:
                p.el = prev.el
        seg.removed = True
        prev.last, prev.last_tok = seg.last, seg.last_tok

    # ---- scene breaks

    def _scene_breaks(self, segments: list[_Seg]) -> None:
        wanted = {u.index for u in self.seg.units if u.scene_before}
        for seg in segments:
            if seg.removed or seg.kind != "text" or seg.first not in wanted:
                continue
            if self.unit_first[seg.first] != self._matched(seg.first_tok):
                continue
            previous = seg.el.getprevious()
            if previous is not None and "qc-scene-break" in (previous.get("class") or ""):
                continue
            hr = seg.el.makeelement(
                f"{{{XHTML_NS}}}hr", {"class": "qc-scene-break", "data-qc-generated": ""}
            )
            seg.el.addprevious(hr)
            u = self.seg.units[seg.first]
            self.pending.append(
                _Pending("insert_scene_break", seg.el, [u.block, f"page={u.page}"], None, None)
            )
            wanted.discard(seg.first)

    # ---- documents

    def _drop_empty_docs(self, mapping: dict[str, str]) -> None:
        book = self.ctx.book
        for doc in list(book.docs):
            if len(book.docs) == 1 or _has_content(doc.body):
                continue
            k = book.docs.index(doc)
            dest = book.docs[k + 1] if k + 1 < len(book.docs) else book.docs[k - 1]
            mapping[doc.href] = dest.href
            for x in doc.body.iter(etree.Element):
                if x.get("id"):
                    mapping[f"{doc.href}#{x.get('id')}"] = dest.href
            for old, new in list(mapping.items()):
                if new.partition("#")[0] == doc.href:
                    mapping[old] = dest.href
            book.docs.remove(doc)
            self.ctx.record(FAMILY, "remove_empty_document", doc.href, [doc.href])

    def _doc(self, el: etree._Element) -> ContentDoc:
        root = el.getroottree().getroot()
        return next(d for d in self.ctx.book.docs if d.tree is root)

    def _anchor(self, el: etree._Element) -> str:
        doc = self._doc(el)
        ident = el.get("id")
        return f"{doc.href}#{ident}" if ident else doc.href


def _contains(hay: list[str], seq: list[str]) -> bool:
    n = len(seq)
    return n > 0 and any(hay[k : k + n] == seq for k in range(len(hay) - n + 1))


def _has_content(el: etree._Element) -> bool:
    if "".join(el.itertext()).strip():
        return True
    return any(local_name(x) in _MEDIA for x in el.iter(etree.Element))


def _is_block_child(el: etree._Element) -> bool:
    """A block with nested blocks inside: cutting it would tear the nesting."""
    return any(is_block(x) for x in el.iterdescendants())


def _mergeable(el: etree._Element) -> bool:
    parent = el.getparent()
    return (
        local_name(el) in _MERGEABLE
        and parent is not None
        and local_name(parent) in _FLOW_PARENTS
        and not _is_block_child(el)
    )


def _place_span(target: etree._Element, span: etree._Element, at_start: bool) -> None:
    if local_name(target) == "img":
        (target.addprevious if at_start else target.addnext)(span)
        return
    offset = 0 if at_start else len(inline.text_of(target))
    inline.insert_at(target, offset, span)


# --------------------------------------------------------------------------- entry point


def apply(ctx: ConvertContext) -> None:
    """Re-segment paragraphs, join words hyphenated across lines, remove page furniture."""
    if ctx.alignment is None:
        refresh(ctx)
    furniture = running_furniture(ctx.source)
    notes = note_blocks(ctx.source, furniture)
    seg = _Segmenter(ctx, furniture, notes).run()
    before = len(ctx.blocks)
    rebuild = _Rebuild(ctx, seg, furniture)
    rebuild.run()
    refresh(ctx)
    _report(ctx, seg, rebuild.disputed)
    log.info("paragraphs: %d blocks -> %d", before, len(ctx.blocks))


def _report(ctx: ConvertContext, seg: _Segmentation, disputed: list[tuple[int, int]]) -> None:
    al = ctx.alignment
    first: dict[int, int] = {}
    for i, t in enumerate(al.source):
        first.setdefault(seg.unit_of.get((t.block, t.line), -1), i)
    for u in seg.units:
        if u.boundary != UNSURE:
            continue
        page = ctx.source.pages[u.page]
        ctx.issue(
            "paragraphs.uncertain_split",
            Severity.MINOR,
            "A paragraph boundary is uncertain; the converted break was kept",
            page=u.page,
            page_label=page.label,
            region=u.region,
            output_anchor=al.anchor_near_source(first[u.index]) if u.index in first else None,
            evidence=f"{u.block} page={u.page} rule={u.rule}",
            action="flagged",
            uncertainty="Indentation, spacing and punctuation disagree about a new paragraph.",
        )
    for index, moved in disputed:
        pos = al.first_output_position_on_page(index)
        ctx.issue(
            "paragraphs.column_order",
            Severity.MAJOR,
            "Competing column orders on a page; its paragraphs were left as converted",
            page=index,
            page_label=ctx.source.pages[index].label,
            region=Box(0.0, 0.0, 1.0, 1.0),
            output_anchor=f"{al.hrefs[pos[0]]}#{pos[0]}" if pos else None,
            evidence=f"moved={moved} page={index}",
            action="flagged",
            uncertainty="Source order follows the detected columns; the page may read row by "
            "row or hold parallel texts.",
        )
