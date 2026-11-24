"""Alignment and coverage on synthetic books: an inventory built in memory and a matching
EPUB, so every expectation is exact and no external tool is needed."""

import time
from html import escape

from synthetic import block, context, epub, inventory, novel

from quire_convert.align.align import align_book, reading_order
from quire_convert.align.coverage import account, note_blocks, running_furniture
from quire_convert.epub.book import Book
from quire_convert.models import Box, ImageRegion, Severity
from quire_convert.report import is_safe_evidence


def build(tmp_path, pages, bodies: list[str]):
    book = Book.load(epub(tmp_path / "b.epub", bodies))
    return context(inventory(pages), book, tmp_path)


def paras(texts: list[str]) -> str:
    return "\n".join(f"<p>{escape(t)}</p>" for t in texts)


def codes(ctx) -> list[tuple[str, int | None]]:
    """(code, page) of every issue; every issue must also survive report redaction."""
    assert all(is_safe_evidence(i.evidence) for i in ctx.issues)
    return sorted((i.code, i.page) for i in ctx.issues)


def test_clean_book_aligns_and_accounts_everything(tmp_path):
    pages, texts = novel(1500)
    half = len(texts) // 2
    ctx = build(tmp_path, pages, [paras(texts[:half]), paras(texts[half:])])
    summary = account(ctx)
    assert ctx.issues == []
    assert summary["prose"]["ratio"] == 1.0 and summary["prose"]["blocks"] == len(texts)
    assert summary["notes"]["blocks"] == 0 and summary["notes"]["ratio"] is None
    assert summary["furniture"] == {"blocks": 2 * len(pages), "kept": 0}
    assert summary["output"]["unaligned"] == 0 and summary["output"]["duplicated"] == 0
    assert summary["blocks"] == {"exact": len(texts)}
    assert set(summary["pages"].values()) == {1.0}
    assert ctx.alignment.coverage is summary
    assert all(b.alignment == "exact" and b.spans for b in ctx.blocks)


def test_lookups_map_both_ways(tmp_path):
    pages, texts = novel(700)
    ctx = build(tmp_path, pages, [paras(texts)])
    align_book(ctx)
    al = ctx.alignment
    ids = [b.id for b in ctx.blocks]
    # page 1 starts with its header (s1-b0), then its first paragraph s1-b1 = output block 11
    assert al.first_output_position_on_page(1) == (ids[11], 0)
    assert al.source_to_output(1, "s1-b1") == (ids[11], 0)
    assert al.source_to_output(1, 2) == (ids[11], 0)  # words 0 and 1 are "Running Title"
    assert al.source_to_output(1, 1) is None
    second_word = ctx.blocks[11].text.index(" ") + 1
    assert al.output_to_source(ids[11], second_word) == (1, "s1-b1", 0, 1)
    assert al.output_to_source(ids[11], 10_000) == (1, "s1-b1", 2, 9)  # past the end
    assert al.block_pages(ids[11]) == [1]
    assert {t.block for t in al.unmatched_source_tokens(1)} == {"s1-b0", "s1-b12"}
    assert al.unmatched_output_tokens(ids[11]) == []
    span = ctx.blocks[11].spans[0]
    assert span.page == 1 and span.text is None  # book text only with diagnostics on


def test_missing_and_invented_paragraphs(tmp_path):
    pages, texts = novel(700)
    invented = "zqx " * 6 + "a whole sentence nobody wrote in the source"
    out = texts[:5] + texts[6:15] + [invented] + texts[15:]
    ctx = build(tmp_path, pages, [paras(out)])
    summary = account(ctx)
    assert codes(ctx) == [("coverage.invented_text", 1), ("coverage.missing_text", 0)]
    missing = next(i for i in ctx.issues if i.code == "coverage.missing_text")
    assert missing.severity == Severity.CRITICAL and missing.material
    assert missing.evidence.startswith("s0-b6 ") and missing.action == "flagged"
    assert missing.region is not None and missing.output_anchor.endswith(ctx.blocks[4].id)
    invented_issue = next(i for i in ctx.issues if i.code == "coverage.invented_text")
    assert invented_issue.output_anchor.endswith(ctx.blocks[14].id)
    assert summary["pages"]["0"] < 1.0 and summary["pages"]["1"] == 1.0


def test_generated_blocks_are_excluded(tmp_path):
    pages, texts = novel(400)
    nav = '<nav data-qc-generated="toc"><p>Contents of this edition</p></nav>'
    label = '<p data-qc-generated="label">Editor added label</p>'
    ctx = build(tmp_path, pages, [nav + paras(texts[:3]) + label + paras(texts[3:])])
    summary = account(ctx)
    assert ctx.issues == []
    generated = [b for b in ctx.blocks if b.alignment == "generated"]
    assert [b.text for b in generated] == ["Contents of this edition", "Editor added label"]
    assert summary["output"]["generated_blocks"] == 2


def test_figures_account_for_regions(tmp_path):
    pages, texts = novel(700)
    ctx = build(tmp_path, pages, [paras(texts)])
    region = ImageRegion("s1-i0", 1, Box(0.2, 0.3, 0.8, 0.6), "vector")
    ctx.source.pages[1].images.append(region)
    account(ctx)
    lost = [i for i in ctx.issues if i.code == "coverage.lost_figure"]
    assert len(lost) == 1 and lost[0].page == 1 and lost[0].region == region.box
    # a repair adds a figure at the end of the book that says which region it shows
    figure = '<figure data-qc-source="s1-i0"><img src="f.png" alt=""/></figure>'
    ctx = build(tmp_path, pages, [paras(texts) + figure])
    ctx.source.pages[1].images.append(region)
    summary = account(ctx)
    assert ctx.issues == []
    assert summary["illustrations"] == {
        "regions": 1,
        "accounted": 1,
        "ratio": 1.0,
        "figure_text_blocks": 0,
        "text_panels": 0,
    }
    shown = ctx.alignment.images["s1-i0"]
    assert ctx.alignment.block_pages(shown) == [1]
    assert next(b for b in ctx.blocks if b.id == shown).spans[0].image_ref == "s1-i0"


def test_running_furniture_and_notes():
    body = ["one two three four five six seven eight nine ten"] * 3
    pages = [
        [
            block(p, 0, [f"Chapter One {p + 7}"], 0.03, 10),
            block(p, 1, body, 0.2),
            block(p, 2, ["1 A note set small at the foot."], 0.85, 9),
            block(p, 3, ["2 Not a note, body size."], 0.75),
            block(p, 4, [f"{p + 7}"], 0.95, 10),
        ]
        for p in range(2)
    ]
    pages[0].append(block(0, 5, ["Unique margin line"], 0.96, 10))
    source = inventory(pages)
    assert running_furniture(source) == {"s0-b0", "s1-b0", "s0-b4", "s1-b4"}
    assert note_blocks(source) == {"s0-b2", "s1-b2"}


def test_reading_order_reads_columns_on_flagged_pages():
    left = [block(0, 1, ["left top"], 0.2), block(0, 2, ["left bottom"], 0.5)]
    right = [block(0, 3, ["right top"], 0.2), block(0, 4, ["right bottom"], 0.5)]
    for b in right:
        b.box = Box(0.55, b.box.y0, 0.9, b.box.y1)
    for b in left:
        b.box = Box(0.1, b.box.y0, 0.45, b.box.y1)
    head = block(0, 0, ["Header"], 0.02)
    page = inventory([[head, left[0], right[0], left[1], right[1]]]).pages[0]
    assert [b.id for b in reading_order(page)] == ["s0-b0", "s0-b1", "s0-b3", "s0-b2", "s0-b4"]
    page.flags.append("multi_column")
    assert [b.id for b in reading_order(page)] == ["s0-b0", "s0-b1", "s0-b2", "s0-b3", "s0-b4"]


def test_alignment_speed(tmp_path):
    pages, texts = novel(20_000)
    ctx = build(tmp_path, pages, [paras(texts[k : k + 100]) for k in range(0, len(texts), 100)])
    started = time.perf_counter()
    summary = account(ctx)
    assert time.perf_counter() - started < 3.0
    assert ctx.issues == [] and summary["prose"]["ratio"] == 1.0
