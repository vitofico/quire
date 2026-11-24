"""Printed page labels, page-break anchors and the EPUB page list.

Physical pages (0-based) and printed labels stay separate. A page's printed label is
verified when two sources agree: the PDF /PageLabels value and a visible folio (or a
``page.N`` named destination), or a folio that fits the arithmetic run of its
neighbours. A page without a folio of its own (a chapter opening, a counted plate) takes
its label from the run around it only when the run is confirmed on both sides (on one
side when /PageLabels supplies the value). An uncounted plate breaks the run's arithmetic,
so it stays unlabelled. A label printed on two physical pages is ambiguous and stays out
of the page list.

Every page with aligned output gets an empty anchor ``qc-page-{physical}`` at its first
output position, inside a paragraph that crosses the page boundary when needed. Only a
verified label makes it a semantic page break; other pages get a plain id anchor so
reports can still point at them without claiming a printed number.
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field
from typing import TYPE_CHECKING

from ..epub import inline
from ..epub.package import EPUB_NS, XHTML_NS, local_name
from ..models import PAGE_ANCHOR_PREFIX, Severity, SourceInventory

if TYPE_CHECKING:
    from ..context import ConvertContext

log = logging.getLogger(__name__)

FAMILY = "pages"
NEIGHBOURS = 3  # pages either side searched for a folio that confirms the run
_NUMERAL = re.compile(r"^(?P<prefix>.*?)(?P<num>\d+|[ivxlcdm]+)$", re.IGNORECASE)
_DEST = re.compile(r"^page[._\-]?(?P<label>\d+|[ivxlcdm]+)$", re.IGNORECASE)
_ROMAN = [(1000, "m"), (900, "cm"), (500, "d"), (400, "cd"), (100, "c"), (90, "xc")]
_ROMAN += [(50, "l"), (40, "xl"), (10, "x"), (9, "ix"), (5, "v"), (4, "iv"), (1, "i")]


# --------------------------------------------------------------------------- labels


@dataclass(frozen=True)
class _Value:
    prefix: str
    roman: bool
    upper: bool
    n: int

    def fits(self, other: _Value, distance: int) -> bool:
        """Whether ``other`` is this value ``distance`` pages later in the same run."""
        same = (self.prefix, self.roman) == (other.prefix, other.roman)
        return same and other.n - self.n == distance

    def shifted(self, distance: int) -> str:
        n = self.n + distance
        text = _to_roman(n) if self.roman else str(n)
        return self.prefix + (text.upper() if self.upper else text)


def _to_roman(n: int) -> str:
    out = []
    for value, letters in _ROMAN:
        while n >= value:
            out.append(letters)
            n -= value
    return "".join(out)


def _from_roman(text: str) -> int | None:
    total, rest = 0, text.lower()
    for value, letters in _ROMAN:
        while rest.startswith(letters):
            total += value
            rest = rest[len(letters) :]
    return total if not rest and total and _to_roman(total) == text.lower() else None


def _parse(label: str) -> _Value | None:
    m = _NUMERAL.match(label.strip())
    if not m:
        return None
    num = m["num"]
    if num.isdigit():
        return _Value(m["prefix"], False, False, int(num))
    n = _from_roman(num)
    if n is None or not (num.islower() or num.isupper()):
        return None
    return _Value(m["prefix"], True, num.isupper(), n)


@dataclass
class PageLabels:
    """Printed labels of a source inventory, by physical page."""

    verified: dict[int, str] = field(default_factory=dict)
    conflicts: dict[int, tuple[str, str]] = field(default_factory=dict)  # page -> (pdf, folio)
    unconfirmed: list[int] = field(default_factory=list)  # /PageLabels with no evidence
    by_label: dict[str, list[int]] = field(default_factory=dict)  # casefolded label -> pages

    def ambiguous(self, label: str) -> bool:
        return len(self.by_label.get(label.casefold(), [])) > 1


def analyse(source: SourceInventory) -> PageLabels:
    """Verify each page's printed label from /PageLabels, folios and named destinations."""
    n = len(source.pages)
    seen = [list(p.folio_candidates) for p in source.pages]
    for name, (page, _y) in source.named_dests.items():
        if (m := _DEST.match(name)) and 0 <= page < n and m["label"] not in seen[page]:
            seen[page].append(m["label"])
    values = [[(v, val) for v in vs if (val := _parse(v))] for vs in seen]
    folio = [_run_folio(values, p) for p in range(n)]
    pdf = [p.label for p in source.pages]
    result = PageLabels()
    pending = []
    for p in range(n):
        if pdf[p] is None:
            if folio[p] is not None:
                result.verified[p] = folio[p]
            else:
                pending.append(p)
            continue
        if folio[p] is not None:
            if folio[p].casefold() == pdf[p].casefold():
                result.verified[p] = folio[p]
            else:
                result.conflicts[p] = (pdf[p], folio[p])
            continue
        match = next((v for v in seen[p] if v.casefold() == pdf[p].casefold()), None)
        if match is not None:
            result.verified[p] = match
        else:
            pending.append(p)
    for p in pending:
        label = _from_pdf_run(pdf, result, p) if pdf[p] is not None else _bracketed(result, p)
        if label is not None:
            result.verified[p] = label
        elif pdf[p] is not None:
            result.unconfirmed.append(p)
    result.verified = dict(sorted(result.verified.items()))
    for p, label in result.verified.items():
        result.by_label.setdefault(label.casefold(), []).append(p)
    return result


def _run_folio(values: list[list[tuple[str, _Value]]], p: int) -> str | None:
    """The one folio candidate of page ``p`` that a nearby page's folio continues."""
    hits = []
    for text, value in values[p]:
        for q in range(max(0, p - NEIGHBOURS), min(len(values), p + NEIGHBOURS + 1)):
            if q != p and any(value.fits(other, q - p) for _, other in values[q]):
                hits.append(text)
                break
    return hits[0] if len(hits) == 1 else None


def _from_pdf_run(pdf: list[str | None], result: PageLabels, p: int) -> str | None:
    """Accept /PageLabels for a page without evidence when the nearest pages with
    evidence in the same label run, on each side, confirm it."""
    start = _parse(pdf[p])
    if start is None:
        return None
    sides = []
    for step in (-1, 1):
        q = p + step
        while 0 <= q < len(pdf):
            value = _parse(pdf[q]) if pdf[q] is not None else None
            if value is None or not start.fits(value, q - p):
                break
            if q in result.conflicts:
                return None
            if q in result.verified:
                sides.append(q)
                break
            q += step
    return pdf[p] if sides else None


def _bracketed(result: PageLabels, p: int) -> str | None:
    """Infer the label of an unnumbered page counted between two verified pages."""
    before = [q for q in result.verified if q < p]
    after = [q for q in result.verified if q > p]
    if not before or not after:
        return None
    a, b = max(before), min(after)
    va, vb = _parse(result.verified[a]), _parse(result.verified[b])
    if va is None or vb is None or not va.fits(vb, b - a):
        return None
    return va.shifted(p - a)


_cache: tuple[SourceInventory, PageLabels] | None = None


def labels(ctx: ConvertContext) -> PageLabels:
    """The label analysis of ``ctx.source``, computed once per inventory."""
    global _cache
    if _cache is None or _cache[0] is not ctx.source:
        _cache = (ctx.source, analyse(ctx.source))
    return _cache[1]


def printed_label(ctx: ConvertContext, page: int) -> str | None:
    """The verified printed label of physical ``page``, or None."""
    return labels(ctx).verified.get(page)


def pages_for_label(ctx: ConvertContext, label: str) -> list[int]:
    """Physical pages whose verified printed label is ``label`` (more than one: ambiguous)."""
    return list(labels(ctx).by_label.get(label.strip().casefold(), []))


def anchor_id(page: int) -> str:
    return f"{PAGE_ANCHOR_PREFIX}{page}"


# --------------------------------------------------------------------------- anchors


def apply(ctx: ConvertContext) -> None:
    if ctx.alignment is None:
        from ..align.align import align_book

        align_book(ctx)
    found = labels(ctx)
    _report_labels(ctx, found)
    existing = {
        ident
        for doc in ctx.book.docs
        for el in doc.tree.iter()
        if (ident := el.get("id") if isinstance(el.tag, str) else None)
    }
    page_list = []
    for page, (block_id, offset) in _positions(ctx).items():
        if anchor_id(page) in existing:
            continue
        label = found.verified.get(page)
        href = ctx.alignment.hrefs[block_id]
        _insert_anchor(ctx, f"{href}#{block_id}", offset, page, label)
        target = f"{href}#{anchor_id(page)}"
        ctx.record(FAMILY, "insert_page_anchor", target, [f"page={page}", block_id], after=label)
        if label is not None and not found.ambiguous(label):
            page_list.append((label, target))
    if page_list:
        ctx.book.page_list = page_list
        ctx.record(FAMILY, "set_page_list", "page-list", [f"entries={len(page_list)}"])
    log.info("page anchors: %d pages, %d in the page list", len(found.verified), len(page_list))


def _report_labels(ctx: ConvertContext, found: PageLabels) -> None:
    for p, (pdf, folio) in found.conflicts.items():
        ctx.issue(
            "pages.unverified_label",
            Severity.MINOR,
            "printed folio disagrees with the PDF page label; page left out of the page list",
            page=p,
            page_label=folio,
            evidence=f"page={p}",
            action="flagged",
            uncertainty=f"PDF label {pdf!r}, printed folio {folio!r}",
        )
    for run in _runs(found.unconfirmed):
        ctx.issue(
            "pages.unverified_label",
            Severity.INFO,
            f"no printed folio confirms the PDF page labels of {len(run)} pages",
            page=run[0],
            page_label=ctx.source.pages[run[0]].label,
            evidence=f"pages={run[0]}-{run[-1]} count={len(run)}",
            action="left_out_of_page_list",
        )
    for pages in found.by_label.values():
        if len(pages) > 1:
            ctx.issue(
                "pages.ambiguous_label",
                Severity.MINOR,
                f"one printed label appears on {len(pages)} physical pages",
                page=pages[0],
                page_label=found.verified[pages[0]],
                evidence="pages=" + ",".join(str(p) for p in pages),
                action="left_out_of_page_list",
                uncertainty="index references to this label cannot be resolved",
            )


def _runs(pages: list[int]) -> list[list[int]]:
    runs: list[list[int]] = []
    for p in pages:
        if runs and runs[-1][-1] == p - 1:
            runs[-1].append(p)
        else:
            runs.append([p])
    return runs


def _positions(ctx: ConvertContext) -> dict[int, tuple[str, int]]:
    """Each page's anchor position (output block id, char offset), in physical order.

    A page starts at its earliest output word that is not before the previous page's
    anchor, so the page list follows reading order. A word joined across the break (a
    hyphenated word merged into one output word) belongs to the page it starts on, so
    the next page's anchor goes after it. A page with no aligned words falls back to the
    first output image showing one of its regions.
    """
    al = ctx.alignment
    order = {b.id: k for k, b in enumerate(ctx.blocks)}
    first_page: dict[int, int] = {}
    for i, tok in enumerate(al.source):
        if (j := al.src_to_out[i]) >= 0:
            first_page.setdefault(j, tok.page)
    images: dict[int, list[str]] = {}
    for region, block in al.images.items():
        if (page := al.image_pages.get(region)) is not None:
            images.setdefault(page, []).append(block)
    positions: dict[int, tuple[str, int]] = {}
    previous = (-1, -1)
    for page in ctx.source.pages:
        p = page.index
        candidates = []
        for i in al.source_tokens(p):
            if (j := al.src_to_out[i]) < 0:
                continue
            tok = al.output[j]
            offset = tok.end if first_page[j] < p else tok.start
            candidates.append((order.get(tok.block, -1), offset, tok.block))
        candidates += [(order.get(b, -1), 0, b) for b in images.get(p, [])]
        candidates = sorted(c for c in candidates if c[0] >= 0 and c[:2] >= previous)
        if not candidates:
            _no_anchor(ctx, page)
            continue
        block_index, offset, block_id = candidates[0]
        positions[p] = (block_id, offset)
        previous = (block_index, offset)
    return positions


def _no_anchor(ctx: ConvertContext, page) -> None:
    blank = page.page_type == "blank" or not any(b.lines for b in page.blocks)
    reason = "page has no text or image" if blank else "no output text or image aligns to it"
    ctx.issue(
        "pages.no_anchor",
        Severity.INFO if blank and not page.images else Severity.MINOR,
        f"no page-break anchor: {reason}",
        page=page.index,
        page_label=labels(ctx).verified.get(page.index),
        evidence=f"page={page.index}",
        action="skipped",
    )


def _insert_anchor(
    ctx: ConvertContext, anchor: str, offset: int, page: int, label: str | None
) -> None:
    _doc, el = ctx.book.element(anchor)
    attrs = {"id": anchor_id(page)}
    if label is not None:
        attrs |= {f"{{{EPUB_NS}}}type": "pagebreak", "role": "doc-pagebreak", "aria-label": label}
    span = el.makeelement(f"{{{XHTML_NS}}}span", attrs, nsmap={"epub": EPUB_NS})
    if local_name(el) == "img":
        el.addprevious(span)
    else:
        inline.insert_at(el, offset, span)
