"""System + user prompt composition for book-insight generation.

PROMPT_VERSION is part of the cache key for `book_insights`. Bump it whenever
the system prompt or schema is changed in a way that materially affects
output. Do NOT bump it for typo fixes or whitespace.

The runtime source of truth is THIS CONSTANT — ``quire_server/main.py``
constructs the InsightOrchestrator with this value via
``core/ai/_compat.py::_resolve_prompt_version``. The env var
``QUIRE_SERVER_AI_PROMPT_VERSION`` is an emergency rollback override only;
see coordinator.md §3.1, Lock #2, and Lock #19 (legacy ``"1"`` is a sentinel
for "unset").
"""

from __future__ import annotations

from quire_server.api.ai_schemas import AiStyle, Citation, MetadataBundle
from quire_server.core.ai.themes import CONTROLLED_THEMES

PROMPT_VERSION = "5"

# `tone` and `language` (from AiStyle) participate in the cache key via the
# `book_insights.tone` and `book_insights.language` columns, so emitting
# tone- or language-specific instructions in the prompt is safe across users.
# `feedback` does NOT participate in the cache key — it's a one-shot input on
# /insights/regenerate that produces a new row marked superseded against the
# previous one.

# Controlled themes vocabulary appended to SYSTEM_PROMPT so the model can
# pick from it. Sorted for prompt stability across Python runs (frozenset
# iteration order is otherwise hash-seed-dependent — would silently churn
# every PROMPT_VERSION otherwise).
_THEMES_VOCAB_BLOCK = "Controlled themes vocabulary:\n" + ", ".join(sorted(CONTROLLED_THEMES))

SYSTEM_PROMPT = (
    "You write cached, user-agnostic book insights for Quire, a privacy-first reading app.\n"
    "\n"
    "JSON key order matters. Generate keys in this order: intro, author, series, analysis,"
    " content_warnings, themes, theme_analysis, craft_notes, comparative_anchors,"
    " distinctive_take, discussion_prompts, confidence.\n"
    "\n"
    "Rules:\n"
    "- Use the supplied EPUB metadata as the work's identity. If metadata names a series,"
    " copy `name` and `position` exactly.\n"
    "- If metadata has no series, fill `series` only when a cited source or clearly canonical"
    " knowledge makes the series name and position unambiguous. Otherwise omit it (null).\n"
    "- Be conservative with author identity. Fill `author` only when you have high confidence"
    " the supplied author matches the cited sources or a well-known author. Otherwise leave"
    " it null.\n"
    "- `intro`: 1-2 sentences saying what the book is and why a reader might care. No spoilers"
    " past the inciting incident.\n"
    "- `analysis`: one compact paragraph, ~80-130 words, weaving together a short synopsis,"
    ' the major themes, the tone or style, and a one-line "you\'ll like this if…" pointer.'
    " Avoid bullet lists; keep it readable.\n"
    "- `content_warnings`: only concrete reader-safety concerns — graphic violence, sexual"
    " content, abuse, self-harm, racism or slurs, addiction, body horror. Do NOT list themes,"
    " genre, politics, or plot mechanics here.\n"
    "- `themes`: pick 1-5 tags from the controlled vocabulary listed at the end of this prompt."
    " Prefer the listed names exactly. If a clearly-applicable concept is missing, you MAY emit"
    " your own short snake_case string; the server preserves it with reduced confidence. Do NOT"
    ' emit the literal string "other".\n'
    '- `confidence`: "high" only when at least one external citation grounds the central'
    ' book claims; "medium" when metadata plus reliable training knowledge is enough;'
    ' "low" otherwise.\n'
    "- `theme_analysis`: pick the TWO themes most central to this book (NOT all"
    " themes in `themes`; pick by centrality, not list order). For each, write"
    " 2-4 sentences on how that theme manifests in THIS specific book — cite a"
    " recurring image, structural choice, or character relationship. Do NOT"
    " restate the theme definition. Output dict has 0, 1, or 2 keys; NEVER more"
    " than two (the server REJECTS payloads with >2 keys). Null is acceptable"
    " when no theme dominates.\n"
    "- `craft_notes`: 3-5 sentences combining structure (POV, tense, pacing,"
    " time handling) with prose qualities. Only include if genuinely"
    " distinctive — null is acceptable for ordinary-craft books and most"
    " nonfiction.\n"
    "- `comparative_anchors`: 2-4 entries. Use ONLY books you are confident"
    " exist as published works. The `similar_in` line must be specific (NOT"
    " 'both are dystopias' — instead 'both use the boarding school as a closed"
    " society where adults are absent'). `different_in` is optional; include"
    " only when the contrast is non-trivial. Never invent titles.\n"
    "- `distinctive_take`: 1-2 sentences on what THIS book does that other"
    " books in its themes don't. NOT a recap; a differentiator.\n"
    "- `discussion_prompts`: 3-5 probing questions about theme, character, or"
    " structure (e.g. \"How does the protagonist's relationship to language"
    ' shift after chapter 12?"). DO NOT reveal plot beats past the inciting'
    " incident. NOT plot-recap questions.\n"
    "- Output strict JSON conforming exactly to the supplied JSON schema. No prose, no"
    " markdown, no code fences.\n"
    "\n" + _THEMES_VOCAB_BLOCK
)


_TONE_HINT = {
    "neutral": "",
    "enthusiastic": "Tone: warm, energetic, a recommender's voice — without overselling.",
    "scholarly": "Tone: precise and analytical, comfortable with literary terms.",
    "casual": "Tone: conversational and direct, like a friend with good taste.",
}


def compose_user_prompt(
    bundle: MetadataBundle,
    citations: list[Citation],
    *,
    style: AiStyle | None = None,
    feedback: str | None = None,
) -> str:
    lines: list[str] = []
    lines.append("Generate a book insight for the following work.")
    lines.append("")
    lines.append("## Metadata (from the EPUB)")
    lines.append(f"- Title: {bundle.title}")
    if bundle.author:
        lines.append(f"- Author: {bundle.author}")
    if bundle.series_name:
        position = bundle.series_position
        pos_text = f", book {position}" if position is not None else ""
        lines.append(f"- Series (authoritative — do not override): {bundle.series_name}{pos_text}")
    if bundle.language:
        lines.append(f"- Language: {bundle.language}")
    if bundle.publisher:
        lines.append(f"- Publisher: {bundle.publisher}")
    if bundle.publish_date:
        lines.append(f"- Publish date: {bundle.publish_date}")
    if bundle.isbn:
        lines.append(f"- ISBN: {bundle.isbn}")
    if bundle.subjects:
        lines.append(f"- Subjects: {', '.join(bundle.subjects)}")
    if bundle.description:
        lines.append("- Publisher description:")
        lines.append(f"  > {bundle.description.strip()}")

    if citations:
        lines.append("")
        lines.append("## External sources (use these to ground your answer)")
        for c in citations:
            label = {
                "wikipedia": "Wikipedia",
                "openlibrary": "OpenLibrary",
                "opf": "OPF metadata",
                "model": "Model knowledge",
            }.get(c.kind, c.kind.capitalize())
            lines.append(f"- {label}: {c.title}")
            if c.snippet:
                lines.append(f"  > {c.snippet.strip()}")

    if style is not None:
        hint = _TONE_HINT.get(style.tone, "")
        if hint:
            lines.append("")
            lines.append(hint)
        # `auto` is the universal default and emits no language clause —
        # preserves pre-PR4 prompt body byte-for-byte. Non-auto codes are
        # validated to ISO 639-1 in AiStyle._validate_language.
        if style.language != "auto":
            lines.append("")
            lines.append(
                f'Respond in the language identified by ISO 639-1 code "{style.language}".'
            )

    if feedback:
        lines.append("")
        lines.append("## User feedback on the previous attempt")
        lines.append(f"> {feedback.strip()}")
        lines.append(
            "Apply factual or coverage corrections. Ignore tone, length, or "
            "personalization requests — those are handled separately."
        )

    lines.append("")
    lines.append("Return BookInsightPayload JSON only.")
    return "\n".join(lines)
