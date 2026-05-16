"""Controlled vocabulary for `BookInsightPayload.themes` (PR3).

The model is instructed (via PROMPT_VERSION="4") to pick 1-5 tags from the
vocabulary below. Vocab hits are normalized snake_case and persisted to
`book_themes` at confidence 1.0. Off-vocab strings are preserved verbatim
(after lowercase/trim) at confidence 0.5 so PR9-style aggregation can
either include or exclude them via the confidence band.

Confidence here is a NORMALIZATION BAND, not epistemic certainty:
  * 1.0 = "the model picked a controlled vocab term"
  * 0.5 = "off-vocab raw passthrough (or empty input fallback)"

The literal token "other" is NOT in the vocab (the model never sees it),
so any "other" rows in `book_themes` come exclusively from the empty-input
fallback or from a model deliberately ignoring the instruction. In both
cases they land at 0.5 confidence — PR9's `WHERE confidence >= 1.0`
top-themes query filters them out cleanly.

Renames cost data migration: if a vocab entry is renamed later, an
Alembic data migration must UPDATE existing `book_themes.theme` rows.
Pick names carefully here.
"""

from __future__ import annotations

CONTROLLED_THEMES: frozenset[str] = frozenset(
    {
        # Fiction — broad buckets
        "science_fiction",
        "fantasy",
        "literary_fiction",
        "contemporary_fiction",
        "historical_fiction",
        "young_adult",
        "middle_grade",
        "children",
        "poetry",
        "drama",
        "short_stories",
        "graphic_novel",
        # Fiction — speculative subgenres
        "dystopia",
        "post_apocalyptic",
        "cyberpunk",
        "space_opera",
        "first_contact",
        "time_travel",
        "alternate_history",
        "magical_realism",
        "epic_fantasy",
        "urban_fantasy",
        "mythology",
        "superheroes",
        # Fiction — genre
        "mystery",
        "thriller",
        "noir",
        "horror",
        "romance",
        "crime",
        "coming_of_age",
        "war_fiction",
        "adventure",
        "satire",
        "western",
        # Nonfiction
        "biography",
        "memoir",
        "history",
        "science",
        "philosophy",
        "essays",
        "journalism",
        "business",
        "economics",
        "psychology",
        "self_help",
        "travel",
        "cooking",
        "politics",
        "religion",
        "true_crime",
        "nature",
        "art",
        "music",
        "sports",
        "education",
        "technology",
        "medicine",
        "health",
    }
)

VOCAB_CONFIDENCE: float = 1.0
OTHER_CONFIDENCE: float = 0.5


def normalize_theme(raw: str) -> tuple[str, float]:
    """Map a model-emitted theme string to (canonical_theme, confidence).

    Algorithm:
      1. `stripped = raw.strip().lower()`.
      2. `candidate = stripped.replace(" ", "_").replace("-", "_")`.
      3. If `candidate in CONTROLLED_THEMES`: return (candidate, 1.0).
      4. If `not stripped` (empty after strip): return ("other", 0.5).
      5. Otherwise: return (stripped, 0.5) — passthrough with spaces preserved.

    Examples:
      `"Mystery"`          -> `("mystery", 1.0)`
      `"coming-of-age"`    -> `("coming_of_age", 1.0)`
      `"coming of age"`    -> `("coming_of_age", 1.0)`
      `"interstellar war"` -> `("interstellar war", 0.5)` (off-vocab passthrough)
      `"other"`            -> `("other", 0.5)` (model ignored the instruction; lands in OTHER band)
      `""`                 -> `("other", 0.5)` (empty fallback)
    """
    stripped = raw.strip().lower()
    candidate = stripped.replace(" ", "_").replace("-", "_")
    if candidate in CONTROLLED_THEMES:
        return candidate, VOCAB_CONFIDENCE
    if not stripped:
        return "other", OTHER_CONFIDENCE
    return stripped, OTHER_CONFIDENCE
