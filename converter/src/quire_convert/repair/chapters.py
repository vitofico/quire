"""Chapters family: verified headings, chapter splits, navigation and contents links.

Four kinds of evidence are compared. PDF bookmarks and printed contents entries (a title
with a trailing page number, mapped to a physical page through page labels and folios)
are claims; typographic candidates (short lines set larger or bold, at the top of a page
or after whitespace) and the text of the body are what the claims are checked against.
A claim is accepted only when a body heading with its title sits on the page it names,
never inside the printed contents and never by first text match alone: duplicate titles
are told apart by sequence and page. Unclaimed candidates are accepted when they share
the style of a claimed heading (front matter such as Contents or an Index), when they are
a smaller style repeated under claimed ones (sections), or, in a book with no claims,
when they look like headings on their own or repeat a heading style.

Accepted headings become ``hN`` elements, the top levels start their own spine document,
and they make up the rebuilt navigation; the printed contents entries link to them.
"""

from __future__ import annotations

import logging
import re
import statistics
from collections import Counter
from dataclasses import dataclass, field
from typing import TYPE_CHECKING

from lxml import etree

from ..align.align import reading_order, refresh
from ..align.coverage import note_blocks, running_furniture
from ..align.normalize import tokenize
from ..epub.book import ContentDoc, Landmark, NavEntry, is_block, remove_keep_tail
from ..epub.inline import insert_at, merge, split_at, text_of
from ..epub.package import XHTML_NS, local_name, relative_href
from ..models import Severity, SourceInventory, TextBlock
from ..source import layout

if TYPE_CHECKING:
    from ..context import ConvertContext
    from ..models import Bookmark

log = logging.getLogger(__name__)

FAMILY = "chapters"
WEAK_RATIO = 1.1  # size over body text that makes a short isolated line a heading candidate
STRONG_RATIO = 1.3  # ... that, with bold, makes it a heading on its own
LARGE_RATIO = 1.6  # ... that makes it a heading on its own without bold
BOLD_SHARE = 0.8
MAX_WORDS = 12  # heading candidates are short
MAX_LINES = 3
CLAIM_WORDS = 20  # longest block a contents entry or bookmark title may match
GAP = 0.8  # whitespace above an isolated line, in body text sizes
SIZE_STEP = 1.0  # sizes this close (points) are one heading level
BOOKMARK_Y = 0.2  # largest distance between a bookmark's y and its heading's top
SIGNAL_PAGES = 10  # a book this long without verified chapters is flagged regardless

HEADINGS = frozenset(f"h{n}" for n in range(1, 7))
PROMOTABLE = HEADINGS | {"p", "div"}
LINK = f"{{{XHTML_NS}}}a"
MARKER = f"{{{XHTML_NS}}}span"

_LEADER = r"(?:\s*(?:[.·…_]\s*){2,}|\s*…\s*)"
_ENTRY = re.compile(
    rf"^(?P<title>.*?[^\s.·…_])(?:(?P<leader>{_LEADER})|\s+)(?P<num>\d{{1,4}}|[ivxlcdm]{{1,7}})$",
    re.I,
)
_LABEL_WORDS = frozenset(
    {"chapter", "part", "book", "section", "capitolo", "parte", "libro", "chapitre", "kapitel"}
)
_PART_WORDS = frozenset({"part", "book", "parte", "libro", "teil"})
_NUMBER_WORDS = frozenset(
    "one two three four five six seven eight nine ten eleven twelve thirteen fourteen "
    "fifteen sixteen seventeen eighteen nineteen twenty first second third fourth fifth "
    "sixth seventh eighth ninth tenth".split()
)
_SECTION = re.compile(r"^(\d+(?:\.\d+)+)\.?(?:\s|$)")
_CONTENTS_TITLES = frozenset(
    {"contents", "table of contents", "sommario", "indice", "inhalt", "table des matières"}
)
_INDEX_TITLES = frozenset({"index", "general index", "subject index", "indice analitico"})


def apply(ctx: ConvertContext) -> None:
    if ctx.alignment is None:
        refresh(ctx)
    _Chapters(ctx).run()


# --------------------------------------------------------------------------- evidence


@dataclass
class _Cand:
    """A short source block (or run of same-style blocks) that may be a heading."""

    blocks: list[TextBlock]
    order: int  # rank of the first block among body blocks in reading order
    title: str
    norms: list[str]
    size: float
    bold: bool
    weak: bool  # heading-like: short, isolated, larger or bold, no sentence ending
    strong: bool  # heading on its own: weak and clearly larger

    @property
    def page(self) -> int:
        return self.blocks[0].page

    @property
    def id(self) -> str:
        return self.blocks[0].id

    @property
    def style(self) -> tuple[int, bool]:
        return round(self.size), self.bold


@dataclass
class _Entry:
    """One printed contents line."""

    block: TextBlock
    title: str
    norms: list[str]
    label: str
    page: int | None  # physical page its printed number names
    heading: _Heading | None = None


@dataclass
class _Heading:
    cand: _Cand
    evidence: list[str]
    bookmark_level: int | None = None
    level: int = 1
    pieces: list[tuple[str, int, int]] = field(default_factory=list)  # output (block, start, end)
    el: etree._Element | None = None


def norms_of(text: str) -> list[str]:
    return [t.norm for t in tokenize(text)]


def _is_numeral(word: str) -> bool:
    return word.isdigit() or word in _NUMBER_WORDS or bool(layout.folio_text(word))


def _core(norms: list[str]) -> list[str]:
    """The title without a leading label and number ("chapter 3 the river" -> "the river")."""
    k = 1 if norms and norms[0] in _LABEL_WORDS else 0
    if k < len(norms) and _is_numeral(norms[k]):
        k += 1
    return norms[k:]


def same_title(a: list[str], b: list[str]) -> bool:
    """Equal titles, or equal once one side's numbering is dropped (never both sides',
    so "Chapter One: X" and "Chapter Two: X" stay different)."""
    if not a or not b:
        return False
    return a == b or _core(a) == b or a == _core(b)


def parse_entry(text: str) -> tuple[str, str] | None:
    """``(title, printed page)`` of a contents line: a title, an optional dot leader and a
    trailing page number. Roman page numbers need a leader; "term, 3" is an index line."""
    m = _ENTRY.match(text.strip())
    if m is None:
        return None
    title, num = m["title"], m["num"]
    if not num.isdigit() and not m["leader"]:
        return None
    if title.endswith(",") or not any(ch.isalpha() for ch in title):
        return None
    return title, num


def _kind(title: str) -> tuple[str, int]:
    """("part" | "chapter" | "section" | "", depth) from the title's numbering."""
    words = norms_of(title)
    if len(words) >= 2 and _is_numeral(words[1]):
        if words[0] in _PART_WORDS:
            return "part", 1
        if words[0] in _LABEL_WORDS:
            return "chapter", 1
    if m := _SECTION.match(title.strip()):
        return "section", m.group(1).count(".") + 1
    return "", 1


def _body_size(source: SourceInventory, skip: set[str]) -> float | None:
    sizes = Counter(
        w.font_size
        for page in source.pages
        for b in page.blocks
        if b.id not in skip
        for line in b.lines
        for w in line.words
        if w.font_size
    )
    return sizes.most_common(1)[0][0] if sizes else None


def _label_map(source: SourceInventory) -> tuple[dict[str, list[int]], int | None]:
    """Printed label -> physical pages (from /PageLabels and margin folios), and the most
    common offset between physical index and arabic labels, for pages missing a folio."""
    labels: dict[str, list[int]] = {}
    offsets: Counter[int] = Counter()
    for page in source.pages:
        for label in {page.label, *page.folio_candidates} - {None}:
            labels.setdefault(label.casefold(), []).append(page.index)
            if label.isdigit():
                offsets[page.index - int(label)] += 1
    common = offsets.most_common(1)
    offset = common[0][0] if common and common[0][1] >= 2 else None
    return labels, offset


# --------------------------------------------------------------------------- the family


class _Chapters:
    def __init__(self, ctx: ConvertContext):
        self.ctx = ctx
        self.book = ctx.book
        self.source = ctx.source
        self.al = ctx.alignment
        self.furniture = running_furniture(ctx.source)
        self.skip = self.furniture | note_blocks(ctx.source, self.furniture)
        self.body_size = _body_size(ctx.source, self.skip)
        self.labels, self.offset = _label_map(ctx.source)
        self.body: list[TextBlock] = [
            b for page in ctx.source.pages for b in reading_order(page) if b.id not in self.skip
        ]
        cands = self._candidates()
        self.entries = self._contents(cands)
        self.contents_pages = {e.block.page for e in self.entries}
        entry_blocks = {e.block.id for e in self.entries}
        self.cands = [c for c in cands if not {b.id for b in c.blocks} & entry_blocks]
        self.heads: dict[str, _Heading] = {}
        self.demoted: set[str] = set()
        self.expected: dict[str, list[str]] = {}  # nav target -> title it must show

    # ----------------------------------------------------------------------- run

    def run(self) -> None:
        self._claim_bookmarks()
        self._claim_entries()
        self._accept_by_style()
        heads = sorted(self.heads.values(), key=lambda h: h.cand.order)
        self._levels(heads)
        for head in heads:
            if not self._locate(head):
                self._unlocated(head)
        placed = [h for h in heads if h.pieces]
        self._demote_furniture({b for h in placed for b, _, _ in h.pieces})
        for entry in self.entries:
            if entry.heading is None or not entry.heading.pieces:
                self._missing(entry)
        if not placed:
            self._abstain()
            return
        self._promote(placed)
        placed = [h for h in placed if h.el is not None]
        self.book.ensure_block_ids()
        self._split(placed)
        refresh(self.ctx)
        self.al = self.ctx.alignment
        self._link_entries()
        self._rebuild_toc(placed)
        self._set_landmarks(placed)
        self._verify()
        log.info("chapters: %d headings, %d contents entries", len(placed), len(self.entries))

    # ----------------------------------------------------------------------- source side

    def _candidates(self) -> list[_Cand]:
        cands: list[_Cand] = []
        for k, block in enumerate(self.body):
            words = [w for line in block.lines for w in line.words]
            norms = norms_of(block.text)
            if not norms or len(norms) > CLAIM_WORDS or len(block.lines) > MAX_LINES + 1:
                continue
            prev = cands[-1] if cands else None
            if prev is not None and self._continues(prev, block, k):
                prev.blocks.append(block)
                self._describe(prev, [w for b in prev.blocks for ln in b.lines for w in ln.words])
                continue
            cand = _Cand([block], k, "", norms, 0.0, False, False, False)
            self._describe(cand, words)
            cands.append(cand)
        return cands

    def _continues(self, prev: _Cand, block: TextBlock, k: int) -> bool:
        """A second line of a heading set as its own block: same page and style, adjacent."""
        last = prev.blocks[-1]
        if not prev.weak or last.page != block.page or self.body[k - 1] is not last:
            return False
        sizes = [w.font_size for line in block.lines for w in line.words if w.font_size]
        size = statistics.median(sizes) if sizes else 0.0
        gap = (block.box.y0 - last.box.y1) * self.source.pages[block.page].height_pt
        return abs(size - prev.size) < SIZE_STEP and 0 <= gap < 0.6 * size

    def _describe(self, cand: _Cand, words) -> None:
        cand.title = " ".join(" ".join(b.text.split()) for b in cand.blocks)
        cand.norms = norms_of(cand.title)
        sizes = [w.font_size for w in words if w.font_size]
        body = self.body_size
        cand.size = statistics.median(sizes) if sizes else (body or 0.0)
        cand.bold = sum(w.bold for w in words) >= BOLD_SHARE * len(words)
        lines = sum(len(b.lines) for b in cand.blocks)
        ratio = cand.size / body if body else 0.0
        short = len(cand.norms) <= MAX_WORDS and lines <= MAX_LINES
        sentence = cand.title.rstrip()[-1:] in ".,;"
        isolated = self._isolated(cand.order)
        cand.weak = short and not sentence and isolated and (ratio >= WEAK_RATIO or cand.bold)
        cand.strong = cand.weak and ratio >= STRONG_RATIO and (cand.bold or ratio >= LARGE_RATIO)

    def _isolated(self, k: int) -> bool:
        block = self.body[k]
        prev = self.body[k - 1] if k else None
        if prev is None or prev.page != block.page or self.body_size is None:
            return True
        gap = (block.box.y0 - prev.box.y1) * self.source.pages[block.page].height_pt
        return gap >= GAP * self.body_size

    def _contents(self, cands: list[_Cand]) -> list[_Entry]:
        """Entries of the printed contents pages: pages where most lines are entries, whose
        page numbers do not decrease and at least half of which name a body heading."""
        per_page: dict[int, list[_Entry]] = {}
        lines: Counter[int] = Counter()
        for block in self.body:
            for line in block.lines:
                lines[block.page] += 1
                if parsed := parse_entry(line.text):
                    title, label = parsed
                    per_page.setdefault(block.page, []).append(
                        _Entry(block, title, norms_of(title), label, None)
                    )
        found: list[_Entry] = []
        for page, entries in sorted(per_page.items()):
            if len(entries) < 2 or 2 * len(entries) < lines[page]:
                continue
            for entry in entries:
                entry.page = self._target(entry.label, page)
            pages = [e.page for e in entries if e.page is not None]
            if pages != sorted(pages):
                continue
            named = sum(
                any(c.page != page and same_title(e.norms, c.norms) for c in cands) for e in entries
            )
            if 2 * named >= len(entries):
                found.extend(entries)
        return found

    def _target(self, label: str, contents_page: int) -> int | None:
        pages = sorted(set(self.labels.get(label.casefold(), [])))
        if pages:
            later = [p for p in pages if p > contents_page]
            return later[0] if later else None
        if label.isdigit() and self.offset is not None:
            page = int(label) + self.offset
            if contents_page < page < self.source.page_count:
                return page
        return None

    # ----------------------------------------------------------------------- claims

    def _accept(self, cand: _Cand, evidence: str) -> _Heading:
        head = self.heads.get(cand.id)
        if head is None:
            head = self.heads[cand.id] = _Heading(cand, [cand.id])
        head.evidence.append(evidence)
        return head

    def _claim_bookmarks(self) -> None:
        for n, mark in enumerate(self.source.bookmarks, 1):
            norms = norms_of(mark.title)
            hits = [
                c
                for c in self.cands
                if c.page == mark.page
                and c.page not in self.contents_pages
                and same_title(norms, c.norms)
                and (mark.y is None or abs(c.blocks[0].box.y0 - mark.y) <= BOOKMARK_Y)
            ]
            if hits:
                hit = min(hits, key=lambda c: (not c.weak, c.order))
                self._accept(hit, f"bookmark={n}").bookmark_level = mark.level
            else:
                self._reject_bookmark(n, mark, norms)

    def _reject_bookmark(self, n: int, mark: Bookmark, norms: list[str]) -> None:
        elsewhere = next(
            (
                c
                for c in self.cands
                if c.page not in self.contents_pages and c.weak and same_title(norms, c.norms)
            ),
            None,
        )
        evidence = f"bookmark={n} page={mark.page}"
        if elsewhere is not None:
            evidence += f" heading={elsewhere.id} heading_page={elsewhere.page}"
        self.ctx.issue(
            "nav.unverified_bookmark",
            Severity.MINOR,
            "a PDF bookmark does not land on a matching heading; it was not used",
            page=mark.page,
            page_label=self._label(mark.page),
            evidence=evidence,
            action="ignored",
            uncertainty="the bookmark destination shows no heading with its title",
        )

    def _claim_entries(self) -> None:
        after = -1
        for k, entry in enumerate(self.entries):
            pool = [
                c
                for c in self.cands
                if c.order > after
                and c.page not in self.contents_pages
                and same_title(entry.norms, c.norms)
            ]
            if entry.page is not None:
                exact = [c for c in pool if c.page == entry.page]
                near = [c for c in pool if abs(c.page - entry.page) == 1]
                hits = [min(exact, key=lambda c: (not c.weak, c.order))] if exact else near
            else:
                bound = next((e.page for e in self.entries[k + 1 :] if e.page is not None), None)
                hits = [c for c in pool if bound is None or c.page <= bound]
            if len(hits) > 1:
                self._ambiguous(entry, hits)
                continue
            if hits:
                entry.heading = self._accept(hits[0], f"toc={entry.block.id}")
                after = hits[0].order

    def _accept_by_style(self) -> None:
        """Unclaimed heading-like candidates: same style as claimed headings, or a smaller
        style repeated under them; with no claims, strong ones and repeated styles. A style
        repeats when it opens lines on two pages or more (a title page's subtitle and author
        line share a page)."""
        free = [c for c in self.cands if c.weak and c.id not in self.heads]
        repeats = Counter(style for style, _ in {(c.style, c.page) for c in free})
        if self.heads:
            claimed = list(self.heads.values())
            styles = {h.cand.style for h in claimed}
            smallest = min(size for size, _ in styles)
            first = min(h.cand.order for h in claimed)
            for c in free:
                under = c.style[0] < smallest and repeats[c.style] >= 2 and c.order > first
                if c.style in styles or under:
                    self._accept(c, f"size={c.size:.1f}")
            return
        for c in free:
            if c.strong or repeats[c.style] >= 2:
                self._accept(c, f"size={c.size:.1f}")

    def _levels(self, heads: list[_Heading]) -> None:
        """Levels from relative size, then numbering: parts above chapters of the same
        style, "3.1" below "3"; verified bookmark levels win when every heading has one."""
        if heads and all(h.bookmark_level for h in heads):
            for h in heads:
                h.level = min(h.bookmark_level, 6)
            return
        rank: dict[float, int] = {}
        for size in sorted({h.cand.size for h in heads}, reverse=True):
            close = [s for s in rank if s - size <= SIZE_STEP]
            rank[size] = rank[close[-1]] if close else len(set(rank.values()))
        kinds = {h.cand.id: _kind(h.cand.title) for h in heads}
        part_ranks = {rank[h.cand.size] for h in heads if kinds[h.cand.id][0] == "part"}
        plain_ranks = {rank[h.cand.size] for h in heads if kinds[h.cand.id][0] != "section"}
        for h in heads:
            r, (kind, depth) = rank[h.cand.size], kinds[h.cand.id]
            h.level = r + 1
            if part_ranks and kind != "part" and r >= min(part_ranks):
                h.level += 1
            if kind == "section" and r in plain_ranks:
                h.level += depth - 1
        steps = {lv: k + 1 for k, lv in enumerate(sorted({h.level for h in heads}))}
        for h in heads:
            h.level = min(steps[h.level], 6)

    # ----------------------------------------------------------------------- output side

    def _locate(self, head: _Heading) -> bool:
        """Find the heading's title in the output between the body text around it, outside
        the printed contents; prefer the copy aligned to it, else the one nearest the text
        that follows (a running header lookalike comes before the real heading)."""
        al = self.al
        own = [i for b in head.cand.blocks for i in al.source_block_tokens(b.id)]
        if not own:
            return False
        mine = set(own)
        prev = self._neighbour(min(own) - 1, -1, mine)
        nxt = self._neighbour(max(own) + 1, 1, mine)
        lo = prev + 1 if prev is not None else 0
        hi = nxt if nxt is not None else len(al.output)
        if hi < lo:
            lo, hi = 0, len(al.output)
        want = head.cand.norms
        inside = head.cand.page in self.contents_pages  # the contents page's own title
        n = len(want)
        starts = [
            p
            for p in range(lo, hi - n + 1)
            if all(al.output[p + k].norm == want[k] for k in range(n))
            and (inside or self._outside_contents(p, n))
        ]
        aligned = [p for p in starts if any(al.out_to_src[p + k] in mine for k in range(n))]
        if hi - lo == len(al.output) and not aligned:
            return False
        chosen = (aligned or starts or [None])[-1]
        if chosen is None:
            return False
        pieces: list[tuple[str, int, int]] = []
        for tok in al.output[chosen : chosen + n]:
            if pieces and pieces[-1][0] == tok.block:
                pieces[-1] = (tok.block, pieces[-1][1], tok.end)
            else:
                pieces.append((tok.block, tok.start, tok.end))
        head.pieces = pieces
        return True

    def _neighbour(self, i: int, step: int, mine: set[int]) -> int | None:
        al = self.al
        while 0 <= i < len(al.source):
            if i not in mine and al.source[i].block not in self.skip and al.src_to_out[i] >= 0:
                return al.src_to_out[i]
            i += step
        return None

    def _outside_contents(self, p: int, n: int) -> bool:
        for tok in self.al.output[p : p + n]:
            pages = set(self.al.block_pages(tok.block))
            if pages and pages <= self.contents_pages:
                return False
        return True

    def _element(self, block_id: str) -> etree._Element:
        return self.book.element(f"{self.al.hrefs[block_id]}#{block_id}")[1]

    def _doc_of(self, el: etree._Element) -> ContentDoc:
        root = el.getroottree().getroot()
        return next(d for d in self.book.docs if d.tree is root)

    def _anchor(self, el: etree._Element) -> str:
        return f"{self._doc_of(el).href}#{el.get('id')}"

    # ----------------------------------------------------------------------- edits

    def _demote_furniture(self, keep: set[str]) -> None:
        """Calibre headings made of running headers or folios become paragraphs."""
        al = self.al
        for block in self.ctx.blocks:
            if block.tag not in HEADINGS or block.id in keep or block.id in al.ambiguous_blocks:
                continue
            js = al.output_block_tokens(block.id)
            sources = {al.source[al.out_to_src[j]].block for j in js if al.out_to_src[j] >= 0}
            if not sources or not sources <= self.furniture or al.unmatched_output_tokens(block.id):
                continue
            el = self._element(block.id)
            el.tag = f"{{{XHTML_NS}}}p"
            self.demoted.add(block.id)
            self.ctx.record(
                FAMILY,
                "demote_heading",
                f"{block.href}#{block.id}",
                [block.id, *sorted(sources)],
                before=f"{block.tag}: {block.text}",
                after="p",
            )

    def _promote(self, heads: list[_Heading]) -> None:
        # Later headings first, so splitting a block keeps earlier offsets valid.
        position = {b.id: k for k, b in enumerate(self.ctx.blocks)}
        for head in sorted(
            heads, key=lambda h: (position[h.pieces[0][0]], h.pieces[0][1]), reverse=True
        ):
            els = [self._element(b) for b, _, _ in head.pieces]
            if any(local_name(el) not in PROMOTABLE for el in els):
                self._unlocated(head, "the heading sits in an element that cannot become one")
                continue
            before = " | ".join(f"{local_name(el)}: {text_of(el)}" for el in els)
            el = _isolate(els, head.pieces[0][1], head.pieces[-1][2])
            tag = f"h{head.level}"
            el.tag = f"{{{XHTML_NS}}}{tag}"
            head.el = el
            self.ctx.record(
                FAMILY,
                "promote_heading",
                f"{self.al.hrefs[head.pieces[0][0]]}#{head.pieces[0][0]}",
                [*head.evidence, f"level={head.level}"],
                before=before,
                after=f"{tag}: {text_of(el)}",
            )

    def _split(self, heads: list[_Heading]) -> None:
        """Each top-level heading (and each chapter under a part) starts a spine document,
        unless only page furniture (a running header Calibre kept) comes before it."""
        parts = any(_kind(h.cand.title)[0] == "part" for h in heads)
        depth = 2 if parts else 1
        for head in heads:
            if head.level > depth:
                continue
            doc = self._doc_of(head.el)
            if self._only_furniture_before(doc, head.el):
                continue
            new = self.book.split_doc(doc.href, head.el.get("id"))
            if new != doc.href:
                self.ctx.record(
                    FAMILY,
                    "split_doc",
                    f"{new}#{head.el.get('id')}",
                    [doc.href, head.cand.id],
                )

    def _only_furniture_before(self, doc: ContentDoc, target: etree._Element) -> bool:
        al = self.al
        for el in doc.body.iter(etree.Element):
            if el is target:
                return True
            if not is_block(el) or not text_of(el).strip():
                continue
            js = al.output_block_tokens(el.get("id") or "")
            sources = {al.source[al.out_to_src[j]].block for j in js if al.out_to_src[j] >= 0}
            if not sources or not sources <= self.furniture:
                return False
        return True

    def _link_entries(self) -> None:
        """Wrap each printed contents entry's title in a link to its verified heading."""
        al = self.al
        on_contents = {
            b.id for b in self.ctx.blocks if set(al.block_pages(b.id)) & self.contents_pages
        }
        toks = [t for t in al.output if t.block in on_contents]
        cursor = 0
        for entry in self.entries:
            head = entry.heading
            n = len(entry.norms)
            if head is None or head.el is None or not n:
                continue
            found = next(
                (
                    p
                    for p in range(cursor, len(toks) - n + 1)
                    if [t.norm for t in toks[p : p + n]] == entry.norms
                    and len({t.block for t in toks[p : p + n]}) == 1
                ),
                None,
            )
            if found is None:
                continue
            cursor = found + n
            block = toks[found].block
            el = self._element(block)
            target = self._anchor(head.el)
            href = relative_href(self.al.hrefs[block], target)
            if _wrap(el, toks[found].start, toks[found + n - 1].end, href):
                self.ctx.record(
                    FAMILY,
                    "link_contents_entry",
                    f"{self.al.hrefs[block]}#{block}",
                    [entry.block.id, target],
                    after=target,
                )

    # ----------------------------------------------------------------------- navigation

    def _positions(self) -> dict[str, tuple[int, int]]:
        found = {}
        for k, doc in enumerate(self.book.docs):
            found[doc.href] = (k, -1)
            for i, el in enumerate(doc.body.iter(etree.Element)):
                if (ident := el.get("id")) is not None:
                    found.setdefault(f"{doc.href}#{ident}", (k, i))
        return found

    def _rebuild_toc(self, heads: list[_Heading]) -> None:
        """Navigation from the accepted headings. A Calibre entry is replaced by the heading
        with its title; one that lands on its own title elsewhere is kept and flagged; the
        rest (placeholders such as "Start") are dropped in favour of the verified list."""
        position = self._positions()
        flat: list[tuple[tuple[int, int], NavEntry]] = []
        for head in heads:
            href = self._anchor(head.el)
            title = " ".join(text_of(head.el).split())
            flat.append((position[href], NavEntry(title, href, head.level)))
            self.expected[href] = head.cand.norms
        old = list(_flatten(self.book.toc))
        for entry in old:
            norms = norms_of(entry.title)
            if any(same_title(norms, h.cand.norms) for h in heads):
                continue
            if entry.href in position and norms_of(self._visible(entry.href)) == norms:
                flat.append((position[entry.href], NavEntry(entry.title, entry.href, entry.level)))
                self.expected[entry.href] = norms
                self.ctx.issue(
                    "nav.ambiguous_heading",
                    Severity.MINOR,
                    "kept a converter navigation entry that matches no verified heading",
                    output_anchor=entry.href,
                    evidence=f"target={entry.href}",
                    action="kept",
                    uncertainty="the entry's heading is not confirmed by the source",
                )
            else:
                self.ctx.record(
                    FAMILY,
                    "remove_nav_entry",
                    entry.href,
                    [entry.href, f"verified={len(heads)}"],
                    before=entry.title,
                )
        flat.sort(key=lambda item: item[0])
        self.book.toc = _nest([entry for _, entry in flat])
        self.ctx.record(
            FAMILY,
            "rebuild_toc",
            self.book.nav_href,
            [f"entries={len(flat)}", f"replaced={len(old)}"],
            before=" | ".join(e.title for e in old),
            after=" | ".join(e.title for _, e in flat),
        )

    def _set_landmarks(self, heads: list[_Heading]) -> None:
        def titled(head: _Heading, titles: frozenset[str]) -> bool:
            return " ".join(head.cand.norms) in titles

        last_contents = max(self.contents_pages, default=-1)
        toc = next((h for h in heads if h.cand.page in self.contents_pages), None) or next(
            (h for h in heads if titled(h, _CONTENTS_TITLES)), None
        )
        index = next((h for h in heads if titled(h, _INDEX_TITLES)), None)
        body = next(
            (
                h
                for h in heads
                if h.cand.page > last_contents
                and h is not index
                and h is not toc
                and not titled(h, _CONTENTS_TITLES)
            ),
            None,
        )
        marks = [m for m in self.book.landmarks if m.type not in ("toc", "bodymatter", "index")]
        chosen = [(kind, h) for kind, h in (("toc", toc), ("bodymatter", body), ("index", index))]
        for kind, head in reversed(chosen):
            if head is None:
                continue
            href = self._anchor(head.el)
            marks.insert(0, Landmark(kind, " ".join(text_of(head.el).split()), href))
            self.expected[href] = head.cand.norms
            self.ctx.record(FAMILY, f"landmark_{kind}", href, [head.cand.id])
        self.book.landmarks = marks

    def _visible(self, href: str) -> str:
        try:
            _, el = self.book.element(href)
        except KeyError:
            return ""
        if local_name(el) != "body":
            return text_of(el)
        return next((t for b in el.iter(etree.Element) if is_block(b) and (t := text_of(b))), "")

    def _verify(self) -> None:
        """Every generated target must show its accepted title; drop and flag any that do not."""

        def lands(href: str) -> bool:
            want = self.expected.get(href)
            return want is not None and norms_of(self._visible(href)) == want

        def keep(entries: list[NavEntry]) -> list[NavEntry]:
            kept = []
            for entry in entries:
                entry.children = keep(entry.children)
                if lands(entry.href):
                    kept.append(entry)
                else:
                    self._bad_target(entry.href)
                    kept.extend(entry.children)
            return kept

        self.book.toc = keep(self.book.toc)
        marks = []
        for mark in self.book.landmarks:
            if mark.type in ("toc", "bodymatter", "index") and not lands(mark.href):
                self._bad_target(mark.href)
            else:
                marks.append(mark)
        self.book.landmarks = marks

    # ----------------------------------------------------------------------- issues

    def _label(self, page: int | None) -> str | None:
        if page is None or not 0 <= page < len(self.source.pages):
            return None
        p = self.source.pages[page]
        return p.label or (p.folio_candidates[0] if p.folio_candidates else None)

    def _missing(self, entry: _Entry) -> None:
        self.ctx.issue(
            "nav.missing_chapter",
            Severity.MAJOR,
            "a printed contents entry has no verified heading in the book",
            page=entry.page,
            page_label=entry.label,
            evidence=f"toc={entry.block.id} page={entry.page if entry.page is not None else -1}",
            action="flagged",
            uncertainty="the chapter may be missing, retitled or on another page",
            material=True,
        )

    def _ambiguous(self, entry: _Entry, hits: list[_Cand]) -> None:
        self.ctx.issue(
            "nav.ambiguous_heading",
            Severity.MAJOR,
            "a contents entry matches several headings and no page settles it",
            page=entry.page,
            evidence=f"toc={entry.block.id} candidates={','.join(c.id for c in hits)}",
            action="flagged",
            uncertainty="which heading the entry names",
        )

    def _unlocated(self, head: _Heading, why: str = "the heading was not found in the output"):
        head.pieces = []
        self.ctx.issue(
            "nav.ambiguous_heading",
            Severity.MAJOR,
            why,
            page=head.cand.page,
            page_label=self._label(head.cand.page),
            evidence=" ".join(head.evidence),
            action="flagged",
            uncertainty="the verified heading has no navigation target",
        )

    def _bad_target(self, href: str) -> None:
        self.ctx.issue(
            "nav.ambiguous_heading",
            Severity.MAJOR,
            "a navigation target does not show its title; the entry was dropped",
            output_anchor=href,
            evidence=f"target={href}",
            action="removed",
        )

    def _abstain(self) -> None:
        calibre = sum(b.tag in HEADINGS and b.id not in self.demoted for b in self.ctx.blocks)
        weak = sum(1 for c in self.cands if c.weak)
        signals = self.entries or self.source.bookmarks or weak or calibre
        if not signals and self.source.page_count < SIGNAL_PAGES:
            return
        self.ctx.issue(
            "nav.unresolved_structure",
            Severity.MAJOR,
            "chapter divisions could not be verified; source order and navigation kept",
            evidence=(
                f"toc_entries={len(self.entries)} bookmarks={len(self.source.bookmarks)} "
                f"candidates={weak} calibre_headings={calibre} pages={self.source.page_count}"
            ),
            action="flagged",
            uncertainty="the navigation may list no chapters or the wrong ones",
        )


# --------------------------------------------------------------------------- helpers


def _isolate(els: list[etree._Element], start: int, end: int) -> etree._Element:
    """Make the title running from ``start`` in the first element to ``end`` in the last
    one element of its own: split off text before and after it, merge the middle."""
    first, last = els[0], els[-1]
    if re.search(r"\w", text_of(last)[end:]):
        split_at(last, end)
    head = split_at(first, start) if re.search(r"\w", text_of(first)[:start]) else first
    for el in els[1:]:
        merge(head, el, sep=" ")
    return head


def _wrap(el: etree._Element, start: int, end: int, href: str) -> bool:
    """Wrap characters ``start:end`` of ``el``'s text in ``<a href>``, keeping markup. The
    ends are lifted out of inline elements they open or close; if they still sit in
    different parents, or a link is already there, nothing changes."""
    if next(el.iter(LINK), None) is not None or any(
        local_name(a) == "a" for a in el.iterancestors()
    ):
        return False
    m_end, m_start = etree.Element(MARKER), etree.Element(MARKER)
    insert_at(el, end, m_end)
    insert_at(el, start, m_start)
    _lift(m_start, el, before=True)
    _lift(m_end, el, before=False)
    parent = m_start.getparent()
    if parent is not m_end.getparent():
        remove_keep_tail(m_start)
        remove_keep_tail(m_end)
        return False
    link = etree.Element(LINK, href=href)
    parent.insert(parent.index(m_start), link)
    link.text, m_start.tail = m_start.tail, None
    node = m_start.getnext()
    while node is not m_end:
        following = node.getnext()
        link.append(node)
        node = following
    parent.remove(m_start)
    remove_keep_tail(m_end)
    return True


def _lift(marker: etree._Element, top: etree._Element, before: bool) -> None:
    """Move an empty marker out of inline parents it sits at the very start (``before``)
    or very end of, so both ends of a range can share a parent."""
    while (parent := marker.getparent()) is not top:
        grand = parent.getparent()
        if before:
            if (parent.text or "") or marker.getprevious() is not None:
                return
            parent.text, marker.tail = marker.tail, None
            parent.remove(marker)
            grand.insert(grand.index(parent), marker)
        else:
            if (marker.tail or "") or marker.getnext() is not None:
                return
            parent.remove(marker)
            marker.tail, parent.tail = parent.tail, None
            grand.insert(grand.index(parent) + 1, marker)


def _flatten(entries: list[NavEntry]):
    for entry in entries:
        yield entry
        yield from _flatten(entry.children)


def _nest(flat: list[NavEntry]) -> list[NavEntry]:
    root: list[NavEntry] = []
    stack: list[tuple[int, NavEntry]] = []
    for entry in flat:
        wanted = entry.level
        while stack and stack[-1][0] >= wanted:
            stack.pop()
        entry.children = []
        entry.level = len(stack) + 1
        (stack[-1][1].children if stack else root).append(entry)
        stack.append((wanted, entry))
    return root
