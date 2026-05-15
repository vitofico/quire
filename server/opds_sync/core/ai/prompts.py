"""System + user prompt composition for book-insight generation.

PROMPT_VERSION is part of the cache key for `book_insights`. Bump it whenever
the system prompt or schema is changed in a way that materially affects
output. Do NOT bump it for typo fixes or whitespace.
"""

from __future__ import annotations

from opds_sync.api.ai_schemas import AiStyle, Citation, MetadataBundle

PROMPT_VERSION = "1"

# `style` and `feedback` deliberately do NOT participate in the cache key
# (PROMPT_VERSION is the only knob there). Personalization is a presentation
# concern; if quality is poor the user regenerates, which writes a new row
# under a new id but the same (content_hash, model_id, prompt_version) — the
# old row is marked superseded by the orchestrator.

SYSTEM_PROMPT = """You write structured insights about books for a privacy-first reading app.

Goals:
- Help the reader understand what a book is, who wrote it, where it sits in
  the author's body of work, and whether it's part of a series.
- Provide useful but cautious analysis: themes, tone, content advisories,
  and a one-line "you'll like this if..." pointer.

Rules:
- Only assert things you can support from the supplied metadata, the cited
  external sources, or your own training knowledge. Where a field is unknown
  or uncertain, return null instead of inventing it.
- If a series is named in the metadata, treat that as authoritative — do not
  override it.
- Author biography in particular is high-risk: only fill it when you have
  high confidence in the identity of the author. Otherwise leave the author
  fields null.
- Set `confidence` to "high" only if at least one external citation grounds
  the central claims about the book; "medium" if you have only the metadata
  to work from; "low" otherwise.
- Output strict JSON conforming exactly to the supplied JSON schema. No
  prose, no markdown, no code fences."""


_DEFAULT_STYLE = AiStyle()


def _style_block(style: AiStyle) -> list[str]:
    """Emit a short style guide. Returns [] if style is the default — keep tokens lean."""
    if style == _DEFAULT_STYLE:
        return []
    lines = ["", "## Style preferences (apply to summary, themes, suggested_for)"]
    lines.append(f"- Tone: {style.tone}")
    lines.append(
        {
            "brief": "- Length: keep it short — 2-3 sentence summary, terse themes.",
            "standard": "- Length: standard — 4-6 sentence summary.",
            "deep": "- Length: deep dive — 6-10 sentences, richer themes.",
        }[style.length]
    )
    if style.author_focus == "none":
        lines.append("- Author: leave author fields null.")
    elif style.author_focus == "detailed":
        lines.append("- Author: detailed — fill bio, nationality, active years, notable works.")
    if style.include_spoilers:
        lines.append("- Spoilers: permitted — discuss plot points freely.")
    else:
        lines.append("- Spoilers: avoid — no plot points past the inciting incident.")
    if style.interests:
        lines.append(f"- Focus on: {', '.join(style.interests)}.")
    return lines


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
    if bundle.series_name:
        position = bundle.series_position
        pos_text = f", book {position}" if position is not None else ""
        lines.append(
            f"- Series (authoritative — do not override): {bundle.series_name}{pos_text}"
        )

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
            url = f" <{c.url}>" if c.url else ""
            lines.append(f"- {label}: {c.title}{url}")
            if c.snippet:
                lines.append(f"  > {c.snippet.strip()}")

    if style is not None:
        lines.extend(_style_block(style))

    if feedback:
        lines.append("")
        lines.append("## User feedback on the previous attempt")
        lines.append(f"> {feedback.strip()}")
        lines.append("Address the feedback above when generating this new version.")

    lines.append("")
    lines.append(
        "Return a single JSON object matching the BookInsightPayload schema. "
        "If the series is given above, copy it verbatim into the `series` field."
    )
    return "\n".join(lines)
