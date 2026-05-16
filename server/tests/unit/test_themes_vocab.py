"""Unit tests for the controlled-vocabulary normalizer (PR3)."""

from __future__ import annotations

import pytest

from opds_sync.core.ai.themes import (
    CONTROLLED_THEMES,
    OTHER_CONFIDENCE,
    VOCAB_CONFIDENCE,
    normalize_theme,
)


def test_controlled_themes_is_a_nonempty_frozenset():
    assert isinstance(CONTROLLED_THEMES, frozenset)
    assert len(CONTROLLED_THEMES) >= 50
    # Sanity: the literal "other" sentinel must NOT be in the vocab — it's
    # reserved for the empty-input fallback and must NOT be model-visible.
    assert "other" not in CONTROLLED_THEMES


@pytest.mark.parametrize(
    "raw,expected_theme",
    [
        ("mystery", "mystery"),
        ("Mystery", "mystery"),
        ("MYSTERY", "mystery"),
        ("  Mystery  ", "mystery"),
        ("coming-of-age", "coming_of_age"),
        ("coming of age", "coming_of_age"),
        ("Coming Of Age", "coming_of_age"),
        ("science_fiction", "science_fiction"),
        ("science fiction", "science_fiction"),
        ("science-fiction", "science_fiction"),
    ],
)
def test_vocab_hits_normalize_at_full_confidence(raw, expected_theme):
    theme, conf = normalize_theme(raw)
    assert theme == expected_theme
    assert conf == VOCAB_CONFIDENCE


@pytest.mark.parametrize(
    "raw,expected_theme",
    [
        ("interstellar politics", "interstellar politics"),  # spaces preserved
        ("Noir Western", "noir western"),  # lowercased, spaces kept
        ("steampunk", "steampunk"),  # off-vocab single word
    ],
)
def test_off_vocab_passthrough_at_other_confidence(raw, expected_theme):
    theme, conf = normalize_theme(raw)
    assert theme == expected_theme
    assert conf == OTHER_CONFIDENCE


@pytest.mark.parametrize("empty", ["", "   ", "\t", "\n", "  \n\t "])
def test_empty_input_collapses_to_literal_other(empty):
    theme, conf = normalize_theme(empty)
    assert theme == "other"
    assert conf == OTHER_CONFIDENCE


def test_literal_other_from_model_lands_in_other_band():
    """The model is instructed NOT to emit "other", but if it does anyway,
    the row lands at OTHER_CONFIDENCE — NOT in the controlled-vocab band.
    This keeps PR9's `WHERE confidence >= 1.0` top-themes query clean.
    """
    theme, conf = normalize_theme("other")
    assert theme == "other"
    assert conf == OTHER_CONFIDENCE
