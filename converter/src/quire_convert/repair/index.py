"""Link a back-of-book index to the printed pages it cites.

Index pages are found in the source: a page whose heading reads "Index" (or an Italian
equivalent) and whose lines mostly read ``term, 3, 12-14; see also X``, plus the pages
that continue in that shape. Lines are parsed on source words, so the entry grammar
survives Calibre merging a page's lines into one paragraph. A page reference is linked
when its printed label belongs to exactly one verified physical page carrying a
``qc-page-N`` anchor; the link wraps the number already in the text and adds none. A
range is linked at both ends or not at all. An ambiguous or unresolved number stays plain
text and is reported. A "see"/"see also" target is linked only when exactly one entry
carries that term. A page link promises page-level accuracy only.
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field
from typing import TYPE_CHECKING

from lxml import etree

from ..align.align import reading_order
from ..align.coverage import running_furniture
from ..epub import inline
from ..epub.package import XHTML_NS, local_name, relative_href
from ..models import PAGE_ANCHOR_PREFIX, Severity, Word
from . import pages

if TYPE_CHECKING:
    from ..context import ConvertContext

log = logging.getLogger(__name__)

FAMILY = "index"
HEADINGS = frozenset(
    {"index", "general index", "subject index", "indice", "indice analitico", "indice dei nomi"}
)
MIN_SHARE = 0.5  # share of a page's lines that must read as index entries
_NUM = r"\d+|[ivxlcdm]+"
_REF = re.compile(
    rf"^[(\[]?(?P<start>{_NUM})(?:[-–—‑](?P<end>{_NUM}))?(?:n\d*|nn|ff?)?\.?[)\]]?[,;.]?$"
)
_FILLERS = frozenset({"passim", "f.", "ff.", "sgg.", "segg.", "n.", "nn."})
_SEE = frozenset({"see", "vedi", "cf", "cfr"})
_ALSO = frozenset({"also", "anche"})
_PUNCT = " ,.;:()[]"


@dataclass
class _Ref:
    word: Word
    start: str
    end: str | None  # a range's second endpoint, expanded ("123-24" -> "124")


@dataclass
class _Entry:
    page: int
    block: str  # source block id
    term: list[Word] = field(default_factory=list)
    refs: list[_Ref] = field(default_factory=list)
    see: list[list[Word]] = field(default_factory=list)  # words of each cross-reference target

    @property
    def key(self) -> str:
        return _key(self.term)


def apply(ctx: ConvertContext) -> None:
    if ctx.alignment is None:
        from ..align.align import align_book

        align_book(ctx)
    entries = _parse_index(ctx)
    if not entries:
        return
    anchors = _page_anchors(ctx)
    if not anchors:
        ctx.issue(
            "index.no_page_anchors",
            Severity.MINOR,
            "index found but the book has no page anchors; references left unlinked",
            page=entries[0].page,
            evidence=f"entries={len(entries)} {entries[0].block}",
            action="abstained",
        )
        return
    linker = _Linker(ctx, anchors)
    for entry in entries:
        for ref in entry.refs:
            linker.link_ref(entry, ref)
    linker.link_cross_references(entries)
    log.info("index: %d entries, %d links", len(entries), linker.links)


# --------------------------------------------------------------------------- source side


def _parse_index(ctx: ConvertContext) -> list[_Entry]:
    furniture = running_furniture(ctx.source)
    entries: list[_Entry] = []
    started = False
    for page in ctx.source.pages:
        blocks = [b for b in reading_order(page) if b.id not in furniture and b.text.strip()]
        if not started:
            if not blocks or _norm(blocks[0].text) not in HEADINGS:
                continue
            blocks = blocks[1:]
        found = _parse_page(page.index, blocks)
        lines = sum(len(b.lines) for b in blocks)
        useful = sum(1 for e in found if e.refs or e.see)
        if not useful or useful < MIN_SHARE * lines:
            if started:
                break
            continue
        started = True
        entries += found
    return entries


def _parse_page(page: int, blocks) -> list[_Entry]:
    entries: list[_Entry] = []
    continues = False
    for block in blocks:
        for line in block.lines:
            term, refs, see = _parse_line(line.words, continues)
            if term:
                entries.append(_Entry(page, block.id, term, refs, see))
            elif (refs or see) and entries:
                entries[-1].refs += refs
                entries[-1].see += see
            continues = bool(refs) and refs[-1].word.text.endswith(",")
    return entries


def _parse_line(words: list[Word], continues: bool):
    """Split one index line into (term words, page references, cross-reference targets)."""
    see_at = next((k for k in range(len(words)) if _is_see(words, k)), len(words))
    body = words[:see_at]
    refs: list[_Ref] = []
    term = body
    for k in range(1, len(body)):
        if not body[k - 1].text.rstrip(")").endswith((",", ":")):
            continue
        tail = body[k:]
        if all(_REF.match(w.text) or w.text.casefold() in _FILLERS for w in tail):
            refs = [r for w in tail if (r := _ref(w))]
            if refs:
                term = body[:k]
                break
    else:
        numbers = [_ref(w, roman=False) for w in body]
        if continues and body and all(numbers):
            term, refs = [], numbers
    return term, refs, _see_targets(words[see_at:])


def _is_see(words: list[Word], k: int) -> bool:
    text = words[k].text
    if text.lstrip("(").rstrip(".,;:").casefold() not in _SEE:
        return False
    if k == 0 or text.startswith("("):
        return True
    before = words[k - 1].text
    return before.endswith((",", ".", ";", ":")) or bool(_REF.match(before))


def _see_targets(words: list[Word]) -> list[list[Word]]:
    if not words:
        return []
    rest = words[1:]
    if rest and rest[0].text.rstrip(".,;:").casefold() in _ALSO:
        rest = rest[1:]
    groups: list[list[Word]] = [[]]
    for word in rest:
        groups[-1].append(word)
        if word.text.rstrip(")").endswith(";"):
            groups.append([])
    return [g for g in groups if _key(g)]


def _ref(word: Word, roman: bool = True) -> _Ref | None:
    m = _REF.match(word.text)
    if m is None or (not roman and not m["start"].isdigit()):
        return None
    start, end = m["start"], m["end"]
    if end is None:
        return _Ref(word, start, None)
    if start.isdigit() != end.isdigit():
        return None
    if start.isdigit():
        if len(end) < len(start):  # abbreviated range end: "123-24" means 123-124
            end = start[: len(start) - len(end)] + end
        if int(end) <= int(start):
            return None
    return _Ref(word, start, end)


def _norm(text: str) -> str:
    return " ".join(text.split()).casefold()


def _key(words: list[Word]) -> str:
    return _norm(" ".join(w.text for w in words)).strip(_PUNCT)


# --------------------------------------------------------------------------- output side


def _page_anchors(ctx: ConvertContext) -> dict[int, str]:
    """Physical page -> ``href#qc-page-N`` of every page anchor in the book."""
    anchors = {}
    for doc in ctx.book.docs:
        for el in doc.body.iter(etree.Element):
            ident = el.get("id") or ""
            suffix = ident.removeprefix(PAGE_ANCHOR_PREFIX)
            if suffix != ident and suffix.isdigit():
                anchors[int(suffix)] = f"{doc.href}#{ident}"
    return anchors


class _Linker:
    def __init__(self, ctx: ConvertContext, anchors: dict[int, str]):
        self.ctx = ctx
        self.anchors = anchors
        self.links = 0
        al = ctx.alignment
        words = {id(w): (p.index, k) for p in ctx.source.pages for k, w in enumerate(_words(p))}
        by_pos: dict[tuple[int, int], list[int]] = {}
        for i, tok in enumerate(al.source):
            by_pos.setdefault((tok.page, tok.page_word), []).append(i)
        self._by_pos, self._words = by_pos, words
        self._ids = {ident for doc in ctx.book.docs for ident in doc.tree.xpath("//@id")}
        self._dest_ids: dict[int, str] = {}

    # ---- page references

    def link_ref(self, entry: _Entry, ref: _Ref) -> None:
        labels = [ref.start] + ([ref.end] if ref.end else [])
        targets = [self._target(entry, label) for label in labels]
        if None in targets:
            return
        toks = self._word_tokens(ref.word)
        spans = [self._span(t) for t in toks]
        if len(toks) != len(labels) or None in spans:
            self._issue("index.unresolved_reference", entry, ref.start, "not found in output")
            return
        for (block, start, end), target, label in zip(spans, targets, labels, strict=True):
            if self._wrap(block, start, end, target):
                self.ctx.record(
                    FAMILY,
                    "link_reference",
                    f"{self.ctx.alignment.hrefs[block]}#{block}",
                    [entry.block, f"page={entry.page}", target],
                    before=label,
                    after=target,
                )
            else:
                self._issue("index.unresolved_reference", entry, label, "markup splits it")

    def _target(self, entry: _Entry, label: str) -> str | None:
        found = pages.pages_for_label(self.ctx, label)
        if len(found) > 1:
            self._issue(
                "index.ambiguous_reference",
                entry,
                label,
                f"label printed on {len(found)} pages",
                f"pages={','.join(map(str, found))}",
            )
            return None
        if not found or found[0] not in self.anchors:
            why = "no verified page has this label" if not found else "its page has no anchor"
            self._issue("index.unresolved_reference", entry, label, why)
            return None
        return self.anchors[found[0]]

    def _issue(self, code: str, entry: _Entry, label: str, why: str, extra: str = "") -> None:
        self.ctx.issue(
            code,
            Severity.MAJOR,
            f"index reference left unlinked: {why}",
            page=entry.page,
            page_label=pages.printed_label(self.ctx, entry.page),
            evidence=f"{entry.block} page={entry.page} {extra}".strip(),
            action="left_unlinked",
            uncertainty=f"reference to printed page {label!r} stays visible as plain text",
        )

    # ---- cross references

    def link_cross_references(self, entries: list[_Entry]) -> None:
        by_key: dict[str, list[_Entry]] = {}
        for entry in entries:
            if entry.term:
                by_key.setdefault(entry.key, []).append(entry)
        for entry in entries:
            for target in entry.see:
                dests = by_key.get(_key(target), [])
                if len(dests) != 1:
                    self.ctx.issue(
                        "index.unresolved_cross_reference",
                        Severity.MINOR,
                        f"cross-reference left unlinked: {len(dests)} entries match its term",
                        page=entry.page,
                        evidence=f"{entry.block} page={entry.page} matches={len(dests)}",
                        action="left_unlinked",
                    )
                    continue
                self._link_see(entry, target, dests[0])

    def _link_see(self, entry: _Entry, target: list[Word], dest: _Entry) -> None:
        first = self._word_tokens(target[0])
        last = [t for w in target for t in self._word_tokens(w)]
        dest_tokens = self._word_tokens(dest.term[0])
        if not first or not dest_tokens:
            return
        start, end = self._span(first[0]), self._span(last[-1])
        dest_pos = self._span(dest_tokens[0])
        if start is None or end is None or dest_pos is None or start[0] != end[0]:
            return
        href = self._dest_anchor(dest, dest_pos)
        if self._wrap(start[0], start[1], end[2], href):
            self.ctx.record(
                FAMILY,
                "link_cross_reference",
                f"{self.ctx.alignment.hrefs[start[0]]}#{start[0]}",
                [entry.block, dest.block, href],
                after=href,
            )

    def _dest_anchor(self, dest: _Entry, pos: tuple[str, int, int]) -> str:
        block, offset, _ = pos
        href = self.ctx.alignment.hrefs[block]
        if offset == 0:
            return f"{href}#{block}"
        if id(dest) not in self._dest_ids:
            k = len(self._dest_ids)
            while (ident := f"qc-ix-{k}") in self._ids:
                k += 1
            self._ids.add(ident)
            _doc, el = self.ctx.book.element(f"{href}#{block}")
            inline.insert_at(el, offset, el.makeelement(f"{{{XHTML_NS}}}span", {"id": ident}))
            self._dest_ids[id(dest)] = ident
        return f"{href}#{self._dest_ids[id(dest)]}"

    # ---- alignment and markup

    def _word_tokens(self, word: Word) -> list[int]:
        pos = self._words.get(id(word))
        return self._by_pos.get(pos, []) if pos is not None else []

    def _span(self, i: int) -> tuple[str, int, int] | None:
        """Output (block, start, end) of source token ``i`` when it matched the same word."""
        al = self.ctx.alignment
        j = al.src_to_out[i]
        if j < 0 or al.out_to_src[j] != i or al.output[j].norm != al.source[i].norm:
            return None
        tok = al.output[j]
        return tok.block, tok.start, tok.end

    def _wrap(self, block: str, start: int, end: int, target: str) -> bool:
        """Wrap ``[start, end)`` of the block's text in a link, when it lies in one text run."""
        href = self.ctx.alignment.hrefs[block]
        _doc, el = self.ctx.book.element(f"{href}#{block}")
        a = el.makeelement(f"{{{XHTML_NS}}}a", {"href": relative_href(href, target)})
        if _wrap_text(el, start, end, a):
            self.links += 1
            return True
        return False


def _words(page) -> list[Word]:
    """A page's words in inventory order, the order ``SourceToken.page_word`` counts."""
    return [w for b in page.blocks for line in b.lines for w in line.words]


def _wrap_text(el: etree._Element, start: int, end: int, a: etree._Element) -> bool:
    want = inline.text_of(el)[start:end]
    node, attr, i = inline.locate(el, start)
    value = getattr(node, attr) or ""
    while i >= len(value):  # the point ends a text run: step into the inline element after it
        nxt = (node[0] if len(node) else None) if attr == "text" else node.getnext()
        if nxt is None or not isinstance(nxt.tag, str) or local_name(nxt) in ("br", "img"):
            return False
        node, attr, i, value = nxt, "text", 0, nxt.text or ""
    k = i
    for ch in want:
        if ch == " ":
            if k >= len(value) or not value[k].isspace():
                return False
            while k < len(value) and value[k].isspace():
                k += 1
        elif k < len(value) and value[k] == ch:
            k += 1
        else:
            return False
    holder = node if attr == "text" else node.getparent()
    if any(local_name(x) == "a" for x in [holder, *holder.iterancestors()]):
        return False
    a.text, a.tail = value[i:k], value[k:] or None
    if attr == "text":
        node.text = value[:i] or None
        node.insert(0, a)
    else:
        node.tail = value[:i] or None
        holder.insert(holder.index(node) + 1, a)
    return True
