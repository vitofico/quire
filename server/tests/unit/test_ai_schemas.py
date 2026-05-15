import pytest
from pydantic import ValidationError

from opds_sync.api.ai_schemas import (
    BookInsightPayload,
    InsightLookupBody,
)


def test_payload_round_trip_minimal():
    p = BookInsightPayload(confidence="high")
    again = BookInsightPayload.model_validate_json(p.model_dump_json())
    assert again.confidence == "high"
    assert again.schema_version == 1
    assert again.summary is None


def test_payload_extra_fields_rejected():
    with pytest.raises(ValidationError):
        BookInsightPayload.model_validate({"schema_version": 1, "fictional_field": "no"})


def test_lookup_body_requires_content_hash():
    with pytest.raises(ValidationError):
        InsightLookupBody.model_validate(
            {"identity": {"metadata_id": "x"}, "bundle": {"title": "y"}}
        )


def test_lookup_body_metadata_id_optional():
    b = InsightLookupBody.model_validate(
        {
            "identity": {"content_hash": "abc"},
            "bundle": {"title": "Foundation"},
        }
    )
    assert b.identity.metadata_id is None
    assert b.bundle.title == "Foundation"


def test_style_defaults():
    from opds_sync.api.ai_schemas import AiStyle

    s = AiStyle()
    assert s.tone == "neutral"
    assert s.length == "standard"
    assert s.author_focus == "moderate"
    assert s.include_spoilers is False
    assert "themes" in s.interests


def test_style_rejects_unknown_tone():
    from opds_sync.api.ai_schemas import AiStyle

    with pytest.raises(ValidationError):
        AiStyle.model_validate({"tone": "snarky"})


def test_regenerate_requires_reason():
    from opds_sync.api.ai_schemas import InsightRegenerateBody

    with pytest.raises(ValidationError):
        InsightRegenerateBody.model_validate(
            {
                "identity": {"content_hash": "abc"},
                "bundle": {"title": "y"},
                "reason": "",
            }
        )
