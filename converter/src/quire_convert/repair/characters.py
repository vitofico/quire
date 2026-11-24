"""Find strange characters in the converted text and repair only what the source proves.

Three things are looked for in every source-derived output block:

* characters no reader can use: U+FFFD, private-use code points (often a ligature the
  font never mapped) and control characters. They are reported with the page region
  that holds the word, never guessed;
* mojibake, UTF-8 text read as Windows-1252 or Latin-1 ("caffÃ¨" for "caffè"). A word is
  repaired only when the decoded form equals the source text layer at that position;
* aligned words where the output and the source text layer disagree. Nothing is changed:
  a name, number, date, unit or negation is a major issue, anything else a minor one.

Latin presentation ligatures ("ﬁ") are also expanded, which never changes a word as
matched. No OCR is run; where it would help, the issue says so.
"""

from __future__ import annotations

import logging
import re
import unicodedata
from typing import TYPE_CHECKING

from ..align.align import DUPLICATE, MOVED, refresh
from ..align.normalize import tokenize
from ..models import Box, Severity
from .paragraphs import replace_text

if TYPE_CHECKING:
    from ..align.align import Alignment
    from ..context import ConvertContext
    from ..models import OutputBlock

log = logging.getLogger(__name__)

FAMILY = "characters"
MAX_ISSUES = 50  # per issue code; the rest are counted in one summary issue
MAX_SPAN = 6  # source words a repaired or flagged output word may stand for

LIGATURES = {"ﬀ": "ff", "ﬁ": "fi", "ﬂ": "fl", "ﬃ": "ffi", "ﬄ": "ffl", "ﬅ": "st", "ﬆ": "st"}
# Characters a UTF-8 continuation byte (0x80-0xBF) turns into when read as Windows-1252,
# or as Latin-1 for the five bytes Windows-1252 leaves undefined.
_CONT = "".join(bytes([b]).decode("cp1252", errors="ignore") or chr(b) for b in range(0x80, 0xC0))
_MOJIBAKE = re.compile("[Â-ß][{c}]|[à-ï][{c}]{{2}}|[ð-ô][{c}]{{3}}".format(c=re.escape(_CONT)))
_LIGATURE = re.compile("[" + "".join(LIGATURES) + "]")
OCR_NOTE = "OCR of this region would settle it; no OCR was run."

NEGATIONS = frozenset(
    "no not nor never none nothing nobody neither non nessuno niente nulla mai né ne "
    "pas jamais rien nicht kein keine nie".split()
)
MONTHS = frozenset(
    "january february march april may june july august september october november december "
    "gennaio febbraio marzo aprile maggio giugno luglio agosto settembre ottobre novembre "
    "dicembre".split()
)
UNITS = frozenset(
    "mm cm m km g kg mg l ml cl s ms h min kb mb gb hz khz w kw kwh v a c f °c °f "
    "in ft yd mi lb oz pt qt gal".split()
)


def _bad(ch: str) -> bool:
    """Whether ``ch`` cannot be shown as text: U+FFFD, private use or a control code."""
    cat = unicodedata.category(ch)
    return ch == "\ufffd" or cat == "Co" or (cat == "Cc" and ch not in "\t\n\r")


def _demojibake(text: str) -> str | None:
    """``text`` with every mojibake sequence decoded, or None when one does not decode
    into a plausible character."""

    def fix(m: re.Match) -> str:
        raw = bytes(_byte(ch) for ch in m.group())
        decoded = raw.decode("utf-8")  # the pattern only admits well-formed sequences
        cp = ord(decoded)
        if unicodedata.category(decoded)[0] == "C" or not (cp < 0x0590 or 0x1E00 <= cp < 0x2C00):
            raise ValueError
        return decoded

    try:
        fixed = _MOJIBAKE.sub(fix, text)
    except (ValueError, UnicodeDecodeError):
        return None
    return fixed if fixed != text else None


def _byte(ch: str) -> int:
    try:
        return ch.encode("cp1252")[0]
    except UnicodeEncodeError:
        return ord(ch)  # Latin-1 reading of the bytes cp1252 leaves undefined


def _critical(word: str) -> bool:
    """A word whose misreading changes meaning: numbers, names, negations, dates, units."""
    core = word.strip(".,;:!?\"'()[]“”‘’«»…")
    if not core:
        return False
    low = core.casefold()
    return (
        any(ch.isdigit() for ch in core)
        or core[0].isupper()
        or low in NEGATIONS
        or low in MONTHS
        or low in UNITS
    )


class _Scan:
    def __init__(self, ctx: ConvertContext):
        self.ctx = ctx
        self.al: Alignment = ctx.alignment
        self.counts: dict[str, int] = {}
        self.changed = False
        self.flagged: set[int] = set()  # output tokens already reported
        self.by_id = {b.id: b for b in ctx.blocks}

    def run(self) -> None:
        for block in self.ctx.blocks:
            if block.alignment == "generated" or block.id in self.al.generated:
                continue
            self._block(block)
        self._disagreements()
        for code, n in self.counts.items():
            if n > MAX_ISSUES:
                self.ctx.issue(
                    code,
                    Severity.MAJOR,
                    "More characters of this kind were found than are listed",
                    evidence=f"more={n - MAX_ISSUES}",
                    action="flagged",
                )

    # ---- one block

    def _block(self, block: OutputBlock) -> None:
        _, el = self.ctx.book.element(f"{block.href}#{block.id}")
        text = block.text
        edits: list[tuple[int, int, str, str]] = []  # start, end, new, action
        for m in re.finditer(r"\S+", text):
            word, start, end = m.group(), m.start(), m.end()
            fixed = _demojibake(word)
            if fixed is not None:
                self._mojibake(block, word, fixed, start, end, edits)
                continue
            bad = [ch for ch in word if _bad(ch)]
            if bad:
                self._unreadable(block, start, end, bad)
        for m in _LIGATURE.finditer(text):
            edits.append((m.start(), m.end(), LIGATURES[m.group()], "expand_ligature"))
        applied: dict[str, list[tuple[int, int, str]]] = {}
        for start, end, new, action in sorted(edits, key=lambda e: -e[0]):
            if replace_text(el, start, end, new):
                applied.setdefault(action, []).append((start, end, new))
        for action, done in applied.items():
            done.reverse()
            self.ctx.record(
                FAMILY,
                action,
                f"{block.href}#{block.id}",
                [block.id, f"count={len(done)}", *self._pages(block, done)],
                before=" | ".join(text[max(0, s - 15) : e + 15] for s, e, _ in done),
                after=" | ".join(
                    text[max(0, s - 15) : s] + new + text[e : e + 15] for s, e, new in done
                ),
            )
            self.changed = True

    def _pages(self, block: OutputBlock, done) -> list[str]:
        pages = set()
        for start, end, _ in done:
            span = self._source_span(block.id, start, end)
            pages |= {self.al.source[i].page for i in span or []}
        return [f"page={p}" for p in sorted(pages)]

    def _mojibake(self, block, word, fixed, start, end, edits) -> None:
        span = self._source_span(block.id, start, end)
        source = [self.al.source[i].norm for i in span or []]
        if span is not None and _norms(fixed) == source and _norms(word) != source:
            for m in _MOJIBAKE.finditer(word):
                edits.append(
                    (start + m.start(), start + m.end(), _demojibake(m.group()), "fix_mojibake")
                )
            self._settle(block.id, start, end)
            return
        agrees = span is not None and _norms(word) == source
        self._flag(
            "characters.mojibake",
            Severity.MAJOR,
            "Text that looks like misread UTF-8 was left unchanged",
            block,
            start,
            end,
            span,
            f"source_same={int(agrees)}",
            "The decoded form does not match the source text layer at this position.",
        )

    def _unreadable(self, block, start, end, bad) -> None:
        span = self._source_span(block.id, start, end)
        points = ".".join(sorted({f"0x{ord(ch):04x}" for ch in bad}))
        self._flag(
            "characters.replacement_char",
            Severity.MAJOR,
            "The converted text holds characters that cannot be read",
            block,
            start,
            end,
            span,
            f"count={len(bad)} codepoints={points}",
            "The text layer has no usable character here. " + OCR_NOTE,
        )

    # ---- aligned words that disagree

    def _disagreements(self) -> None:
        al = self.al
        for j, tok in enumerate(al.output):
            i = al.out_to_src[j]
            if i < 0 or al.out_kind[j] in (MOVED, DUPLICATE) or j in self.flagged:
                continue
            if tok.norm == al.source[i].norm or al.src_to_out[i] != j:
                continue
            if any(0 <= k < len(al.output) and al.out_to_src[k] == i for k in (j - 1, j + 1)):
                continue  # a word Calibre split in two ("conti" "nued"), not a misreading
            block = self.by_id[tok.block]
            word = block.text[tok.start : tok.end]
            src_word = self._source_word(i)
            if _critical(word) or _critical(src_word):
                code, sev, msg = (
                    "characters.uncertain_token",
                    Severity.MAJOR,
                    "A name, number, date, unit or negation differs from the source; "
                    "it was not changed",
                )
            else:
                code, sev, msg = (
                    "characters.mismatch",
                    Severity.MINOR,
                    "A word differs from the source text layer; it was not changed",
                )
            self._flag(
                code,
                sev,
                msg,
                block,
                tok.start,
                tok.end,
                [i],
                f"src={i} out={j}",
                "Output and source text layer read this word differently. " + OCR_NOTE,
            )

    def _source_word(self, i: int) -> str:
        t = self.al.source[i]
        page = self.ctx.source.pages[t.page]
        block = next(b for b in page.blocks if b.id == t.block)
        return block.lines[t.line].words[t.word].text

    # ---- positions

    def _matched(self, j: int) -> int:
        i = self.al.out_to_src[j]
        return i if i >= 0 and self.al.out_kind[j] not in (MOVED, DUPLICATE) else -1

    def _source_span(self, block_id: str, start: int, end: int) -> list[int] | None:
        """Source tokens that stand where output characters ``[start, end)`` are, from the
        matched words around and inside the range; None when that is unclear."""
        js = self.al.output_block_tokens(block_id)
        inside = [i for j in js if self._overlaps(j, start, end) and (i := self._matched(j)) >= 0]
        left = [i for j in js if self.al.output[j].end <= start and (i := self._matched(j)) >= 0]
        right = [i for j in js if self.al.output[j].start >= end and (i := self._matched(j)) >= 0]
        lo = left[-1] + 1 if left else (min(inside) if inside else None)
        hi = right[0] - 1 if right else (max(inside) if inside else None)
        if lo is None or hi is None or hi - lo + 1 > MAX_SPAN:
            return None
        span = list(range(lo, hi + 1))
        if len({self.al.source[i].page for i in span}) > 1:
            return None
        return span

    def _overlaps(self, j: int, start: int, end: int) -> bool:
        tok = self.al.output[j]
        return tok.start < end and tok.end > start

    # ---- issues

    def _settle(self, block_id: str, start: int, end: int) -> None:
        """Keep the disagreement scan off words already repaired or reported."""
        for j in self.al.output_block_tokens(block_id):
            if self._overlaps(j, start, end):
                self.flagged.add(j)

    def _flag(self, code, severity, message, block, start, end, span, detail, why) -> None:
        self._settle(block.id, start, end)
        self.counts[code] = self.counts.get(code, 0) + 1
        if self.counts[code] > MAX_ISSUES:
            return
        page, region = self._where(block, start, span)
        self.ctx.issue(
            code,
            severity,
            message,
            page=page,
            page_label=self.ctx.source.pages[page].label if page is not None else None,
            region=region,
            output_anchor=f"{block.href}#{block.id}",
            evidence=" ".join(
                [block.id, *([f"page={page}"] if page is not None else []), f"offset={start}"]
                + [detail]
            ),
            action="flagged",
            uncertainty=why,
        )

    def _where(self, block, start: int, span: list[int] | None) -> tuple[int | None, Box | None]:
        """Source page and region (crop-box coordinates) of an output range."""
        if span:
            boxes = [self.al.source[i].box for i in span]
            region = boxes[0]
            for box in boxes[1:]:
                region = region.union(box)
            return self.al.source[span[0]].page, region
        pos = self.al.output_to_source(block.id, start)
        if pos is None:
            return None, None
        page = self.ctx.source.pages[pos.page]
        line = next(b for b in page.blocks if b.id == pos.block).lines[pos.line]
        return pos.page, line.box


def _norms(text: str) -> list[str]:
    return [t.norm for t in tokenize(text)]


def apply(ctx: ConvertContext) -> None:
    """Repair mojibake the source confirms, expand ligatures, report unreadable or
    disagreeing characters."""
    if ctx.alignment is None:
        refresh(ctx)
    scan = _Scan(ctx)
    scan.run()
    if scan.changed:
        refresh(ctx)
    log.info("characters: %s", " ".join(f"{k}={v}" for k, v in sorted(scan.counts.items())))
