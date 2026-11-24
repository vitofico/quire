"""Word-level map between the PDF's text and the EPUB's blocks.

The source stream is every word of every page, text blocks in reading order; the output
stream is every word of every non-generated block in spine order. Both are matched on
their normalized forms:

1. word 4-grams that occur exactly once on each side are anchors; the longest chain of
   anchors that is increasing on both sides fixes the in-order skeleton;
2. each gap between anchors is filled by a small dynamic program that allows fuzzy word
   equality and two-to-one merges (hyphenation, split words), or, when the gap is too
   large, by recursing on 3-, 2- and 1-grams unique inside the gap;
3. output words still unmatched are seeded with 3-grams unique in the whole source:
   a match on source words nobody claimed is text moved out of order, a match on
   claimed words is a duplicate.

Repeated text never seeds an anchor unless it is unique, so a running title or a repeated
heading is placed by its neighbours; when a gap holds a different number of copies on
each side the placement is a tie and the block is marked ambiguous.
"""

from __future__ import annotations

import itertools
import logging
import time
from bisect import bisect_left
from dataclasses import dataclass
from typing import TYPE_CHECKING, NamedTuple

from lxml import etree

from ..models import Box, ImageRegion, OutputBlock, PageInventory, SourceSpan, TextBlock
from ..source import layout
from .normalize import similar, tokenize

if TYPE_CHECKING:
    from ..context import ConvertContext
    from ..epub.book import Book
    from ..models import SourceInventory

log = logging.getLogger(__name__)

ANCHOR_N = 4
SEED_N = 3
MAX_CELLS = 40_000  # largest gap filled by the dynamic program directly
SOLID_RUN = 3  # matched words in a row that stand on their own inside a gap
NEAR = 2  # unmatched words allowed between a short run and its neighbour
GENERATED_ATTR = "data-qc-generated"
SOURCE_ATTR = "data-qc-source"  # space-separated ImageRegion ids an output figure/img shows

EXACT, FUZZY, MOVED, DUPLICATE = "exact", "fuzzy", "moved", "duplicate"
_EXACT_SCORE, _FUZZY_SCORE = 3, 2


@dataclass(frozen=True, slots=True)
class SourceToken:
    norm: str
    page: int
    block: str  # TextBlock id
    line: int  # line index in the block
    word: int  # word index in the line
    page_word: int  # word index on the page, blocks in inventory order
    box: Box  # the word's box


@dataclass(frozen=True, slots=True)
class OutputToken:
    norm: str
    block: str  # output block id
    start: int  # character span in the block's text (epub.inline.text_of)
    end: int


class SourcePos(NamedTuple):
    page: int
    block: str
    line: int
    word: int


# --------------------------------------------------------------------------- stream matching


@dataclass
class Matches:
    """Token matches between two streams. Merges map two tokens to one partner."""

    src_to_out: list[int]  # -1 when the source token has no in-order or moved partner
    out_to_src: list[int]  # -1 when the output token is unmatched
    out_kind: list[str | None]  # EXACT, FUZZY, MOVED, DUPLICATE or None
    ambiguous: set[int]  # output tokens whose placement is a tie


def match_streams(src: list[str], out: list[str], out_groups: list[int] | None = None) -> Matches:
    """Align two normalized token streams; ``out_groups`` gives each output token's block."""
    m = Matches([-1] * len(src), [-1] * len(out), [None] * len(out), set())
    _Matcher(src, out, out_groups, m).run()
    return m


class _Matcher:
    def __init__(self, src, out, groups, m: Matches):
        self.src, self.out, self.groups, self.m = src, out, groups, m
        self.similar: dict[tuple[str, str], bool] = {}

    def run(self) -> None:
        anchors = _unique_pairs(self.src, 0, len(self.src), self.out, 0, len(self.out), ANCHOR_N)
        self._mark_chain(_increasing_chain(anchors), ANCHOR_N)
        for gap in self._gaps(0, len(self.src), 0, len(self.out)):
            self._fill(*gap)
        self._leftovers()
        self._extend()

    # ---- in-order phase

    def _mark_chain(self, chain: list[tuple[int, int]], n: int) -> None:
        m = self.m
        last_i = last_j = -1
        for i, j in chain:
            for k in range(n):
                a, b = i + k, j + k
                if a > last_i and b > last_j and m.src_to_out[a] < 0 and m.out_to_src[b] < 0:
                    self._pair(a, 1, b, 1, EXACT, claim=True)
                    last_i, last_j = a, b

    def _gaps(self, i0: int, i1: int, j0: int, j1: int):
        """Unmatched (src range, out range) stretches between matched pairs in a window."""
        m = self.m
        pi, pj = i0, j0
        for j in range(j0, j1):
            i = m.out_to_src[j]
            if i < 0:
                continue
            if pi < i and pj < j:
                yield pi, i, pj, j
            pi = max(pi, i + 1 + (i + 1 < i1 and m.src_to_out[i + 1] == j))
            pj = j + 1
        if pi < i1 and pj < j1:
            yield pi, i1, pj, j1

    def _fill(self, i0: int, i1: int, j0: int, j1: int) -> None:
        stack = [(i0, i1, j0, j1)]
        while stack:
            a0, a1, b0, b1 = stack.pop()
            if a1 <= a0 or b1 <= b0:
                continue
            if (a1 - a0) * (b1 - b0) <= MAX_CELLS:
                self._apply_dp(a0, a1, b0, b1, claim=True, edges=(True, True))
                self._mark_ties(a0, a1, b0, b1)
                continue
            for n in (3, 2, 1):
                pairs = _unique_pairs(self.src, a0, a1, self.out, b0, b1, n)
                if n < SEED_N:
                    pairs = [(i, j) for i, j in pairs if self._confirmed(i, j, n, a0, a1, b0, b1)]
                if pairs:
                    self._mark_chain(_increasing_chain(pairs), n)
                    stack.extend(self._gaps(a0, a1, b0, b1))
                    break
            else:
                self._match_ends(a0, a1, b0, b1)

    def _confirmed(self, i: int, j: int, n: int, a0: int, a1: int, b0: int, b1: int) -> bool:
        """A short anchor counts only when the word next to it matches too."""
        src, out = self.src, self.out
        before = i > a0 and j > b0 and src[i - 1] == out[j - 1]
        after = i + n < a1 and j + n < b1 and src[i + n] == out[j + n]
        return before or after

    def _match_ends(self, i0: int, i1: int, j0: int, j1: int) -> None:
        while i0 < i1 and j0 < j1 and self.src[i0] == self.out[j0]:
            self._pair(i0, 1, j0, 1, EXACT, claim=True)
            i0, j0 = i0 + 1, j0 + 1
        while i0 < i1 and j0 < j1 and self.src[i1 - 1] == self.out[j1 - 1]:
            self._pair(i1 - 1, 1, j1 - 1, 1, EXACT, claim=True)
            i1, j1 = i1 - 1, j1 - 1

    def _mark_ties(self, i0: int, i1: int, j0: int, j1: int) -> None:
        """Blocks inside a gap whose text occurs a different number of times on each side."""
        if self.groups is None:
            return
        src = tuple(self.src[i0:i1])
        out = tuple(self.out[j0:j1])
        j = j0
        while j < j1:
            k = j
            while k < j1 and self.groups[k] == self.groups[j]:
                k += 1
            seq = out[j - j0 : k - j0]
            in_src = _occurrences(src, seq)
            if in_src and in_src != _occurrences(out, seq):
                self.m.ambiguous.update(range(j, k))
            j = k

    # ---- out-of-order phase

    def _leftovers(self) -> None:
        m = self.m
        unique = _unique_index(self.src, 0, len(self.src), SEED_N)
        j, n_out = 0, len(self.out)
        while j < n_out:
            if m.out_to_src[j] >= 0:
                j += 1
                continue
            k = j
            while k < n_out and m.out_to_src[k] < 0:
                k += 1
            if k - j >= SEED_N:
                self._seed_run(j, k, unique)
            j = k

    def _seed_run(self, j0: int, j1: int, unique: dict[tuple[str, ...], int]) -> None:
        seeds = []
        for j in range(j0, j1 - SEED_N + 1):
            gram = tuple(self.out[j : j + SEED_N])
            i = unique.get(gram)
            if i is not None and _informative(gram):
                seeds.append((i, j))
        clusters: list[list[tuple[int, int]]] = []
        for i, j in seeds:
            if clusters and abs((i - j) - (clusters[-1][-1][0] - clusters[-1][-1][1])) <= 5:
                clusters[-1].append((i, j))
            else:
                clusters.append([(i, j)])
        for c, cluster in enumerate(clusters):
            lo = j0 if c == 0 else (clusters[c - 1][-1][1] + cluster[0][1]) // 2 + 1
            hi = j1 if c == len(clusters) - 1 else (cluster[-1][1] + clusters[c + 1][0][1]) // 2 + 1
            self._seed_cluster(_increasing_chain(sorted(cluster)), lo, hi)

    def _seed_cluster(self, chain: list[tuple[int, int]], lo: int, hi: int) -> None:
        """Pair the seeds' words, then fill between seeds and out to [lo, hi) by DP."""
        m = self.m
        points: list[tuple[int, int]] = []
        for i, j in chain:
            for d in range(SEED_N):
                a, b = i + d, j + d
                if m.out_to_src[b] < 0 and (
                    not points or (a > points[-1][0] and b > points[-1][1])
                ):
                    self._pair(a, 1, b, 1, EXACT, claim=False)
                    points.append((a, b))
        if not points:
            return
        (fi, fj), (li, lj) = points[0], points[-1]
        windows = [(max(0, fi - (fj - lo) - 5), fi, lo, fj, (False, True))]
        windows += [
            (a + 1, c, b + 1, d, (True, True)) for (a, b), (c, d) in itertools.pairwise(points)
        ]
        windows.append(
            (li + 1, min(len(self.src), li + 1 + (hi - lj) + 5), lj + 1, hi, (True, False))
        )
        for s0, s1, b0, b1, edges in windows:
            if s1 > s0 and b1 > b0 and (s1 - s0) * (b1 - b0) <= MAX_CELLS:
                self._apply_dp(s0, s1, b0, b1, claim=False, edges=edges)

    def _extend(self) -> None:
        """Match unmatched words that equal the source word right next to their matched
        neighbour's source word: boundary residue around moved or duplicated text."""
        m, src, out = self.m, self.src, self.out
        for j in range(1, len(out)):
            if m.out_to_src[j] < 0 and m.out_to_src[j - 1] >= 0:
                i = m.out_to_src[j - 1] + 1
                while i < len(src) and m.src_to_out[i] == j - 1:
                    i += 1
                if i < len(src) and src[i] == out[j]:
                    self._pair_next_to(i, j, j - 1)
        for j in range(len(out) - 2, -1, -1):
            if m.out_to_src[j] < 0 and m.out_to_src[j + 1] >= 0:
                i = m.out_to_src[j + 1] - 1
                if i >= 0 and src[i] == out[j]:
                    self._pair_next_to(i, j, j + 1)

    def _pair_next_to(self, i: int, j: int, neighbour: int) -> None:
        in_order = self.m.out_kind[neighbour] in (EXACT, FUZZY)
        claim = in_order and self.m.src_to_out[i] < 0
        self._pair(i, 1, j, 1, EXACT, claim=claim)

    # ---- shared

    def _pair(self, i: int, ni: int, j: int, nj: int, kind: str, claim: bool) -> None:
        m = self.m
        if not claim:
            free = all(m.src_to_out[i + d] < 0 for d in range(ni))
            kind = MOVED if free else DUPLICATE
        for d in range(nj):
            m.out_to_src[j + d] = i
            m.out_kind[j + d] = kind
        if kind != DUPLICATE:
            for d in range(ni):
                m.src_to_out[i + d] = j

    def _apply_dp(self, i0, i1, j0, j1, claim: bool, edges: tuple[bool, bool]) -> None:
        pairs = _filter_scattered(self._dp(i0, i1, j0, j1), i0, i1, j0, j1, edges)
        for i, ni, j, nj, kind in pairs:
            if all(self.m.out_to_src[j + d] < 0 for d in range(nj)):
                self._pair(i, ni, j, nj, kind, claim)

    def _same(self, a: str, b: str) -> bool:
        key = (a, b)
        hit = self.similar.get(key)
        if hit is None:
            hit = self.similar[key] = similar(a, b)
        return hit

    def _dp(self, i0: int, i1: int, j0: int, j1: int) -> list[tuple[int, int, int, int, str]]:
        """Best monotone matching of src[i0:i1] to out[j0:j1] as (i, ni, j, nj, kind)."""
        s, o = self.src[i0:i1], self.out[j0:j1]
        n, w = len(s), len(o)
        score = [[0] * (w + 1) for _ in range(n + 1)]
        for a in range(1, n + 1):
            sa, row, prev = s[a - 1], score[a], score[a - 1]
            for b in range(1, w + 1):
                best = prev[b] if prev[b] >= row[b - 1] else row[b - 1]
                ob = o[b - 1]
                if sa == ob:
                    best = max(best, prev[b - 1] + _EXACT_SCORE)
                else:
                    if self._same(sa, ob):
                        best = max(best, prev[b - 1] + _FUZZY_SCORE)
                    if a > 1 and s[a - 2] + sa == ob:
                        best = max(best, score[a - 2][b - 1] + _EXACT_SCORE)
                    if b > 1 and o[b - 2] + ob == sa:
                        best = max(best, prev[b - 2] + _EXACT_SCORE)
                row[b] = best
        pairs = []
        a, b = n, w
        while a > 0 and b > 0:
            here, sa, ob = score[a][b], s[a - 1], o[b - 1]
            if sa == ob and here == score[a - 1][b - 1] + _EXACT_SCORE:
                pairs.append((i0 + a - 1, 1, j0 + b - 1, 1, EXACT))
                a, b = a - 1, b - 1
            elif a > 1 and s[a - 2] + sa == ob and here == score[a - 2][b - 1] + _EXACT_SCORE:
                pairs.append((i0 + a - 2, 2, j0 + b - 1, 1, FUZZY))
                a, b = a - 2, b - 1
            elif b > 1 and o[b - 2] + ob == sa and here == score[a - 1][b - 2] + _EXACT_SCORE:
                pairs.append((i0 + a - 1, 1, j0 + b - 2, 2, FUZZY))
                a, b = a - 1, b - 2
            elif sa != ob and here == score[a - 1][b - 1] + _FUZZY_SCORE and self._same(sa, ob):
                pairs.append((i0 + a - 1, 1, j0 + b - 1, 1, FUZZY))
                a, b = a - 1, b - 1
            elif here == score[a][b - 1]:
                b -= 1
            else:
                a -= 1
        return pairs[::-1]


def _filter_scattered(pairs, i0: int, i1: int, j0: int, j1: int, edges: tuple[bool, bool]):
    """Keep runs of at least ``SOLID_RUN`` matched words, plus shorter runs within ``NEAR``
    words of a kept run or of a window edge that is itself a match (``edges`` says which
    are). Isolated common words matched across otherwise different text go."""
    runs: list[list[tuple]] = []
    for p in pairs:
        if runs and (p[0], p[2]) == _end(runs[-1][-1]):
            runs[-1].append(p)
        else:
            runs.append([p])
    keep = [sum(p[3] for p in run) >= SOLID_RUN for run in runs]

    def near(a: tuple[int, int], b: tuple[int, int]) -> bool:
        return max(b[0] - a[0], b[1] - a[1]) <= NEAR

    point = (i0, j0) if edges[0] else None
    for r, run in enumerate(runs):
        if keep[r] or (point is not None and near(point, (run[0][0], run[0][2]))):
            keep[r], point = True, _end(run[-1])
    point = (i1, j1) if edges[1] else None
    for r in range(len(runs) - 1, -1, -1):
        if keep[r] or (point is not None and near(_end(runs[r][-1]), point)):
            keep[r], point = True, (runs[r][0][0], runs[r][0][2])
    return [p for r, run in enumerate(runs) if keep[r] for p in run]


def _end(p: tuple) -> tuple[int, int]:
    return p[0] + p[1], p[2] + p[3]


def _occurrences(hay: tuple[str, ...], seq: tuple[str, ...]) -> int:
    n = len(seq)
    if not n or n > len(hay):
        return 0
    first = seq[0]
    return sum(1 for k in range(len(hay) - n + 1) if hay[k] == first and hay[k : k + n] == seq)


def _unique_index(tokens: list[str], lo: int, hi: int, n: int) -> dict[tuple[str, ...], int]:
    seq = tokens[lo:hi]
    seen: dict[tuple[str, ...], int] = {}
    repeated = set()
    for k, gram in enumerate(zip(*(seq[d:] for d in range(n)), strict=False)):
        if gram in seen:
            repeated.add(gram)
        else:
            seen[gram] = lo + k
    for gram in repeated:
        del seen[gram]
    return seen


def _unique_pairs(src, i0, i1, out, j0, j1, n) -> list[tuple[int, int]]:
    us = _unique_index(src, i0, i1, n)
    uo = _unique_index(out, j0, j1, n)
    return sorted((us[g], uo[g]) for g in us.keys() & uo.keys() if _informative(g))


def _informative(gram: tuple[str, ...]) -> bool:
    """An anchor needs long words: "ledger 1 1 the" is unique by accident, not by content."""
    return sum(len(t) >= 4 for t in gram) >= (1 if len(gram) <= 2 else 2)


def _increasing_chain(pairs: list[tuple[int, int]]) -> list[tuple[int, int]]:
    """Longest chain of pairs (sorted by source index) strictly increasing in output index."""
    tails: list[int] = []
    tail_at: list[int] = []
    back = [-1] * len(pairs)
    for k, (_, j) in enumerate(pairs):
        pos = bisect_left(tails, j)
        if pos == len(tails):
            tails.append(j)
            tail_at.append(k)
        else:
            tails[pos] = j
            tail_at[pos] = k
        back[k] = tail_at[pos - 1] if pos else -1
    chain = []
    k = tail_at[-1] if tail_at else -1
    while k >= 0:
        chain.append(pairs[k])
        k = back[k]
    return chain[::-1]


# --------------------------------------------------------------------------- source order


def reading_order(page: PageInventory) -> list[TextBlock]:
    """Text blocks in reading order: margin blocks at the top and bottom, and on pages
    flagged multi-column each band between full-width blocks read column by column."""
    if "multi_column" not in page.flags:
        return list(page.blocks)
    top = [b for b in page.blocks if layout.in_margin(b.box) and b.box.y1 <= 0.5]
    bottom = [b for b in page.blocks if layout.in_margin(b.box) and b.box.y1 > 0.5]
    body = sorted((b for b in page.blocks if not layout.in_margin(b.box)), key=lambda b: b.box.y0)
    starts = _column_starts([b.box.x0 for b in body if b.box.width < 0.6])
    ordered: list[TextBlock] = []
    band: list[TextBlock] = []

    def flush() -> None:
        band.sort(key=lambda b: (bisect_left(starts, b.box.x0 + 0.05) - 1, b.box.y0))
        ordered.extend(band)
        band.clear()

    for block in body:
        if block.box.width >= 0.6:
            flush()
            ordered.append(block)
        else:
            band.append(block)
    flush()
    return top + ordered + bottom


def _column_starts(xs: list[float]) -> list[float]:
    starts: list[float] = []
    for x in sorted(xs):
        if not starts or x - starts[-1] > 0.1:
            starts.append(x)
    return starts


# --------------------------------------------------------------------------- the map


class Alignment:
    """Source<->output map at word level; built by ``align_book``.

    Source tokens are indexed in reading order across the book, output tokens in spine
    order. ``src_to_out``/``out_to_src`` hold partner indices (-1 when unmatched);
    ``out_kind`` says how each output token matched. A duplicate output token points at
    its source token while that source token keeps its first partner.
    """

    def __init__(
        self,
        source: list[SourceToken],
        output: list[OutputToken],
        matches: Matches,
        hrefs: dict[str, str],
        generated: set[str],
    ):
        self.source = source
        self.output = output
        self.src_to_out = matches.src_to_out
        self.out_to_src = matches.out_to_src
        self.out_kind = matches.out_kind
        self.hrefs = hrefs  # output block id -> content document href
        self.generated = generated  # output block ids excluded from matching
        self.images: dict[str, str] = {}  # ImageRegion id -> output block id showing it
        self.image_pages: dict[str, int] = {}  # ImageRegion id -> its page, for claimed ones
        self.ambiguous_blocks = {output[j].block for j in matches.ambiguous}
        self.coverage: dict | None = None  # filled by align.coverage.account
        self._page_tokens: dict[int, list[int]] = {}
        self._src_blocks: dict[str, list[int]] = {}
        self._page_words: dict[tuple[int, int], int] = {}
        for i, tok in enumerate(source):
            self._page_tokens.setdefault(tok.page, []).append(i)
            self._src_blocks.setdefault(tok.block, []).append(i)
            self._page_words.setdefault((tok.page, tok.page_word), i)
        self._out_blocks: dict[str, list[int]] = {}
        for j, tok in enumerate(output):
            self._out_blocks.setdefault(tok.block, []).append(j)

    # ---- token lists

    def source_tokens(self, page: int) -> list[int]:
        return self._page_tokens.get(page, [])

    def source_block_tokens(self, block_id: str) -> list[int]:
        return self._src_blocks.get(block_id, [])

    def output_block_tokens(self, block_id: str) -> list[int]:
        return self._out_blocks.get(block_id, [])

    def unmatched_source_tokens(self, page: int) -> list[SourceToken]:
        return [self.source[i] for i in self.source_tokens(page) if self.src_to_out[i] < 0]

    def unmatched_output_tokens(self, block_id: str) -> list[OutputToken]:
        return [
            self.output[j] for j in self.output_block_tokens(block_id) if self.out_to_src[j] < 0
        ]

    # ---- lookups

    def source_to_output(self, page: int, word_or_block: int | str) -> tuple[str, int] | None:
        """Output (block id, char offset) of a source word or of a source block's first
        matched word. An int is the word's index on the page in inventory order."""
        if isinstance(word_or_block, str):
            candidates = self.source_block_tokens(word_or_block)
        else:
            first = self._page_words.get((page, word_or_block))
            candidates = [] if first is None else [first]
        for i in candidates:
            if (j := self.src_to_out[i]) >= 0:
                tok = self.output[j]
                return tok.block, tok.start
        return None

    def output_to_source(self, block_id: str, offset: int) -> SourcePos | None:
        """Source word of the nearest matched output word at or after ``offset`` in the
        block (else the nearest before it)."""
        js = [j for j in self.output_block_tokens(block_id) if self.out_to_src[j] >= 0]
        if not js:
            return None
        after = [j for j in js if self.output[j].end > offset]
        tok = self.source[self.out_to_src[after[0] if after else js[-1]]]
        return SourcePos(tok.page, tok.block, tok.line, tok.word)

    def first_output_position_on_page(self, page: int) -> tuple[str, int] | None:
        """Earliest output position (block id, char offset) holding a word of ``page``."""
        js = [self.src_to_out[i] for i in self.source_tokens(page) if self.src_to_out[i] >= 0]
        if not js:
            return None
        tok = self.output[min(js)]
        return tok.block, tok.start

    def block_pages(self, block_id: str) -> list[int]:
        pages = {self.source[i].page for i in self._matched_sources(block_id)}
        pages |= {self.image_pages[r] for r, b in self.images.items() if b == block_id}
        return sorted(pages)

    def page_near(self, j: int) -> int | None:
        """Source page of the nearest matched output token to output token ``j``."""
        for k in _outward(j, len(self.output)):
            if (i := self.out_to_src[k]) >= 0:
                return self.source[i].page
        return None

    def anchor_near_source(self, i: int) -> str | None:
        """``href#id`` of the output block holding the nearest matched word before source
        token ``i`` (else after it): where missing source text would have been."""
        for k in itertools.chain(range(i, -1, -1), range(i + 1, len(self.source))):
            if (j := self.src_to_out[k]) >= 0:
                block = self.output[j].block
                return f"{self.hrefs[block]}#{block}"
        return None

    def _matched_sources(self, block_id: str) -> list[int]:
        return [
            self.out_to_src[j]
            for j in self.output_block_tokens(block_id)
            if self.out_to_src[j] >= 0
        ]


def _outward(j: int, n: int):
    for d in range(n):
        if j - d >= 0:
            yield j - d
        if d and j + d < n:
            yield j + d


# --------------------------------------------------------------------------- building


def align_book(ctx: ConvertContext) -> None:
    """Rebuild ``ctx.blocks`` and ``ctx.alignment`` from the source inventory and the book."""
    started = time.perf_counter()
    blocks = ctx.book.ensure_block_ids()
    generated = _generated_ids(ctx.book)
    source = _source_tokens(ctx.source)
    output = [
        OutputToken(tok.norm, block.id, tok.start, tok.end)
        for block in blocks
        if block.id not in generated
        for tok in tokenize(block.text)
    ]
    groups, index = [], {}
    for tok in output:
        groups.append(index.setdefault(tok.block, len(index)))
    matches = match_streams([t.norm for t in source], [t.norm for t in output], groups)
    alignment = Alignment(source, output, matches, {b.id: b.href for b in blocks}, generated)
    _claim_images(ctx, alignment, blocks)
    words = {b.id: b for p in ctx.source.pages for b in p.blocks} if ctx.diagnostics else None
    regions = {r.id: r for p in ctx.source.pages for r in p.images}
    shown: dict[str, list] = {}
    for region_id, block_id in alignment.images.items():
        shown.setdefault(block_id, []).append(regions[region_id])
    ctx.blocks = [_filled(block, alignment, shown.get(block.id, []), words) for block in blocks]
    ctx.alignment = alignment
    matched = sum(1 for i in alignment.src_to_out if i >= 0)
    log.info(
        "aligned %d/%d source words to %d output words in %.2fs",
        matched,
        len(source),
        len(output),
        time.perf_counter() - started,
    )


def refresh(ctx: ConvertContext) -> None:
    """Re-run block ids and alignment after a repair changed the block structure."""
    align_book(ctx)


def _source_tokens(inv: SourceInventory) -> list[SourceToken]:
    tokens = []
    for page in inv.pages:
        page_word = {
            id(w): k
            for k, w in enumerate(w for b in page.blocks for ln in b.lines for w in ln.words)
        }
        for block in reading_order(page):
            for li, line in enumerate(block.lines):
                for wi, word in enumerate(line.words):
                    for tok in tokenize(word.text):
                        tokens.append(
                            SourceToken(
                                tok.norm,
                                page.index,
                                block.id,
                                li,
                                wi,
                                page_word[id(word)],
                                word.box,
                            )
                        )
    return tokens


def _generated_ids(book: Book) -> set[str]:
    """Ids of elements inside a ``nav`` or an element marked ``data-qc-generated``."""
    from ..epub.package import local_name

    ids = set()
    for doc in book.docs:
        for el in doc.body.iter(etree.Element):
            if el.get(GENERATED_ATTR) is not None or local_name(el) == "nav":
                ids.update(d.get("id") for d in el.iter(etree.Element) if d.get("id"))
    return ids


def _claim_images(ctx: ConvertContext, alignment: Alignment, blocks: list[OutputBlock]) -> None:
    """Map output images to source image regions: by ``data-qc-source`` when present, else
    a Calibre ``img`` takes the first unclaimed raster region between the pages of the
    nearest matched words before and after it."""
    from ..epub.package import local_name

    regions = {r.id: r for p in ctx.source.pages for r in p.images}
    position = {b.id: k for k, b in enumerate(blocks)}
    found: list[tuple[int, str, str | None]] = []  # (block index, block id, refs)
    for doc in ctx.book.docs:
        for el in doc.body.iter(etree.Element):
            refs = el.get(SOURCE_ATTR)
            if refs is None and local_name(el) != "img":
                continue
            if refs is None and any(a.get(SOURCE_ATTR) is not None for a in el.iterancestors()):
                continue
            owner = next(
                (a.get("id") for a in _self_and_ancestors(el) if a.get("id") in position), None
            )
            if owner is not None:
                found.append((position[owner], owner, refs))
    for _, owner, refs in found:
        for ref in (refs or "").split():
            if ref in regions and ref not in alignment.images:
                alignment.images[ref] = owner
                alignment.image_pages[ref] = regions[ref].page
    if not any(refs is None for _, _, refs in found):
        return
    before, after = _neighbour_pages(alignment, blocks)
    rasters = [r for r in regions.values() if r.kind == "raster"]
    last_page = len(ctx.source.pages) - 1
    for k, owner, refs in sorted(found):
        if refs is not None:
            continue
        lo = before[k] if before[k] is not None else 0
        hi = after[k] if after[k] is not None else last_page
        for region in rasters:
            if lo <= region.page <= hi and region.id not in alignment.images:
                alignment.images[region.id] = owner
                alignment.image_pages[region.id] = region.page
                break


def _self_and_ancestors(el: etree._Element):
    yield el
    yield from el.iterancestors()


def _neighbour_pages(alignment: Alignment, blocks: list[OutputBlock]):
    """Per block index, the page of the last matched word up to it and the first from it."""
    pages = []
    for block in blocks:
        js = alignment.output_block_tokens(block.id)
        found = [
            alignment.source[alignment.out_to_src[j]].page
            for j in js
            if alignment.out_to_src[j] >= 0
        ]
        pages.append(found)
    before, after = [], [None] * len(blocks)
    last = None
    for found in pages:
        if found:
            last = found[-1]
        before.append(last)
    nxt = None
    for k in range(len(blocks) - 1, -1, -1):
        if pages[k]:
            nxt = pages[k][0]
        after[k] = nxt
    return before, after


def _filled(
    block: OutputBlock,
    alignment: Alignment,
    regions: list[ImageRegion],
    words: dict[str, TextBlock] | None,
) -> OutputBlock:
    if block.id in alignment.generated:
        block.alignment = "generated"
        return block
    js = alignment.output_block_tokens(block.id)
    block.spans = _spans(js, alignment, words)
    block.spans += [SourceSpan(r.page, r.box, image_ref=r.id) for r in regions]
    kinds = [alignment.out_kind[j] for j in js]
    if not js:
        block.alignment = "exact" if regions else "unaligned"
    elif all(k is None for k in kinds):
        block.alignment = "unaligned"
    elif block.id in alignment.ambiguous_blocks or DUPLICATE in kinds:
        block.alignment = "ambiguous"
    elif all(k == EXACT for k in kinds):
        block.alignment = "exact"
    else:
        block.alignment = "fuzzy"
    return block


def _spans(
    js: list[int], alignment: Alignment, words: dict[str, TextBlock] | None
) -> list[SourceSpan]:
    """One span per run of matched words from the same source block and adjacent lines."""
    runs: list[list[SourceToken]] = []
    for j in js:
        i = alignment.out_to_src[j]
        if i < 0:
            continue
        tok = alignment.source[i]
        if runs:
            prev = runs[-1][-1]
            if prev.block == tok.block and 0 <= tok.line - prev.line <= 1:
                if tok is not prev:
                    runs[-1].append(tok)
                continue
        runs.append([tok])
    spans = []
    for run in runs:
        box = run[0].box
        for tok in run[1:]:
            box = box.union(tok.box)
        text = None
        if words is not None:
            lines = words[run[0].block].lines
            keys = dict.fromkeys((tok.line, tok.word) for tok in run)
            text = " ".join(lines[li].words[wi].text for li, wi in keys)
        spans.append(SourceSpan(run[0].page, box, text=text))
    return spans
