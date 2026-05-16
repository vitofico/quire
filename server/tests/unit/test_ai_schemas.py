import pytest
from pydantic import ValidationError

from opds_sync.api.ai_schemas import (
    AiStyle,
    BookInsightPayload,
    InsightLookupBody,
    InsightRegenerateBody,
    SeriesInsight,
)


def test_payload_round_trip_minimal():
    p = BookInsightPayload(confidence="high")
    again = BookInsightPayload.model_validate_json(p.model_dump_json())
    assert again.confidence == "high"
    assert again.schema_version == 2
    assert again.intro is None
    assert again.analysis is None


def test_payload_extra_fields_rejected():
    with pytest.raises(ValidationError):
        BookInsightPayload.model_validate({"schema_version": 2, "fictional_field": "no"})


def test_payload_dropped_v1_fields_rejected():
    # v1 had summary/themes/tone/suggested_for/notes — none should validate under v2.
    for stale in ("summary", "themes", "tone", "suggested_for", "notes", "content_advisory"):
        with pytest.raises(ValidationError):
            BookInsightPayload.model_validate({stale: "x"})


def test_payload_key_order_matches_reading_order():
    """Schema field order steers the model's generation order on response_format."""
    keys = list(BookInsightPayload.model_fields.keys())
    expected = [
        "intro",
        "author",
        "series",
        "analysis",
        "content_warnings",
        "confidence",
        "schema_version",
    ]
    assert keys == expected


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
