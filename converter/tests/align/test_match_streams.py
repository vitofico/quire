"""Stream matching and normalization on synthetic token lists (no PDF, no EPUB)."""

import random

import pytest

from quire_convert.align.align import DUPLICATE, EXACT, FUZZY, MOVED, match_streams
from quire_convert.align.normalize import Token, normalize, similar, tokenize

_RNG = random.Random(3)


def words(n: int, tag: str) -> list[str]:
    """``n`` distinct-looking words, unique across calls thanks to the tag letter."""
    return [tag + "".join(_RNG.choice("bcdfghklmnprstvz") for _ in range(5)) for _ in range(n)]


A, B, C = words(30, "a"), words(30, "b"), words(30, "c")


def groups(*parts: list[str]) -> list[int]:
    return [g for g, part in enumerate(parts) for _ in part]


def test_identical_streams_match_exactly():
    m = match_streams(A + B, A + B)
    assert m.src_to_out == list(range(60)) and m.out_to_src == list(range(60))
    assert set(m.out_kind) == {EXACT} and not m.ambiguous


def test_deleted_source_words_stay_unmatched():
    m = match_streams(A + B + C, A + C)
    assert all(m.src_to_out[i] < 0 for i in range(30, 60))
    assert all(j >= 0 for j in m.out_to_src)


def test_inserted_output_words_stay_unmatched():
    extra = words(12, "x")
    m = match_streams(A + C, A + extra + C)
    assert m.out_to_src[30:42] == [-1] * 12
    assert m.out_kind[30:42] == [None] * 12
    assert all(i >= 0 for i in m.src_to_out)


def test_duplicate_points_at_claimed_source():
    out = A + B + B[:15] + C
    m = match_streams(A + B + C, out, groups(A, B, B[:15], C))
    assert m.out_kind[60:75] == [DUPLICATE] * 15
    assert m.out_to_src[60:75] == list(range(30, 45))
    assert m.src_to_out[30:45] == list(range(30, 45))  # the first copy keeps the source


def test_swapped_paragraphs_match_one_as_moved():
    m = match_streams(A + B + C, A + C + B, groups(A, C, B))
    assert all(j >= 0 for j in m.out_to_src) and all(i >= 0 for i in m.src_to_out)
    moved = {k for k, kind in enumerate(m.out_kind) if kind == MOVED}
    assert moved in (set(range(30, 60)), set(range(60, 90)))


def test_hyphen_join_and_split_merge_two_tokens():
    m = match_streams(A + ["inter", "national"] + B, A + ["international"] + B)
    assert m.src_to_out[30] == m.src_to_out[31] == 30 and m.out_kind[30] == FUZZY
    m = match_streams(A + ["international"] + B, A + ["inter", "national"] + B)
    assert m.out_to_src[30] == m.out_to_src[31] == 30
    assert m.out_kind[30:32] == [FUZZY, FUZZY]


def test_extraction_noise_matches_fuzzily():
    m = match_streams(A + ["bridge"] + B, A + ["bridqe"] + B)
    assert m.out_to_src[30] == 30 and m.out_kind[30] == FUZZY
    m = match_streams(A + ["receive"] + B, A + ["unrelated"] + B)
    assert m.out_to_src[30] == -1


def test_repeated_header_between_noise_is_a_tie():
    head = ["running", "title"]
    src = A + ["xq"] + head + ["zz"] + head + ["yy"] + B
    out = A + head + B
    m = match_streams(src, out, groups(A, head, B))
    assert m.ambiguous == {30, 31}
    assert all(m.out_to_src[j] >= 0 for j in range(62))


def test_common_words_do_not_scatter_across_unrelated_text():
    x, y = words(20, "x"), words(20, "y")
    x[3] = x[12] = y[7] = y[15] = "the"
    m = match_streams(A + x + C, A + y + C)
    assert m.out_to_src[30:50] == [-1] * 20


# --------------------------------------------------------------------------- normalize


def test_normalize_folds_ligatures_quotes_dashes_and_case():
    assert normalize("“Don’t” — ﬁnal ſtate").text == '"don\'t" - final state'
    assert normalize("  Wide\t\n space  ").text == "wide space"


def test_composed_and_decomposed_forms_tokenize_alike():
    assert [t.norm for t in tokenize("Café città")] == ["café", "città"]
    assert [t.norm for t in tokenize("Café città")] == ["café", "città"]


def test_tokens_carry_original_offsets():
    text = "  ﬁne  Day co­operate"
    assert tokenize(text) == [Token("fine", 2, 5), Token("day", 7, 10), Token("cooperate", 11, 21)]
    norm = normalize("ﬁx")
    assert norm.text == "fix" and norm.starts == (0, 0, 1) and norm.ends == (1, 1, 2)


@pytest.mark.parametrize(
    ("a", "b", "close"),
    [
        ("bridge", "bridqe", True),
        ("the", "tbe", True),
        ("an", "on", False),  # too short to trust
        ("receive", "recieve", False),  # two edits on seven letters
        ("chapter", "chapters", True),
        ("stone", "tones", False),
    ],
)
def test_similar(a, b, close):
    assert similar(a, b) is close
