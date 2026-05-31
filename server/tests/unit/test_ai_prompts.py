from quire_server.api.ai_schemas import AiStyle, Citation, MetadataBundle
from quire_server.core.ai.prompts import (
    PROMPT_VERSION,
    SYSTEM_PROMPT,
    compose_user_prompt,
)


def test_prompt_version_is_v7():
    """v7 strengthened the language directive (named language, prominent, field-
    scoped) because the v6 single-line ISO-code clause was being ignored by weak
    models. Materially changes output, so the cache key must regenerate.
    """
    assert PROMPT_VERSION == "7"


def test_system_prompt_includes_themes_vocab():
    """PR3: the controlled vocabulary must be inlined in SYSTEM_PROMPT so the
    model can pick from it. We don't pin the exact wording (avoids brittle
    diff churn on vocab tweaks), but a sample of representative entries plus
    the section header must be present.
    """
    low = SYSTEM_PROMPT.lower()
    assert "controlled themes vocabulary" in low
    # Sample a handful of stable vocab entries (renames cost data migration,
    # so these should not move casually).
    for entry in ("mystery", "science_fiction", "biography", "poetry"):
        assert entry in low
    # The model must NOT be told to emit the literal "other".
    assert '"other"' in SYSTEM_PROMPT  # the "Do NOT emit ... other" line


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
    # PR-ε / schema v4: new key-order tokens.
    for v4_key in (
        "theme_analysis",
        "craft_notes",
        "comparative_anchors",
        "distinctive_take",
        "discussion_prompts",
    ):
        assert v4_key in low


def test_system_prompt_caps_theme_analysis_at_two():
    """PR-ε: server REJECTS payloads with >2 theme_analysis keys; prompt must
    tell the model so up front."""
    assert "two themes" in SYSTEM_PROMPT.lower()
    assert "REJECTS payloads with >2 keys" in SYSTEM_PROMPT


def test_system_prompt_forbids_inventing_comparative_anchors():
    """PR-ε: comparative_anchors are display-only; the model must not invent."""
    assert "Never invent titles" in SYSTEM_PROMPT


def test_system_prompt_forbids_discussion_prompt_spoilers():
    """PR-ε / Lock #7 soft mitigation: discussion_prompts must not reveal plot."""
    assert "DO NOT reveal plot beats" in SYSTEM_PROMPT


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
    assert "OUTPUT LANGUAGE" in text
    assert "Italian" in text


def test_language_clause_omitted_when_auto_and_book_language_unknown():
    """`auto` with no book language must produce a prompt byte-for-byte
    identical to the no-style call (the language-neutral default)."""
    bundle = MetadataBundle(title="Foundation")
    text_no_style = compose_user_prompt(bundle, citations=[])
    text_auto = compose_user_prompt(bundle, citations=[], style=AiStyle(language="auto"))
    text_default = compose_user_prompt(bundle, citations=[], style=AiStyle())
    assert text_no_style == text_auto == text_default


def test_language_clause_follows_book_language_when_auto():
    """`auto` defers to the book's own language so the insight matches the book."""
    bundle = MetadataBundle(title="Il nome della rosa", language="it")
    text = compose_user_prompt(bundle, citations=[], style=AiStyle(language="auto"))
    assert "OUTPUT LANGUAGE" in text
    assert "Italian" in text


def test_auto_normalizes_region_and_three_letter_book_language():
    """EPUB/OpenLibrary emit shapes like `en-US` and `eng`; `auto` folds them
    down to a bare ISO 639-1 code before instructing the model."""
    region = compose_user_prompt(
        MetadataBundle(title="A", language="en-US"),
        citations=[],
        style=AiStyle(language="auto"),
    )
    assert "English" in region

    iso2 = compose_user_prompt(
        MetadataBundle(title="B", language="eng"),
        citations=[],
        style=AiStyle(language="auto"),
    )
    assert "English" in iso2


def test_auto_skips_unrecognized_book_language():
    """An unmappable language tag yields no clause rather than a bogus code."""
    bundle = MetadataBundle(title="C", language="zxx")
    text = compose_user_prompt(bundle, citations=[], style=AiStyle(language="auto"))
    assert "OUTPUT LANGUAGE" not in text


def test_auto_rejects_bogus_two_letter_book_language():
    """A two-letter tag that isn't a real ISO 639-1 code must not leak into the
    prompt as an instruction."""
    bundle = MetadataBundle(title="C2", language="zz")
    text = compose_user_prompt(bundle, citations=[], style=AiStyle(language="auto"))
    assert "OUTPUT LANGUAGE" not in text


def test_explicit_language_overrides_book_language():
    """An explicit user code wins even when the book declares its own language."""
    bundle = MetadataBundle(title="D", language="it")
    text = compose_user_prompt(bundle, citations=[], style=AiStyle(language="en"))
    assert "English" in text
    assert "Italian" not in text


def test_language_clause_independent_of_tone():
    """Setting both tone and language emits both clauses."""
    bundle = MetadataBundle(title="Foundation")
    text = compose_user_prompt(
        bundle,
        citations=[],
        style=AiStyle(tone="scholarly", language="fr"),
    )
    assert "analytical" in text.lower() or "scholarly" in text.lower()
    assert "French" in text


def test_language_directive_protects_controlled_fields():
    """The output-language block must instruct the model NOT to translate the
    controlled fields — otherwise it corrupts `themes`/`confidence`."""
    bundle = MetadataBundle(title="Foundation", language="it")
    text = compose_user_prompt(bundle, citations=[], style=AiStyle(language="auto"))
    assert "Do NOT translate" in text
    assert "themes" in text
    assert "confidence" in text


def test_language_directive_leads_the_prompt():
    """The directive must appear before the metadata block so weak models anchor
    on it (they over-weight the first instruction)."""
    bundle = MetadataBundle(title="Foundation", language="it")
    text = compose_user_prompt(bundle, citations=[], style=AiStyle(language="auto"))
    assert text.index("OUTPUT LANGUAGE") < text.index("## Metadata")


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
