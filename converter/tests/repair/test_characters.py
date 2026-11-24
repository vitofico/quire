import copy
import shutil

import pytest

from quire_convert.align.align import refresh
from quire_convert.context import ConvertContext
from quire_convert.epub.book import Book
from quire_convert.epub.calibre import run_calibre
from quire_convert.models import Severity
from quire_convert.repair import characters
from quire_convert.repair.paragraphs import replace_text
from quire_convert.report import is_safe_evidence
from quire_convert.source.preflight import inspect_pdf
from quire_convert.tools import find_tools

pytestmark = pytest.mark.tools


@pytest.fixture(scope="session")
def converted(fixture_pdfs, tmp_path_factory):
    """``get(name) -> ConvertContext`` on fresh Calibre output of a fixture, built once."""
    tools = find_tools()
    if tools.ebook_convert is None or shutil.which("pdftotext") is None:
        pytest.skip("calibre or poppler not installed")
    cache = {}

    def get(name: str) -> ConvertContext:
        if name not in cache:
            pdf, _ = fixture_pdfs[name]
            work = tmp_path_factory.mktemp(f"characters-{name}")
            inv = inspect_pdf(pdf, work / "source", toolchain=tools)
            run = run_calibre(pdf, work / "calibre.epub", work / "calibre", tools)
            cache[name] = (pdf, work, inv, run.epub_path)
        pdf, work, inv, epub = cache[name]
        ctx = ConvertContext(
            pdf_path=pdf,
            workdir=work,
            source=copy.deepcopy(inv),
            book=Book.load(epub),
            toolchain=tools,
        )
        refresh(ctx)
        return ctx

    return get


def corrupt(ctx, old: str, new: str) -> str:
    """Replace the first ``old`` in the output with ``new``; returns the block id."""
    for block in ctx.blocks:
        k = block.text.find(old)
        if k >= 0:
            _, el = ctx.book.element(f"{block.href}#{block.id}")
            assert replace_text(el, k, k + len(old), new)
            refresh(ctx)
            return block.id
    raise AssertionError(old)


def text(ctx, block_id: str) -> str:
    return next(b.text for b in ctx.blocks if b.id == block_id)


def issues(ctx, code: str):
    return [i for i in ctx.issues if i.code == code]


def source_box(ctx, page: int, word: str):
    return next(
        w.box
        for b in ctx.source.pages[page].blocks
        for ln in b.lines
        for w in ln.words
        if w.text == word
    )


def assert_safe(ctx):
    for repair in ctx.repairs:
        assert all(is_safe_evidence(e) for e in repair.evidence), repair.evidence
    for issue in ctx.issues:
        assert is_safe_evidence(issue.evidence), issue.evidence


# --------------------------------------------------------------------------- positive


def test_mojibake_matching_the_source_is_repaired(converted):
    ctx = converted("italian")
    first = corrupt(ctx, "caffè", "caffÃ¨")
    second = corrupt(ctx, "l'anima", "lâ€™anima")
    characters.apply(ctx)
    assert "del caffè." in text(ctx, first)
    assert "perché l’anima chiedeva" in text(ctx, second)
    fixed = [r for r in ctx.repairs if r.action == "fix_mojibake"]
    assert len(fixed) == 2 and all("caff" in r.before or "anima" in r.before for r in fixed)
    assert not ctx.issues
    assert_safe(ctx)


def test_presentation_ligature_is_expanded(converted):
    ctx = converted("paragraph-spanning-pages")
    block = corrupt(ctx, "figures", "ﬁgures")
    characters.apply(ctx)
    assert "his figures in the kitchen" in text(ctx, block)
    assert [r.action for r in ctx.repairs] == ["expand_ligature"]
    assert not ctx.issues


# --------------------------------------------------------------------------- near matches


@pytest.mark.parametrize("name", ["italian", "duplicate-chapter-names", "two-column"])
def test_clean_text_with_accents_is_untouched(converted, name):
    ctx = converted(name)
    before = [b.text for b in ctx.blocks]
    characters.apply(ctx)
    assert [b.text for b in ctx.blocks] == before
    assert not ctx.repairs and not ctx.issues
    if name == "italian":
        joined = " ".join(before)
        assert all(w in joined for w in ("«È già sera»", "caffè", "perché", "più", "indicò"))


def test_mojibake_that_disagrees_with_the_source_is_kept(converted):
    ctx = converted("italian")
    block = corrupt(ctx, "caffè", "caffÃ©")  # decodes to "caffé", the page says "caffè"
    characters.apply(ctx)
    assert "del caffÃ©." in text(ctx, block)
    flagged = issues(ctx, "characters.mojibake")
    assert len(flagged) == 1 and flagged[0].severity is Severity.MAJOR
    assert not ctx.repairs and len(ctx.issues) == 1
    assert_safe(ctx)


# --------------------------------------------------------------------------- abstention


def test_replacement_character_in_a_name_is_flagged_with_its_region(converted):
    ctx = converted("duplicate-chapter-names")
    block = corrupt(ctx, "Elena", "El�na")
    characters.apply(ctx)
    assert "sounded El�na put" in text(ctx, block)
    flagged = issues(ctx, "characters.replacement_char")
    assert len(flagged) == 1 and len(ctx.issues) == 1
    issue = flagged[0]
    assert issue.severity is Severity.MAJOR and issue.page == 0
    assert issue.region == source_box(ctx, 0, "Elena")
    assert issue.output_anchor.endswith(f"#{block}")
    assert "codepoints=0xfffd" in issue.evidence and "OCR" in issue.uncertainty
    assert not ctx.repairs
    assert_safe(ctx)


def test_uncertain_digit_is_flagged_not_changed(converted):
    ctx = converted("repeated-note-numbers")
    block = corrupt(ctx, "1847", "1841")
    characters.apply(ctx)
    assert "winter of 1841." in text(ctx, block)
    flagged = issues(ctx, "characters.uncertain_token")
    assert len(flagged) == 1 and len(ctx.issues) == 1
    assert flagged[0].severity is Severity.MAJOR
    assert flagged[0].region == source_box(ctx, flagged[0].page, "1847.")
    assert not ctx.repairs
    assert_safe(ctx)


def test_ordinary_word_disagreement_is_minor(converted):
    ctx = converted("repeated-note-numbers")
    corrupt(ctx, "ledger", "ledgar")
    characters.apply(ctx)
    assert [(i.code, i.severity) for i in ctx.issues] == [("characters.mismatch", Severity.MINOR)]
    assert not ctx.repairs
