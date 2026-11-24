"""Link footnote markers and note bodies both ways.

Note bodies are the source blocks ``coverage.note_blocks`` finds (lower half of the page, set
smaller, opening with a note glyph); a block holding several notes is split at the lines
that open with the next number. Markers are note glyphs (1, *, †, ¹) in body text, set
smaller than their line and raised above its baseline, attached to the end of a word: not
to the start of the next one (an isotope's ``¹⁴C``) and not to a variable or formula
(``m²``, ``E = mc²``). An ordinal's raised letters (1st, 2º) are no glyph at all.

Numbering is scoped as the source does it: per page when it restarts on each page, else
running, which lets a marker near the foot of a page take its body from the next page.
Each marker pairs with the one body of its number in scope, and every page's pairs must
run in the same order on both sides. A pair becomes a noteref around the existing marker
text and an ``<aside epub:type="footnote">`` holding the moved note text, whose printed
marker becomes the backlink. A page's notes go together after the last paragraph that
references them: after the paragraph holding the reference, without pulling the page's
notes ahead of prose that separates two references. Anything uncertain stays exactly
where Calibre put it, with an issue.
"""

from __future__ import annotations

import re
import statistics
import unicodedata
from dataclasses import dataclass, field
from typing import TYPE_CHECKING

from lxml import etree

from ..align import align
from ..align.coverage import note_blocks, running_furniture
from ..epub.inline import text_of
from ..epub.package import EPUB_NS
from ..models import PageInventory, Severity, TextBlock, Word
from .figures import Edits, widen, wrap, xhtml

if TYPE_CHECKING:
    from ..context import ConvertContext

FAMILY = "notes"
MARKER_SIZE = 0.8  # a marker is set at most this share of its line's type size
MARKER_RISE = 0.15  # and its foot sits this share of the line height above the baseline
GLUE_PT = 1.0  # a gap narrower than this joins two words
_GLYPH = re.compile(r"^[(\[]?(\d{1,3}|[*†‡§¶]{1,3}|[⁰¹²³⁴⁵⁶⁷⁸⁹]{1,3})[)\]]?[.):]?$")
_GLUED = re.compile(r"^(?P<host>.*[^\s*†‡⁰¹²³⁴⁵⁶⁷⁸⁹])(?P<mark>[⁰¹²³⁴⁵⁶⁷⁸⁹]{1,3}|[*†‡]{1,3})$")
_SUPERSCRIPT = set("⁰¹²³⁴⁵⁶⁷⁸⁹")
# A variable or a chemical formula: x, m, Fe, CO, SO4, H2O.
_FORMULA = re.compile(r"^(?:[A-Za-z]|(?:[A-Z][a-z]?\d*)+)$")
_SLUG = {"*": "ast", "†": "dagger", "‡": "ddagger", "§": "sect", "¶": "para"}


def apply(ctx: ConvertContext) -> None:
    _Notes(ctx).run()


@dataclass
class _Note:
    page: int
    value: str
    block: TextBlock
    tokens: list[int]  # source token indices, marker included


@dataclass
class _Marker:
    page: int
    value: str
    block: TextBlock
    token: int | None  # the marker's own source token (digits), else None
    host: int | None  # last source token of the word it is attached to
    strong: bool  # small and raised, or a superscript digit: a reference for certain
    order: int  # reading position: a source token index


@dataclass
class _Pair:
    marker: _Marker
    note: _Note
    ref_range: tuple[str, int, int] = ("", 0, 0)  # output block, start, end
    body_ranges: list[tuple[str, int, int]] = field(default_factory=list)
    parts: list[etree._Element | None] = field(default_factory=list)
    aside: etree._Element | None = None
    note_id: str = ""
    ref_id: str = ""


def value_of(glyph: str) -> str:
    return unicodedata.normalize("NFKC", glyph)


class _Notes:
    def __init__(self, ctx: ConvertContext):
        self.ctx = ctx
        self.al = ctx.alignment
        self.source = ctx.source
        self.furniture = running_furniture(ctx.source)
        self.note_ids = note_blocks(ctx.source, self.furniture)
        self.word_tokens: dict[tuple[str, int, int], list[int]] = {}
        for i, tok in enumerate(self.al.source):
            self.word_tokens.setdefault((tok.block, tok.line, tok.word), []).append(i)
        self.dupes = {
            self.al.out_to_src[j]
            for j, kind in enumerate(self.al.out_kind)
            if kind == align.DUPLICATE
        }
        self.edits = Edits(ctx)
        self.taken = {
            ident
            for d in ctx.book.docs
            for el in d.tree.iter(etree.Element)
            if (ident := el.get("id"))
        }

    def run(self) -> None:
        notes = {p.index: self._notes(p) for p in self.source.pages}
        if not any(notes.values()):
            return
        markers = {p.index: self._markers(p) for p in self.source.pages}
        scope = "page" if _restarts(notes) else "running"
        pairs: list[_Pair] = []
        claimed: set[int] = set()  # id() of bodies a marker on the previous page took
        for page in self.source.pages:
            pairs += self._pair_page(page, notes, markers, scope, claimed)
        pairs = self._place([p for p in pairs if self._plan(p)])
        if not pairs:
            return
        self.edits.apply()
        for pair in pairs:
            self._finish(pair, scope)
        align.refresh(self.ctx)

    # ---- source side

    def _notes(self, page: PageInventory) -> list[_Note]:
        found: list[_Note] = []
        for block in page.blocks:
            if block.id not in self.note_ids:
                continue
            starts = []
            for li, line in enumerate(block.lines):
                match = _GLYPH.match(line.words[0].text) if line.words else None
                if not match:
                    continue
                value = value_of(match.group(1))
                # A repeated number opens a rival body; anything else must follow on.
                previous = starts[-1][1] if starts else ""
                if li == 0 or _follows(previous, value) or any(v == value for _, v in starts):
                    starts.append((li, value))
            for k, (li, value) in enumerate(starts):
                end = starts[k + 1][0] if k + 1 < len(starts) else len(block.lines)
                tokens = [
                    i
                    for ln in range(li, end)
                    for wi in range(len(block.lines[ln].words))
                    for i in self.word_tokens.get((block.id, ln, wi), [])
                ]
                found.append(_Note(page.index, value, block, tokens))
        return found

    def _markers(self, page: PageInventory) -> list[_Marker]:
        found = []
        for block in align.reading_order(page):
            if block.id in self.note_ids or block.id in self.furniture:
                continue
            for li, line in enumerate(block.lines):
                for wi, word in enumerate(line.words):
                    marker = self._marker(page, block, li, wi, word)
                    if marker is not None:
                        found.append(marker)
        return found

    def _marker(self, page, block, li, wi, word: Word) -> _Marker | None:
        words = block.lines[li].words
        prev = words[wi - 1] if wi else None
        nxt = words[wi + 1] if wi + 1 < len(words) else None
        own = self.word_tokens.get((block.id, li, wi), [])
        if (match := _GLYPH.match(word.text)) is not None:
            glyph = match.group(1)
            superscript = glyph[0] in _SUPERSCRIPT
            if not superscript and not self._small_and_raised(word, words):
                return None
            if nxt is not None and self._glued(page, word, nxt):
                return None  # a prefix: ¹⁴C
            if prev is not None and self._glued(page, prev, word) and _formula(prev, words, wi):
                return None  # an exponent: m², mc²
            host = self.word_tokens.get((block.id, li, wi - 1), []) if prev else []
            token = own[-1] if own else None
            host_token = host[-1] if host else None
            order = token if token is not None else host_token
            if order is None:
                return None
            return _Marker(page.index, value_of(glyph), block, token, host_token, True, order)
        glued = _GLUED.match(word.text)
        if glued is None or not own:
            return None
        host_text, glyph = glued.group("host"), glued.group("mark")
        if _FORMULA.match(host_text.rstrip(".,;:")) or _equation(words, wi):
            return None
        superscript = glyph[0] in _SUPERSCRIPT
        token = own[-1] if superscript else None
        host = own[-2] if superscript and len(own) > 1 else own[-1]
        return _Marker(page.index, value_of(glyph), block, token, host, superscript, own[-1])

    @staticmethod
    def _small_and_raised(word: Word, words: list[Word]) -> bool:
        others = [w for w in words if w is not word and not _GLYPH.match(w.text)]
        sizes = [w.font_size for w in others if w.font_size]
        if (
            not sizes
            or not word.font_size
            or word.font_size > MARKER_SIZE * statistics.median(sizes)
        ):
            return False
        base = statistics.median(w.box.y1 for w in others)
        height = statistics.median(w.box.height for w in others)
        return word.box.y1 < base - MARKER_RISE * height

    @staticmethod
    def _glued(page: PageInventory, a: Word, b: Word) -> bool:
        return (b.box.x0 - a.box.x1) * page.width_pt < GLUE_PT

    # ---- pairing

    def _pair_page(self, page, notes, markers, scope, claimed: set[int]) -> list[_Pair]:
        here = [n for n in notes[page.index] if id(n) not in claimed]
        ms = markers[page.index]
        values = list(dict.fromkeys([m.value for m in ms] + [n.value for n in here]))
        pairs = []
        for value in values:
            vm = [m for m in ms if m.value == value]
            vn = [n for n in here if n.value == value]
            if vm and not vn and scope == "running" and page.index + 1 in notes:
                later = markers[page.index + 1]
                vn = [
                    n
                    for n in notes[page.index + 1]
                    if n.value == value and not any(m.value == value for m in later)
                ]
                claimed.update(id(n) for n in vn)
            if len(vn) > 1 or (len(vm) > 1 and vn):
                self._ambiguous(page, value, vm, vn, "several markers or bodies share a number")
            elif vm and not vn:
                for m in vm:
                    if m.strong:
                        self._missing(page, m)
            elif vn and not vm:
                self._unlinked(page, vn[0], "no reference to this note was found")
            elif vn[0].tokens and self._intact(page, vn[0]):
                pairs.append(_Pair(vm[0], vn[0]))
        bodies = [p.note.tokens[0] for p in sorted(pairs, key=lambda p: p.marker.order)]
        if bodies != sorted(bodies):
            for p in pairs:
                self._ambiguous(page, p.marker.value, [p.marker], [p.note], "order differs")
            return []
        return pairs

    def _intact(self, page, note: _Note) -> bool:
        if any(i in self.dupes for i in note.tokens):
            self._issue(
                "note.duplicate_body",
                Severity.CRITICAL,
                "A note's text appears more than once in the EPUB",
                page,
                note.block,
                f"{note.block.id} marker={_code(note.value)} page={note.page}",
                "The copies stay where Calibre put them; the note is not linked.",
                material=True,
            )
            return False
        return True

    # ---- output side

    def _plan(self, pair: _Pair) -> bool:
        """Find the marker and the note text in the EPUB; False (with an issue) if unsure."""
        page = self.source.pages[pair.marker.page]
        ref = self._ref_range(pair.marker)
        if ref is None:
            self._unlinked(page, pair.note, "the reference marker is not in the EPUB")
            return False
        ranges = self._body_ranges(pair.note)
        if ranges is None:
            self._unlinked(page, pair.note, "the note text in the EPUB is mixed with other text")
            return False
        if any(r[0] == ref[0] and not (r[2] <= ref[1] or r[1] >= ref[2]) for r in ranges):
            return False
        pair.ref_range, pair.body_ranges = ref, ranges
        return True

    def _ref_range(self, marker: _Marker) -> tuple[str, int, int] | None:
        al = self.al
        if marker.token is not None:
            j = al.src_to_out[marker.token]
            if j < 0 or al.out_kind[j] == align.DUPLICATE:
                return None
            tok = al.output[j]
            text = self.edits.text(tok.block)
            for start in range(tok.end - 1, tok.start - 1, -1):
                if value_of(text[start : tok.end]) == marker.value:
                    return tok.block, start, tok.end
            return None
        if marker.host is None or (j := al.src_to_out[marker.host]) < 0:
            return None
        tok = al.output[j]
        text = self.edits.text(tok.block)
        k = tok.end
        while k < len(text) and not text[k].isspace() and not text[k:].startswith(marker.value):
            if text[k].isalnum():
                return None
            k += 1
        if not text[k:].startswith(marker.value):
            return None
        return tok.block, k, k + len(marker.value)

    def _body_ranges(self, note: _Note) -> list[tuple[str, int, int]] | None:
        al, own = self.al, set(note.tokens)
        js = sorted(j for i in note.tokens if (j := al.src_to_out[i]) >= 0)
        if len(js) < 0.8 * len(note.tokens):
            return None
        blocks = list(dict.fromkeys(al.output[j].block for j in js))
        ranges = []
        for n, block in enumerate(blocks):
            mine = [j for j in js if al.output[j].block == block]
            text = self.edits.text(block)
            start, end = widen(text, al.output[mine[0]].start, al.output[mine[-1]].end)
            if n == 0:
                start = _glyph_start(text, start, note.value)
            if any(
                start <= al.output[j].start < end and al.out_to_src[j] not in own
                for j in al.output_block_tokens(block)
                if al.out_to_src[j] >= 0
            ):
                return None
            ranges.append((block, start, end))
        first = ranges[0]
        printed = self.edits.text(first[0])[first[1] : first[2]].split(maxsplit=1)
        if not printed or _GLYPH.match(printed[0]) is None:
            return None
        if value_of(_GLYPH.match(printed[0]).group(1)) != note.value:
            return None
        return ranges

    def _place(self, pairs: list[_Pair]) -> list[_Pair]:
        """Plan every edit: noteref wraps, body cuts, and each page's asides after the last
        block that references them (per content document). Returns the pairs planned."""
        groups: dict[tuple[int, str], list[_Pair]] = {}
        placed = []
        for pair in sorted(pairs, key=lambda p: p.marker.order):
            page = pair.marker.page
            if not all(self.edits.free(*r) for r in [pair.ref_range, *pair.body_ranges]):
                self._unlinked(self.source.pages[page], pair.note, "its text overlaps another note")
                continue
            placed.append(pair)
            slug = _slug(pair.marker.value)
            pair.note_id = self._id(f"qc-note-p{page}-{slug}")
            pair.ref_id = self._id(f"qc-noteref-p{page}-{slug}")
            block, start, end = pair.ref_range
            ref = xhtml("a", href=f"#{pair.note_id}", id=pair.ref_id, role="doc-noteref")
            ref.set(f"{{{EPUB_NS}}}type", "noteref")
            self.edits.wrap(block, start, end, ref)
            pair.parts = [None] * len(pair.body_ranges)
            for k, (b, s, e) in enumerate(pair.body_ranges):
                self.edits.cut(b, s, e, lambda mid, p=pair, k=k: p.parts.__setitem__(k, mid))
            href = self.edits.blocks[block].href
            groups.setdefault((page, href), []).append(pair)
        position = {b.id: n for n, b in enumerate(self.ctx.blocks)}
        for group in groups.values():
            anchor = max((p.ref_range[0] for p in group), key=position.__getitem__)
            for pair in group:
                aside = xhtml("aside", id=pair.note_id, role="doc-footnote")
                aside.set(f"{{{EPUB_NS}}}type", "footnote")
                pair.aside = aside
                self.edits.insert(anchor, len(self.edits.text(anchor)), aside)
        return placed

    def _finish(self, pair: _Pair, scope: str) -> None:
        aside = pair.aside
        for part in pair.parts:
            aside.append(part)
        first = aside[0]
        printed = text_of(first).split(maxsplit=1)[0]
        back = xhtml("a", href=f"#{pair.ref_id}", role="doc-backlink")
        wrap(first, 0, len(printed), back)
        marker, note = pair.marker, pair.note
        self.ctx.record(
            FAMILY,
            "link_note",
            f"{self.edits.blocks[pair.ref_range[0]].href}#{pair.note_id}",
            [
                marker.block.id,
                note.block.id,
                pair.ref_range[0],
                *[r[0] for r in pair.body_ranges],
                f"page={marker.page}",
                f"note_page={note.page}",
                f"per_page={int(scope == 'page')}",
            ],
            before=" ".join(self.al.source[i].norm for i in note.tokens),
        )

    def _id(self, base: str) -> str:
        ident, n = base, 1
        while ident in self.taken:
            n += 1
            ident = f"{base}-{n}"
        self.taken.add(ident)
        return ident

    # ---- issues

    def _ambiguous(self, page, value, markers, notes, why) -> None:
        ids = [m.block.id for m in markers] + [n.block.id for n in notes]
        self._issue(
            "note.ambiguous",
            Severity.MAJOR,
            f"A note could not be paired with certainty ({why}); it stays in reading order",
            page,
            (notes or markers)[0].block,
            " ".join(dict.fromkeys(ids)) + f" marker={_code(value)} page={page.index}",
            "The marker and note text stay where Calibre put them, unlinked.",
        )

    def _missing(self, page, marker: _Marker) -> None:
        self._issue(
            "note.missing_body",
            Severity.CRITICAL,
            "A note reference has no note text in its scope",
            page,
            marker.block,
            f"{marker.block.id} marker={_code(marker.value)} page={page.index}",
            "The note may sit elsewhere (endnotes) or be lost from the source text layer.",
            material=True,
        )

    def _unlinked(self, page, note: _Note, why: str) -> None:
        self._issue(
            "note.unlinked",
            Severity.MAJOR,
            f"A note was left unlinked: {why}",
            page,
            note.block,
            f"{note.block.id} marker={_code(note.value)} page={note.page}",
            "The note text stays where Calibre put it.",
        )

    def _issue(self, code, severity, message, page, block, evidence, why, material=False):
        anchor = None
        if (tokens := self.al.source_block_tokens(block.id)) and (
            near := self.al.anchor_near_source(tokens[0])
        ):
            anchor = near
        self.ctx.issue(
            code,
            severity,
            message,
            page=page.index,
            page_label=page.label or (page.folio_candidates[0] if page.folio_candidates else None),
            region=block.box,
            output_anchor=anchor,
            evidence=evidence,
            action="flagged",
            uncertainty=why,
            material=material,
        )


def _restarts(notes: dict[int, list[_Note]]) -> bool:
    """Whether numeric note numbers start again on a later page."""
    last = None
    for page in sorted(notes):
        numbers = [int(n.value) for n in notes[page] if n.value.isdigit()]
        if not numbers:
            continue
        if last is not None and numbers[0] <= last:
            return True
        last = numbers[-1]
    return False


def _follows(prev: str, value: str) -> bool:
    if prev.isdigit() and value.isdigit():
        return int(value) == int(prev) + 1
    return not value.isdigit()


def _formula(host: Word, words: list[Word], index: int) -> bool:
    return bool(_FORMULA.match(host.text)) or _equation(words, index)


def _equation(words: list[Word], index: int) -> bool:
    return any("=" in w.text for w in words[:index])


def _glyph_start(text: str, start: int, value: str) -> int:
    """Pull a body range's start back over a symbol glyph that no token covers ("* Text")."""
    k = start
    while k > 0 and text[k - 1].isspace():
        k -= 1
    if value_of(text[max(0, k - len(value)) : k]) == value and not value.isdigit():
        return widen(text, k - len(value), k)[0]
    return start


def _slug(value: str) -> str:
    return "".join(_SLUG.get(ch, ch) for ch in value)


def _code(value: str) -> str:
    """A marker as redaction-safe evidence: its number, or its symbols' code points."""
    return value if value.isdigit() else "-".join(f"u{ord(ch):04x}" for ch in value)
