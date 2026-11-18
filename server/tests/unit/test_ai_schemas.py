import pytest
from pydantic import ValidationError

from quire_server.api.ai_schemas import (
    AiStyle,
    BookInsightPayload,
    ComparativeAnchor,
    InsightLookupBody,
    InsightRegenerateBody,
    MetadataBundle,
    SeriesInsight,
)


def test_payload_round_trip_minimal():
    p = BookInsightPayload(confidence="high")
    again = BookInsightPayload.model_validate_json(p.model_dump_json())
    assert again.confidence == "high"
    assert again.schema_version == 5
    assert again.intro is None
    assert again.analysis is None
    assert again.themes is None


def test_payload_extra_fields_rejected():
    with pytest.raises(ValidationError):
        BookInsightPayload.model_validate({"schema_version": 4, "fictional_field": "no"})


def test_payload_dropped_v1_fields_rejected():
    # v1 had summary/tone/suggested_for/notes — none should validate under v3.
    # `themes` was re-introduced in PR3 (v3) and is intentionally NOT in this list.
    for stale in ("summary", "tone", "suggested_for", "notes", "content_advisory"):
        with pytest.raises(ValidationError):
            BookInsightPayload.model_validate({stale: "x"})


def test_payload_accepts_themes_field():
    """PR3 (v3) re-introduces `themes` as a first-class output. With PR-ε the
    default schema_version is 4 but explicit v3 payloads still round-trip."""
    p = BookInsightPayload.model_validate(
        {"themes": ["mystery", "noir"], "confidence": "low", "schema_version": 3}
    )
    assert p.themes == ["mystery", "noir"]
    assert p.schema_version == 3


def test_payload_v2_no_themes_still_deserializes():
    """Old cached v2 payloads (no `themes` key, `schema_version=2`) must still
    deserialize cleanly. `themes` defaults to None; `schema_version` reads back
    as 2 because the model honors the supplied value over the default.
    """
    p = BookInsightPayload.model_validate({"schema_version": 2, "confidence": "low"})
    assert p.schema_version == 2
    assert p.themes is None


def test_payload_key_order_matches_reading_order():
    """Schema field order steers the model's generation order on response_format."""
    keys = list(BookInsightPayload.model_fields.keys())
    expected = [
        "intro",
        "author",
        "series",
        "analysis",
        "content_warnings",
        "themes",
        # PR-ε / schema v4: per-book depth fields.
        "theme_analysis",
        "craft_notes",
        "comparative_anchors",
        "distinctive_take",
        # Schema v5.
        "curiosities",
        "discussion_prompts",
        "confidence",
        "schema_version",
    ]
    assert keys == expected


# ---------------------------------------------------------------------------
# PR-ε / schema v4 validators and round-trips.
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "raw,expected_version,expected",
    [
        pytest.param(
            {
                "intro": "i",
                "analysis": "a",
                "themes": ["mystery"],
                "theme_analysis": {"mystery": "Manifests through ..."},
                "craft_notes": "Tight close third POV ...",
                "comparative_anchors": [
                    {
                        "book": "X",
                        "author": "Y",
                        "similar_in": "Both ...",
                        "different_in": "X is ...",
                    }
                ],
                "distinctive_take": "What sets it apart ...",
                "curiosities": ["The author wrote it in six weeks."],
                "discussion_prompts": ["Q1?", "Q2?"],
                "confidence": "medium",
            },
            5,
            {
                "curiosities": ["The author wrote it in six weeks."],
                "theme_analysis": {"mystery": "Manifests through ..."},
                "comparative_anchors": [
                    {
                        "book": "X",
                        "author": "Y",
                        "similar_in": "Both ...",
                        "different_in": "X is ...",
                    }
                ],
            },
            id="v5",
        ),
        pytest.param(
            {
                "schema_version": 3,
                "intro": "i",
                "analysis": "a",
                "themes": ["mystery"],
                "confidence": "low",
            },
            3,
            {"theme_analysis": None, "discussion_prompts": None, "curiosities": None},
            id="cached-v3",
        ),
        pytest.param(
            {"schema_version": 2, "intro": "i", "confidence": "low"},
            2,
            {"themes": None, "theme_analysis": None},
            id="cached-v2",
        ),
    ],
)
def test_book_insight_payload_version_round_trip(raw, expected_version, expected):
    parsed = BookInsightPayload.model_validate(raw)
    round_trip = BookInsightPayload.model_validate_json(parsed.model_dump_json())
    assert round_trip.schema_version == expected_version
    dumped = round_trip.model_dump()
    assert {field: dumped[field] for field in expected} == expected


def test_comparative_anchor_rejects_extra_fields():
    """``extra='forbid'`` invariant on payload sub-types."""
    with pytest.raises(ValidationError):
        ComparativeAnchor.model_validate(
            {"book": "X", "author": "Y", "similar_in": "Z", "bogus": "x"}
        )


def test_book_insight_payload_theme_analysis_accepts_empty_dict():
    """Spec: empty dict allowed; null when model couldn't generate."""
    p = BookInsightPayload(theme_analysis={})
    assert p.theme_analysis == {}


def test_book_insight_payload_theme_analysis_rejects_three_keys():
    """Server-side enforcement of the 2-key cap. If the model ignores the
    prompt and emits 3+ themes, the server REJECTS the payload."""
    bad = {
        "schema_version": 4,
        "theme_analysis": {"a": "x", "b": "y", "c": "z"},
        "confidence": "low",
    }
    with pytest.raises(ValidationError) as ei:
        BookInsightPayload.model_validate(bad)
    assert "at most 2" in str(ei.value)


def test_book_insight_payload_theme_analysis_two_keys_accepted():
    """The exact boundary — 2 keys passes."""
    p = BookInsightPayload.model_validate(
        {
            "schema_version": 4,
            "theme_analysis": {"a": "x", "b": "y"},
            "confidence": "low",
        }
    )
    assert p.theme_analysis == {"a": "x", "b": "y"}


@pytest.mark.parametrize(
    "anchors,expected_books",
    [
        pytest.param(
            [
                {"book": "Real", "author": "Auth", "similar_in": "S"},
                {"book": "  ", "author": "A", "similar_in": "S"},
                {"book": "B", "author": "", "similar_in": "S"},
                {"book": "B", "author": "A", "similar_in": "   "},
            ],
            ["Real"],
            id="blank-fields-filtered",
        ),
        pytest.param(
            [{"book": f"B{i}", "author": f"A{i}", "similar_in": f"s{i}"} for i in range(7)],
            ["B0", "B1", "B2", "B3"],
            id="cap-four",
        ),
        pytest.param(
            [{"book": " ", "author": "A", "similar_in": "S"}],
            None,
            id="all-blank-hidden",
        ),
    ],
)
def test_comparative_anchor_sanitization(anchors, expected_books):
    parsed = BookInsightPayload.model_validate(
        {"schema_version": 4, "comparative_anchors": anchors, "confidence": "low"}
    )
    books = (
        None
        if parsed.comparative_anchors is None
        else [anchor.book for anchor in parsed.comparative_anchors]
    )
    assert books == expected_books


@pytest.mark.parametrize(
    "curiosities,expected",
    [
        pytest.param(
            ["  ", "One.", "", "Two.", "Three.", "Four."],
            ["One.", "Two.", "Three."],
            id="keep-first-three-nonblank",
        ),
        pytest.param([" ", ""], None, id="all-blank-hidden"),
    ],
)
def test_curiosity_sanitization(curiosities, expected):
    parsed = BookInsightPayload.model_validate({"curiosities": curiosities, "confidence": "low"})
    assert parsed.curiosities == expected


def test_series_insight_accepts_context():
    s = SeriesInsight.model_validate(
        {"name": "Foundation", "position": 1, "context": "Book 1 of 7."}
    )
    assert s.context == "Book 1 of 7."


def test_series_insight_rejects_v1_total_known():
    with pytest.raises(ValidationError):
        SeriesInsight.model_validate({"name": "Foundation", "total_known": 7})


def test_lookup_body_accepts_metadata_id_only_identity():
    """PR2 relaxed `content_hash` from required to optional so the
    catalog-preview flow (pre-download) can carry only `metadata_id` (or
    an alias hint). The orchestrator's `_resolve_canonical` enforces that
    at least one resolvable hint is present; the schema layer no longer
    rejects metadata_id-only payloads.
    """
    b = InsightLookupBody.model_validate(
        {"identity": {"metadata_id": "x"}, "bundle": {"title": "y"}}
    )
    assert b.identity.metadata_id == "x"
    assert b.identity.content_hash is None


def test_lookup_body_metadata_id_optional():
    b = InsightLookupBody.model_validate(
        {
            "identity": {"content_hash": "abc"},
            "bundle": {"title": "Foundation"},
        }
    )
    assert b.identity.metadata_id is None
    assert b.bundle.title == "Foundation"


def test_style_has_tone_and_language():
    s = AiStyle()
    assert s.tone == "neutral"
    assert s.language == "auto"
    assert list(AiStyle.model_fields.keys()) == ["tone", "language"]


def test_style_rejects_unknown_tone():
    with pytest.raises(ValidationError):
        AiStyle.model_validate({"tone": "snarky"})


def test_style_rejects_v1_knobs():
    for stale in ("length", "author_focus", "include_spoilers", "interests"):
        with pytest.raises(ValidationError):
            AiStyle.model_validate({"tone": "neutral", stale: "anything"})


@pytest.mark.parametrize("code", ["en", "it", "es", "fr", "de", "pt", "nl", "zh", "ja"])
def test_style_accepts_iso_639_1_languages(code):
    s = AiStyle.model_validate({"tone": "neutral", "language": code})
    assert s.language == code


def test_style_accepts_auto_language():
    s = AiStyle.model_validate({"tone": "neutral", "language": "auto"})
    assert s.language == "auto"


@pytest.mark.parametrize(
    "bad",
    [
        "invalid",  # not a code
        "english",  # word, not a code
        "",  # empty
        "EN",  # uppercase
        "e",  # too short
        "eng",  # ISO 639-2/3, not 639-1
    ],
)
def test_style_rejects_invalid_language(bad):
    with pytest.raises(ValidationError):
        AiStyle.model_validate({"tone": "neutral", "language": bad})


@pytest.mark.parametrize("bad", ["zz", "xx", "qq"])
def test_style_rejects_unknown_iso_code(bad):
    """Proves the validation is an allowlist, not a `^[a-z]{2}$` regex."""
    with pytest.raises(ValidationError):
        AiStyle.model_validate({"tone": "neutral", "language": bad})


def test_regenerate_requires_reason():
    with pytest.raises(ValidationError):
        InsightRegenerateBody.model_validate(
            {
                "identity": {"content_hash": "abc"},
                "bundle": {"title": "y"},
                "reason": "",
            }
        )


# ---------------------------------------------------------------------------
# Phase 0, task S-3: MetadataBundle field caps + optional bundle on
# {Lookup,Regenerate} bodies.
# ---------------------------------------------------------------------------


def test_metadata_bundle_minimal_title_only():
    """Sanity: only title is required; other fields default sensibly."""
    b = MetadataBundle.model_validate({"title": "Foundation"})
    assert b.title == "Foundation"
    assert b.author is None
    assert b.subjects == []
    assert b.description is None


def test_metadata_bundle_rejects_empty_title():
    with pytest.raises(ValidationError):
        MetadataBundle.model_validate({"title": ""})


@pytest.mark.parametrize(
    "field,at_limit,over_limit",
    [
        pytest.param("title", "x" * 512, "x" * 513, id="title"),
        pytest.param("author", "x" * 512, "x" * 513, id="author"),
        pytest.param("language", "x" * 32, "x" * 33, id="language"),
        pytest.param("isbn", "x" * 64, "x" * 65, id="isbn"),
        pytest.param("publisher", "x" * 256, "x" * 257, id="publisher"),
        pytest.param("publish_date", "x" * 32, "x" * 33, id="publish-date"),
        pytest.param("series_name", "x" * 256, "x" * 257, id="series-name"),
        pytest.param("description", "x" * 4096, "x" * 4097, id="description"),
        pytest.param("subjects", ["s"] * 64, ["s"] * 65, id="subject-count"),
        pytest.param("subjects", ["x" * 80], ["x" * 81], id="subject-length"),
        pytest.param("series_position", 0, -1, id="series-position-minimum"),
        pytest.param("series_position", 10_000, 10_001, id="series-position-maximum"),
    ],
)
def test_metadata_bundle_bounds(field, at_limit, over_limit):
    accepted = MetadataBundle.model_validate({"title": "t"} | {field: at_limit})
    assert getattr(accepted, field) == at_limit

    with pytest.raises(ValidationError) as exc:
        MetadataBundle.model_validate({"title": "t"} | {field: over_limit})
    assert [error["loc"] for error in exc.value.errors()] == [(field,)]


def test_lookup_body_bundle_optional():
    """S-3: clients can omit the bundle (server-side handler then either
    falls back to library_items behind a flag or returns 400)."""
    b = InsightLookupBody.model_validate({"identity": {"content_hash": "abc"}})
    assert b.bundle is None


def test_lookup_body_rejects_extra_fields():
    """S-3 hardening: ``extra='forbid'`` so a typo in the request body
    surfaces as 422 instead of being silently ignored."""
    with pytest.raises(ValidationError):
        InsightLookupBody.model_validate(
            {
                "identity": {"content_hash": "abc"},
                "bundle": {"title": "t"},
                "metadata": {"title": "typo"},  # not 'bundle'
            }
        )


def test_regenerate_body_bundle_optional():
    b = InsightRegenerateBody.model_validate(
        {"identity": {"content_hash": "abc"}, "reason": "force"}
    )
    assert b.bundle is None


def test_regenerate_body_rejects_extra_fields():
    with pytest.raises(ValidationError):
        InsightRegenerateBody.model_validate(
            {
                "identity": {"content_hash": "abc"},
                "reason": "force",
                "unknown": True,
            }
        )
