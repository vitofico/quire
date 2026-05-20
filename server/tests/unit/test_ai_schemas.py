import pytest
from pydantic import ValidationError

from quire_server.api.ai_schemas import (
    AiStyle,
    BookInsightPayload,
    ComparativeAnchor,
    InsightLookupBody,
    InsightRegenerateBody,
    SeriesInsight,
)


def test_payload_round_trip_minimal():
    p = BookInsightPayload(confidence="high")
    again = BookInsightPayload.model_validate_json(p.model_dump_json())
    assert again.confidence == "high"
    assert again.schema_version == 4
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
        "discussion_prompts",
        "confidence",
        "schema_version",
    ]
    assert keys == expected


# ---------------------------------------------------------------------------
# PR-ε / schema v4 validators and round-trips.
# ---------------------------------------------------------------------------


def test_book_insight_payload_v4_round_trip():
    p = BookInsightPayload(
        intro="i",
        analysis="a",
        themes=["mystery"],
        theme_analysis={"mystery": "Manifests through ..."},
        craft_notes="Tight close third POV ...",
        comparative_anchors=[
            ComparativeAnchor(book="X", author="Y", similar_in="Both ...", different_in="X is ...")
        ],
        distinctive_take="What sets it apart ...",
        discussion_prompts=["Q1?", "Q2?"],
        confidence="medium",
    )
    j = p.model_dump_json()
    p2 = BookInsightPayload.model_validate_json(j)
    assert p2.schema_version == 4
    assert p2.theme_analysis == {"mystery": "Manifests through ..."}
    assert p2.comparative_anchors is not None
    assert p2.comparative_anchors[0].different_in == "X is ..."


def test_book_insight_payload_v3_payload_deserializes():
    """Old cached v3 row (no v4 fields, has themes) must deserialize cleanly."""
    v3 = {
        "intro": "i",
        "analysis": "a",
        "themes": ["mystery"],
        "confidence": "low",
        "schema_version": 3,
    }
    p = BookInsightPayload.model_validate(v3)
    assert p.schema_version == 3
    assert p.theme_analysis is None
    assert p.discussion_prompts is None


def test_book_insight_payload_v2_payload_deserializes():
    """Even older v2 row (no themes, no v4 fields). Same invariant."""
    v2 = {"intro": "i", "confidence": "low", "schema_version": 2}
    p = BookInsightPayload.model_validate(v2)
    assert p.themes is None
    assert p.theme_analysis is None


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


def test_comparative_anchors_blank_entries_dropped():
    """Sanity filter: blank book/author/similar_in entries are dropped."""
    p = BookInsightPayload.model_validate(
        {
            "schema_version": 4,
            "comparative_anchors": [
                {"book": "Real", "author": "Auth", "similar_in": "S"},
                {"book": "  ", "author": "A", "similar_in": "S"},  # blank book
                {"book": "B", "author": "", "similar_in": "S"},  # blank author
                {"book": "B", "author": "A", "similar_in": "   "},  # blank similar_in
            ],
            "confidence": "low",
        }
    )
    assert p.comparative_anchors is not None
    assert len(p.comparative_anchors) == 1
    assert p.comparative_anchors[0].book == "Real"


def test_comparative_anchors_capped_at_four():
    """Sanity filter: cap at 4 entries; tail dropped."""
    p = BookInsightPayload.model_validate(
        {
            "schema_version": 4,
            "comparative_anchors": [
                {"book": f"B{i}", "author": f"A{i}", "similar_in": f"s{i}"} for i in range(7)
            ],
            "confidence": "low",
        }
    )
    assert p.comparative_anchors is not None
    assert len(p.comparative_anchors) == 4
    assert [a.book for a in p.comparative_anchors] == ["B0", "B1", "B2", "B3"]


def test_comparative_anchors_all_blank_becomes_none():
    """If filtering removes everything, the field collapses to None."""
    p = BookInsightPayload.model_validate(
        {
            "schema_version": 4,
            "comparative_anchors": [
                {"book": " ", "author": "A", "similar_in": "S"},
            ],
            "confidence": "low",
        }
    )
    assert p.comparative_anchors is None


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
