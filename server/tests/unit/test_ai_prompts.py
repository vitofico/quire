from opds_sync.api.ai_schemas import AiStyle, Citation, MetadataBundle
from opds_sync.core.ai.prompts import (
    PROMPT_VERSION,
    compose_user_prompt,
    SYSTEM_PROMPT,
)


def test_prompt_version_is_string():
    assert isinstance(PROMPT_VERSION, str)
    assert PROMPT_VERSION  # non-empty


def test_user_prompt_includes_metadata_fields():
    bundle = MetadataBundle(
        title="Foundation",
        author="Isaac Asimov",
        publisher="Bantam Spectra",
        description="The story of psychohistory.",
        subjects=["Science Fiction", "Galactic empire"],
    )
    text = compose_user_prompt(bundle, citations=[])
    assert "Foundation" in text
    assert "Isaac Asimov" in text
    assert "Bantam Spectra" in text
    assert "psychohistory" in text
    assert "Science Fiction" in text


def test_user_prompt_includes_citations():
    bundle = MetadataBundle(title="Foundation")
    cite = Citation(
        kind="wikipedia",
        title="Foundation (novel)",
        url="https://en.wikipedia.org/wiki/Foundation_(novel)",
        snippet="Foundation is a 1951 science fiction novel by Isaac Asimov.",
    )
    text = compose_user_prompt(bundle, citations=[cite])
    assert "Wikipedia" in text or "wikipedia" in text
    assert "1951 science fiction novel" in text


def test_user_prompt_marks_series_authoritative_when_in_bundle():
    bundle = MetadataBundle(
        title="Foundation and Empire",
        series_name="Foundation",
        series_position=2,
    )
    text = compose_user_prompt(bundle, citations=[])
    assert "series" in text.lower()
    assert "Foundation" in text
    assert "2" in text


def test_system_prompt_describes_role_and_output_constraints():
    assert "book" in SYSTEM_PROMPT.lower()
    assert "json" in SYSTEM_PROMPT.lower()


def test_style_block_emitted_when_non_default():
    bundle = MetadataBundle(title="Foundation")
    style = AiStyle(tone="scholarly", length="deep", author_focus="detailed",
                    include_spoilers=True, interests=["historical_context"])
    text = compose_user_prompt(bundle, citations=[], style=style)
    low = text.lower()
    assert "scholarly" in low
    assert "deep" in low or "detailed" in low
    assert "historical_context" in low or "historical context" in low
    assert "spoiler" in low  # spoilers permitted line


def test_style_omitted_when_all_defaults():
    """Defaults must not bloat the prompt — quota matters."""
    bundle = MetadataBundle(title="Foundation")
    text_no_style = compose_user_prompt(bundle, citations=[])
    text_default_style = compose_user_prompt(bundle, citations=[], style=AiStyle())
    assert text_no_style == text_default_style


def test_feedback_block_appended_on_regeneration():
    bundle = MetadataBundle(title="Foundation")
    text = compose_user_prompt(bundle, citations=[], feedback="Author bio was wrong.")
    assert "feedback" in text.lower()
    assert "Author bio was wrong." in text
