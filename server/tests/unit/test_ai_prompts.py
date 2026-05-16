from opds_sync.api.ai_schemas import AiStyle, Citation, MetadataBundle
from opds_sync.core.ai.prompts import (
    PROMPT_VERSION,
    SYSTEM_PROMPT,
    compose_user_prompt,
)


def test_prompt_version_is_v3():
    """PR4 bumped from v2 to v3 because the prompt body now varies on `language`."""
    assert PROMPT_VERSION == "3"


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


def test_user_prompt_includes_citations_but_strips_urls():
    bundle = MetadataBundle(title="Foundation")
    cite = Citation(
        kind="wikipedia",
        title="Foundation (novel)",
        url="https://en.wikipedia.org/wiki/Foundation_(novel)",
        snippet="Foundation is a 1951 science fiction novel by Isaac Asimov.",
    )
    text = compose_user_prompt(bundle, citations=[cite])
    assert "Wikipedia" in text
    assert "1951 science fiction novel" in text
    # URLs belong in the structured `sources` field returned by the server,
    # not in the prompt body — saves tokens.
    assert "https://" not in text


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
    low = SYSTEM_PROMPT.lower()
    assert "book" in low
    assert "json" in low
    # Reading order is load-bearing for the UI.
    assert "intro" in low and "analysis" in low and "content_warnings" in low
    # Reader-safety scope for warnings (not themes).
    assert "warnings" in low


def test_tone_hint_emitted_when_non_default():
    bundle = MetadataBundle(title="Foundation")
    text = compose_user_prompt(bundle, citations=[], style=AiStyle(tone="scholarly"))
    assert "scholarly" in text.lower() or "analytical" in text.lower()


def test_tone_hint_omitted_when_default():
    """Default tone must not bloat the prompt — quota matters."""
    bundle = MetadataBundle(title="Foundation")
    text_no_style = compose_user_prompt(bundle, citations=[])
    text_default_style = compose_user_prompt(bundle, citations=[], style=AiStyle())
    assert text_no_style == text_default_style


def test_language_clause_emitted_when_non_auto():
    bundle = MetadataBundle(title="Foundation")
    text = compose_user_prompt(bundle, citations=[], style=AiStyle(language="it"))
    assert 'ISO 639-1 code "it"' in text


def test_language_clause_omitted_when_auto():
    """`auto` must produce a prompt byte-for-byte identical to the no-style call."""
    bundle = MetadataBundle(title="Foundation")
    text_no_style = compose_user_prompt(bundle, citations=[])
    text_auto = compose_user_prompt(bundle, citations=[], style=AiStyle(language="auto"))
    text_default = compose_user_prompt(bundle, citations=[], style=AiStyle())
    assert text_no_style == text_auto == text_default


def test_language_clause_independent_of_tone():
    """Setting both tone and language emits both clauses."""
    bundle = MetadataBundle(title="Foundation")
    text = compose_user_prompt(
        bundle,
        citations=[],
        style=AiStyle(tone="scholarly", language="fr"),
    )
    assert "analytical" in text.lower() or "scholarly" in text.lower()
    assert 'ISO 639-1 code "fr"' in text


def test_feedback_block_appended_on_regeneration():
    bundle = MetadataBundle(title="Foundation")
    text = compose_user_prompt(bundle, citations=[], feedback="Author bio was wrong.")
    low = text.lower()
    assert "feedback" in low
    assert "Author bio was wrong." in text
    # Regenerate feedback is for factual fixes, not personalization.
    assert "factual" in low or "corrections" in low


def test_trailing_instruction_is_concise():
    bundle = MetadataBundle(title="Foundation")
    text = compose_user_prompt(bundle, citations=[])
    assert text.rstrip().endswith("Return BookInsightPayload JSON only.")
