"""Account for every meaningful source region against the final EPUB.

Each text block is reflowed text (aligned), a figure's own text (inside an image region),
or verified page furniture (running headers, footers and folios repeated in the margins);
each image region is shown by an output figure, image or facsimile. Anything else becomes
an issue, and so does output text with no source, text that appears twice and text out of
source order. Coverage is counted separately for prose, notes and illustrations, since a
book-wide word count hides a missing page, a duplicated paragraph or swapped columns.

Repairs that insert a figure or facsimile mark the element that shows a region with
``data-qc-source="s2-i0"`` (space-separated ids for several regions); elements they add
without a source (labels, headings) carry ``data-qc-generated``.
"""

from __future__ import annotations

import re
import statistics
from collections import Counter
from typing import TYPE_CHECKING, Any

from ..models import Severity, SourceInventory, TextBlock
from ..source import layout
from .align import DUPLICATE, MOVED, Alignment, refresh
from .normalize import tokenize

if TYPE_CHECKING:
    from ..context import ConvertContext

FURNITURE_ROLES = frozenset({"folio", "running_header", "running_footer"})
_NOTE_MARKER = re.compile(r"^(\(?\d{1,3}[.):]?|\[\d{1,3}\]|[*†‡§¶]+|[⁰¹²³⁴⁵⁶⁷⁸⁹]+)$")
NOTE_ZONE = 0.5  # notes start in the lower half of the page
NOTE_SIZE_RATIO = 0.92  # and are set smaller than the body text
FIGURE_TEXT_SHARE = 0.8  # a text block this much inside an image region is its label
TEXT_PANEL_WORDS = 25  # more words than this inside a region make it a text panel
PARTIAL_RUN = 3  # unmatched words that make a partial loss or addition worth flagging
LOST_SHARE = 0.25  # a block with less of its words aligned counts as lost entirely


def _inside_regions(source: SourceInventory) -> dict[str, list[TextBlock]]:
    """Text blocks lying inside each image region, by region id."""
    found: dict[str, list[TextBlock]] = {}
    for page in source.pages:
        for block in page.blocks:
            area = block.box.area
            for region in page.images:
                if area and region.box.intersection_area(block.box) >= FIGURE_TEXT_SHARE * area:
                    found.setdefault(region.id, []).append(block)
                    break
    return found


def _is_panel(blocks: list[TextBlock]) -> bool:
    words = sum(len(line.words) for b in blocks for line in b.lines)
    prose = sum(1 for b in blocks for line in b.lines if len(line.words) >= 5)
    return words > TEXT_PANEL_WORDS or prose >= 2


def text_panels(source: SourceInventory) -> set[str]:
    """Ids of image regions that are tinted boxes or panels of body text, not illustrations.
    Their reflowed text shows them, so their words are accounted as prose."""
    return {rid for rid, blocks in _inside_regions(source).items() if _is_panel(blocks)}


def figure_text(source: SourceInventory) -> dict[str, str]:
    """Text blocks that belong to an illustration (labels drawn in it): block id -> region id.
    Text inside a text panel is prose, so it is not listed."""
    return {
        block.id: rid
        for rid, blocks in _inside_regions(source).items()
        if not _is_panel(blocks)
        for block in blocks
    }


def running_furniture(source: SourceInventory) -> set[str]:
    """Ids of text blocks that are page furniture: folios, and margin text repeated on at
    least two pages once digits are masked (running headers and footers)."""
    furniture: set[str] = set()
    seen: dict[tuple[bool, str], set[int]] = {}
    members: dict[tuple[bool, str], list[str]] = {}
    for page in source.pages:
        for block in page.blocks:
            if block.role in FURNITURE_ROLES:
                furniture.add(block.id)
                continue
            if not layout.in_margin(block.box):
                continue
            words = [t.norm for t in tokenize(block.text)]
            if words and all(layout.folio_text(w) for w in words):
                furniture.add(block.id)
                continue
            key = (block.box.y0 < 0.5, re.sub(r"\d+", "#", " ".join(words)))
            seen.setdefault(key, set()).add(page.index)
            members.setdefault(key, []).append(block.id)
    for key, pages in seen.items():
        if len(pages) >= 2 and key[1]:
            furniture.update(members[key])
    return furniture


def note_blocks(source: SourceInventory, furniture: set[str] | None = None) -> set[str]:
    """Ids of note text blocks: role ``note``, or in the lower half of the page, set
    smaller than the body text and opening with a note marker (1, 1., [1], *, †, ¹)."""
    furniture = running_furniture(source) if furniture is None else furniture
    sizes = Counter(
        w.font_size
        for page in source.pages
        for b in page.blocks
        if b.id not in furniture
        for line in b.lines
        for w in line.words
        if w.font_size
    )
    body = sizes.most_common(1)[0][0] if sizes else None
    notes = set()
    for page in source.pages:
        for block in page.blocks:
            if block.role == "note":
                notes.add(block.id)
            elif block.id not in furniture and _looks_like_note(block, body):
                notes.add(block.id)
    return notes


def _looks_like_note(block: TextBlock, body_size: float | None) -> bool:
    if block.box.y0 < NOTE_ZONE or not block.lines or not block.lines[0].words:
        return False
    if not _NOTE_MARKER.match(block.lines[0].words[0].text):
        return False
    sizes = [w.font_size for line in block.lines for w in line.words if w.font_size]
    if not sizes or body_size is None:
        return False
    return statistics.median(sizes) <= body_size * NOTE_SIZE_RATIO


def account(ctx: ConvertContext) -> dict[str, Any]:
    """Realign against the final book, record coverage issues and return the summary.

    The summary is also stored on ``ctx.alignment.coverage``.
    """
    refresh(ctx)
    return _Accounting(ctx).run()


class _Accounting:
    def __init__(self, ctx: ConvertContext):
        self.ctx = ctx
        self.al: Alignment = ctx.alignment
        self.source = ctx.source
        self.furniture = running_furniture(self.source)
        self.notes = note_blocks(self.source, self.furniture)
        self.regions = {r.id: r for p in self.source.pages for r in p.images}
        self.blocks = {b.id: b for p in self.source.pages for b in p.blocks}
        self.figure_text = figure_text(self.source)
        self.panels = text_panels(self.source)
        self.counts: Counter[str] = Counter()

    def run(self) -> dict[str, Any]:
        page_prose: dict[int, list[int]] = {}
        for page in self.source.pages:
            for block in page.blocks:
                self._text_block(block, page_prose)
        for region in self.regions.values():
            self._region(region)
        self._output()
        summary = self._summary(page_prose)
        self.al.coverage = summary
        return summary

    # ---- source side

    def _text_block(self, block: TextBlock, page_prose: dict[int, list[int]]) -> None:
        al, c = self.al, self.counts
        tokens = al.source_block_tokens(block.id)
        if not tokens:
            return
        matched = [i for i in tokens if al.src_to_out[i] >= 0]
        if block.id in self.furniture:
            c["furniture_blocks"] += 1
            c["furniture_kept"] += bool(matched)
            return
        if block.id in self.figure_text:
            region = self.figure_text[block.id]
            c["figure_text_blocks"] += 1
            c["figure_text_accounted"] += bool(matched) or region in al.images
            return
        kind = "notes" if block.id in self.notes else "prose"
        c[f"{kind}_blocks"] += 1
        c[f"{kind}_tokens"] += len(tokens)
        c[f"{kind}_aligned"] += len(matched)
        if kind == "prose":
            counts = page_prose.setdefault(block.page, [0, 0])
            counts[0] += len(tokens)
            counts[1] += len(matched)
        if len(matched) < LOST_SHARE * len(tokens):
            self._missing(block, tokens, whole=True, note=kind == "notes")
        elif _longest_gap(tokens, al.src_to_out) >= PARTIAL_RUN:
            self._missing(block, tokens, whole=False, note=kind == "notes")
        moved = [i for i in matched if al.out_kind[al.src_to_out[i]] == MOVED]
        if kind == "prose" and len(moved) >= max(PARTIAL_RUN, len(matched) // 2):
            self._moved(block, moved)

    def _missing(self, block: TextBlock, tokens: list[int], whole: bool, note: bool) -> None:
        al = self.al
        aligned = sum(1 for i in tokens if al.src_to_out[i] >= 0)
        trivial = whole and len(tokens) == 1 and len(al.source[tokens[0]].norm) < 3
        if whole and note:
            code, severity = "coverage.lost_note", Severity.CRITICAL
            message = "A note in the source has no counterpart in the EPUB"
        elif whole:
            code = "coverage.missing_text"
            severity = Severity.MINOR if trivial else Severity.CRITICAL
            message = "A source text block has no counterpart in the EPUB"
        else:
            code, severity = "coverage.missing_text", Severity.MAJOR
            message = "Part of a source text block is missing from the EPUB"
        self._issue(
            code,
            severity,
            message,
            block.page,
            block.box,
            al.anchor_near_source(tokens[0]),
            f"{block.id} tokens={len(tokens)} aligned={aligned} page={block.page}",
            "The text may survive in a form too altered to align, or under another block.",
            material=not trivial,
        )

    def _moved(self, block: TextBlock, moved: list[int]) -> None:
        out = self.al.output[self.al.src_to_out[moved[0]]]
        self._issue(
            "coverage.order_mismatch",
            Severity.MAJOR,
            "Source text appears out of reading order in the EPUB",
            block.page,
            block.box,
            self._anchor(out.block),
            f"{block.id} {out.block} moved={len(moved)} page={block.page}",
            "The source reading order is inferred from layout; columns or floats may differ.",
            material=True,
        )

    def _region(self, region) -> None:
        c = self.counts
        if region.id in self.panels:
            c["text_panels"] += 1
            return
        if region.kind == "unexplained_ink":
            c["ink_regions"] += 1
            if region.id in self.al.images:
                c["ink_accounted"] += 1
                return
            self._issue(
                "coverage.unaccounted_ink",
                Severity.MAJOR,
                "Ink on the source page is not explained by text or a figure in the EPUB",
                region.page,
                region.box,
                self._page_anchor(region.page),
                f"{region.id} area={region.box.area:.4f} page={region.page}",
                "The ink may be decoration, marginalia or a figure the inventory missed.",
                material=False,
            )
            return
        c["illustrations"] += 1
        if region.id in self.al.images:
            c["illustrations_accounted"] += 1
            return
        self._issue(
            "coverage.lost_figure",
            Severity.CRITICAL,
            "A source illustration has no figure or image in the EPUB",
            region.page,
            region.box,
            self._page_anchor(region.page),
            f"{region.id} area={region.box.area:.4f} page={region.page}",
            "Labels inside the illustration may survive as loose text.",
            material=True,
        )

    # ---- output side

    def _output(self) -> None:
        al, c = self.al, self.counts
        duplicates: dict[str, Counter[str]] = {}  # source block -> output block -> words
        for block in self.ctx.blocks:
            if block.alignment == "generated":
                c["generated_blocks"] += 1
                continue
            js = al.output_block_tokens(block.id)
            if not js:
                continue
            c["output_tokens"] += len(js)
            unmatched = [j for j in js if al.out_to_src[j] < 0]
            c["output_unaligned"] += len(unmatched)
            c["output_moved"] += sum(1 for j in js if al.out_kind[j] == MOVED)
            if len(unmatched) == len(js) or _longest_gap(js, al.out_to_src) >= PARTIAL_RUN:
                self._invented(block, js, unmatched)
            for j in js:
                if al.out_kind[j] == DUPLICATE:
                    c["output_duplicated"] += 1
                    src_block = al.source[al.out_to_src[j]].block
                    duplicates.setdefault(src_block, Counter())[block.id] += 1
        for src_block, per_output in duplicates.items():
            self._duplicated(src_block, per_output)

    def _invented(self, block, js: list[int], unmatched: list[int]) -> None:
        whole = len(unmatched) == len(js)
        trivial = whole and len(js) == 1 and len(self.al.output[js[0]].norm) < 3
        if whole:
            severity = Severity.MINOR if trivial else Severity.CRITICAL
            message = "EPUB text has no supporting source text"
        else:
            severity, message = Severity.MAJOR, "Part of an EPUB block has no supporting source"
        page = self.al.page_near(unmatched[0])
        self._issue(
            "coverage.invented_text",
            severity,
            message,
            page,
            None,
            self._anchor(block.id),
            f"{block.id} tokens={len(js)} unaligned={len(unmatched)}"
            + (f" page={page}" if page is not None else ""),
            "The source may hold the text in a form too altered to align.",
            material=not trivial,
        )

    def _duplicated(self, src_block: str, per_output: Counter[str]) -> None:
        total = sum(per_output.values())
        whole = any(
            n == len(self.al.output_block_tokens(block_id)) for block_id, n in per_output.items()
        )
        if total < PARTIAL_RUN and not whole:
            return
        block = self.blocks[src_block]
        short = total < PARTIAL_RUN
        outputs = ",".join(sorted(per_output, key=lambda b: (-per_output[b], b)))
        first = next(
            (
                self.al.output[j].block
                for i in self.al.source_block_tokens(src_block)
                if (j := self.al.src_to_out[i]) >= 0
            ),
            None,
        )
        self._issue(
            "coverage.duplicated_text",
            Severity.MINOR if short else Severity.CRITICAL,
            "Source text appears more than once in the EPUB",
            block.page,
            block.box,
            self._anchor(per_output.most_common(1)[0][0]),
            f"{src_block}"
            + (f" first={first}" if first else "")
            + f" copies={outputs} duplicated={total} page={block.page}",
            "The first copy is taken as the original; the later copies are marked ambiguous.",
            material=not short,
        )

    # ---- helpers

    def _issue(
        self, code, severity, message, page, region, anchor, evidence, uncertainty, material
    ):
        label = None
        if page is not None:
            inv = self.source.pages[page]
            label = inv.label or (inv.folio_candidates[0] if inv.folio_candidates else None)
        self.ctx.issue(
            code,
            severity,
            message,
            page=page,
            page_label=label,
            region=region,
            output_anchor=anchor,
            evidence=evidence,
            action="flagged",
            uncertainty=uncertainty,
            material=material,
        )

    def _anchor(self, block_id: str) -> str:
        return f"{self.al.hrefs[block_id]}#{block_id}"

    def _page_anchor(self, page: int) -> str | None:
        for p in [page, *range(page - 1, -1, -1), *range(page + 1, len(self.source.pages))]:
            if (pos := self.al.first_output_position_on_page(p)) is not None:
                return self._anchor(pos[0])
        return None

    def _summary(self, page_prose: dict[int, list[int]]) -> dict[str, Any]:
        c = self.counts
        statuses = Counter(
            b.alignment for b in self.ctx.blocks if self.al.output_block_tokens(b.id) or b.spans
        )
        return {
            "prose": _ratio(c["prose_blocks"], c["prose_tokens"], c["prose_aligned"]),
            "notes": _ratio(c["notes_blocks"], c["notes_tokens"], c["notes_aligned"]),
            "illustrations": {
                "regions": c["illustrations"],
                "accounted": c["illustrations_accounted"],
                "ratio": _share(c["illustrations_accounted"], c["illustrations"]),
                "figure_text_blocks": c["figure_text_blocks"],
                "text_panels": c["text_panels"],
            },
            "unexplained_ink": {"regions": c["ink_regions"], "accounted": c["ink_accounted"]},
            "furniture": {"blocks": c["furniture_blocks"], "kept": c["furniture_kept"]},
            "output": {
                "tokens": c["output_tokens"],
                "unaligned": c["output_unaligned"],
                "duplicated": c["output_duplicated"],
                "moved": c["output_moved"],
                "generated_blocks": c["generated_blocks"],
            },
            "blocks": dict(sorted(statuses.items())),
            "pages": {
                str(page): _share(aligned, total)
                for page, (total, aligned) in sorted(page_prose.items())
            },
        }


def _longest_gap(indices: list[int], partner: list[int]) -> int:
    best = run = 0
    for k in indices:
        run = run + 1 if partner[k] < 0 else 0
        best = max(best, run)
    return best


def _share(part: int, whole: int) -> float | None:
    return round(part / whole, 4) if whole else None


def _ratio(blocks: int, tokens: int, aligned: int) -> dict[str, Any]:
    return {
        "blocks": blocks,
        "tokens": tokens,
        "aligned": aligned,
        "ratio": _share(aligned, tokens),
    }
