"""Pydantic schemas for the /ai/v1 surface and the persisted insight payload.

The structured BookInsight schema is also passed to the LLM as a JSON Schema
in the `response_format` of the chat completion request, so its shape is
load-bearing on both ends.
"""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

# Full ISO 639-1 set (184 codes, current as of 2024). Frozen so a future
# AiStyle.language="zz" (regex-valid but not a real language) gets a 422
# instead of being silently accepted and propagated into the cache key.
ISO_639_1_CODES: frozenset[str] = frozenset(
    {
        "aa",
        "ab",
        "ae",
        "af",
        "ak",
        "am",
        "an",
        "ar",
        "as",
        "av",
        "ay",
        "az",
        "ba",
        "be",
        "bg",
        "bh",
        "bi",
        "bm",
        "bn",
        "bo",
        "br",
        "bs",
        "ca",
        "ce",
        "ch",
        "co",
        "cr",
        "cs",
        "cu",
        "cv",
        "cy",
        "da",
        "de",
        "dv",
        "dz",
        "ee",
        "el",
        "en",
        "eo",
        "es",
        "et",
        "eu",
        "fa",
        "ff",
        "fi",
        "fj",
        "fo",
        "fr",
        "fy",
        "ga",
        "gd",
        "gl",
        "gn",
        "gu",
        "gv",
        "ha",
        "he",
        "hi",
        "ho",
        "hr",
        "ht",
        "hu",
        "hy",
        "hz",
        "ia",
        "id",
        "ie",
        "ig",
        "ii",
        "ik",
        "io",
        "is",
        "it",
        "iu",
        "ja",
        "jv",
        "ka",
        "kg",
        "ki",
        "kj",
        "kk",
        "kl",
        "km",
        "kn",
        "ko",
        "kr",
        "ks",
        "ku",
        "kv",
        "kw",
        "ky",
        "la",
        "lb",
        "lg",
        "li",
        "ln",
        "lo",
        "lt",
        "lu",
        "lv",
        "mg",
        "mh",
        "mi",
        "mk",
        "ml",
        "mn",
        "mr",
        "ms",
        "mt",
        "my",
        "na",
        "nb",
        "nd",
        "ne",
        "ng",
        "nl",
        "nn",
        "no",
        "nr",
        "nv",
        "ny",
        "oc",
        "oj",
        "om",
        "or",
        "os",
        "pa",
        "pi",
        "pl",
        "ps",
        "pt",
        "qu",
        "rm",
        "rn",
        "ro",
        "ru",
        "rw",
        "sa",
        "sc",
        "sd",
        "se",
        "sg",
        "si",
        "sk",
        "sl",
        "sm",
        "sn",
        "so",
        "sq",
        "sr",
        "ss",
        "st",
        "su",
        "sv",
        "sw",
        "ta",
        "te",
        "tg",
        "th",
        "ti",
        "tk",
        "tl",
        "tn",
        "to",
        "tr",
        "ts",
        "tt",
        "tw",
        "ty",
        "ug",
        "uk",
        "ur",
        "uz",
        "ve",
        "vi",
        "vo",
        "wa",
        "wo",
        "xh",
        "yi",
        "yo",
        "za",
        "zh",
        "zu",
    }
)


class DocumentIdentity(BaseModel):
    """Identifier(s) for a book on the AI surface.

    The two canonical schemes are `metadata_id` (derived from EPUB OPF)
    and `content_hash` (sha256 of the EPUB body). PR2 makes `content_hash`
    OPTIONAL so the catalog-preview flow (PR7) can request insights before
    the book is downloaded, using only alias fields.

    The orchestrator's `_resolve_canonical` step walks all supplied hints
    in identity-hierarchy order and resolves to a canonical via the
    `insight_identity_aliases` table; the API layer raises 422 if no hint
    can be resolved on a write path.
    """

    # Canonicals (at least one of these OR a resolvable alias must be set)
    metadata_id: str | None = None
    content_hash: str | None = None

    # Alias hints (PR2). All optional. The resolver maps them to a
    # canonical via insight_identity_aliases when present.
    opds_href: str | None = None
    opds_dc_id: str | None = None
    calibre_book_id: str | None = None
    isbn: str | None = None

    def alias_dict(self) -> dict[str, str]:
        """Return a dict of all non-None identity hints (canonicals + aliases)."""
        out: dict[str, str] = {}
        for k in (
            "metadata_id",
            "content_hash",
            "opds_dc_id",
            "isbn",
            "calibre_book_id",
            "opds_href",
        ):
            v = getattr(self, k, None)
            if v is not None:
                out[k] = v
        return out


class MetadataBundle(BaseModel):
    title: str
    author: str | None = None
    language: str | None = None
    isbn: str | None = None
    publisher: str | None = None
    publish_date: str | None = None
    subjects: list[str] = Field(default_factory=list)
    description: str | None = None
    series_name: str | None = None
    series_position: int | None = None


class Citation(BaseModel):
    kind: Literal["wikipedia", "openlibrary", "opf", "model"]
    title: str
    url: str | None = None
    snippet: str = ""


class AuthorInsight(BaseModel):
    model_config = ConfigDict(extra="forbid")

    bio: str | None = None
    notable_works: list[str] | None = None


class SeriesInsight(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str
    position: int | None = None
    context: str | None = None


class ComparativeAnchor(BaseModel):
    """One entry of ``BookInsightPayload.comparative_anchors``.

    ``similar_in`` MUST be specific (e.g. "both use the school dormitory as a
    closed-society lab"), NOT genre-level ("both are dystopias"). ``different_in``
    is optional; included only when the model can articulate a non-trivial
    contrast.

    Sanity filtering: callers should drop entries whose ``book`` or ``author``
    or ``similar_in`` is blank/whitespace; the server's ``model_validator`` on
    ``BookInsightPayload`` does this automatically.
    """

    model_config = ConfigDict(extra="forbid")

    book: str
    author: str
    similar_in: str
    different_in: str | None = None


class BookInsightPayload(BaseModel):
    """The structured body of a book insight. Stored verbatim in book_insights.payload.

    Field order is the reading order for BookDetailScreen — the model is
    instructed to generate keys in declared order, which keeps the streaming
    narrative coherent (intro before analysis, etc.).

    ``themes`` (PR3, schema v3): controlled-vocabulary topic tags. The model
    is instructed to pick from ``quire_server.core.ai.themes.CONTROLLED_THEMES``;
    off-vocab strings are preserved verbatim and surface in ``book_themes``
    at confidence 0.5. The payload field is the source of truth for the
    client; ``book_themes`` is the SQL-queryable mirror for aggregate stats.
    Old v2 payloads (no ``themes`` key) deserialize cleanly with themes=None.

    ``theme_analysis``, ``craft_notes``, ``comparative_anchors``,
    ``distinctive_take``, ``discussion_prompts`` (PR-ε, schema v4): per-book
    depth fields. All optional; null defaults so old cached v3 payloads
    deserialize unchanged. The model emits all schema-v4 keys in a single
    structured call. ``theme_analysis`` is hard-capped at 2 keys (validator
    REJECTS >2); ``comparative_anchors`` are sanitized (blank-entry drop,
    cap-at-4) and treated as display-only — the server cannot verify the
    referenced books exist.
    """

    model_config = ConfigDict(extra="forbid")

    intro: str | None = None
    author: AuthorInsight | None = None
    series: SeriesInsight | None = None
    analysis: str | None = None
    content_warnings: list[str] | None = None
    themes: list[str] | None = None
    # v4 (PR-ε): deeper per-book content. All optional and all nullable so
    # old cached v3 rows (and even v2) deserialize cleanly. The model emits
    # all schema-v4 keys in a single structured call.
    theme_analysis: dict[str, str] | None = None
    craft_notes: str | None = None
    comparative_anchors: list[ComparativeAnchor] | None = None
    distinctive_take: str | None = None
    discussion_prompts: list[str] | None = None
    confidence: Literal["high", "medium", "low"] = "low"
    schema_version: int = 4

    @model_validator(mode="after")
    def _enforce_v4_caps_and_sanitize(self) -> "BookInsightPayload":
        # theme_analysis: REJECT >2 keys. We reject (not truncate) so a
        # prompt regression that lets the model emit 3+ keys surfaces in
        # tests instead of being silently masked.
        if self.theme_analysis is not None and len(self.theme_analysis) > 2:
            raise ValueError(
                f"theme_analysis must have at most 2 entries, got {len(self.theme_analysis)}"
            )
        # comparative_anchors: sanitize. Drop entries with blank/whitespace
        # book/author/similar_in. Cap at 4 (drop extras at the tail).
        if self.comparative_anchors is not None:
            cleaned = [
                a
                for a in self.comparative_anchors
                if a.book.strip() and a.author.strip() and a.similar_in.strip()
            ]
            self.comparative_anchors = cleaned[:4] if cleaned else None
        return self


class BookInsightResponse(BaseModel):
    """What the client sees from /ai/v1/insights/*."""

    payload: BookInsightPayload
    sources: list[Citation]
    model_id: str
    prompt_version: str
    generated_at: str  # ISO-8601


class InsightLookupBody(BaseModel):
    identity: DocumentIdentity
    bundle: MetadataBundle


class InsightGetBody(BaseModel):
    identity: DocumentIdentity


class InsightInvalidateBody(BaseModel):
    identity: DocumentIdentity


class InsightPromoteBody(BaseModel):
    """Body of ``POST /ai/v1/insights/promote`` (PR-ζ).

    Carries the ``from`` catalog-side identity (pre-download alias) and the
    ``to`` post-download canonical identity. ``from`` is a Python keyword so
    the Pydantic field is ``from_`` and the wire alias is ``from``.

    ``tone`` and ``language`` mirror the cache-key knobs used elsewhere; they
    default to the universal defaults so callers that do not vary style can
    omit them.
    """

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    from_: DocumentIdentity = Field(alias="from")
    to: DocumentIdentity
    tone: str = "neutral"
    language: str = "auto"


class InsightPromoteResponse(BaseModel):
    """Body of a 200 response from ``POST /ai/v1/insights/promote`` (PR-ζ)."""

    promoted: bool
    insight_id: int | None = None
    already_promoted: bool = False


class InsightSyncCursor(BaseModel):
    """Tuple cursor for ``GET /ai/v1/insights/sync`` pagination (PR-η, Lock #23).

    The cursor is the ``(generated_at, id)`` pair of the LAST item returned
    in the previous page. Strict ``>`` comparison on the next call:
    ``(row.generated_at, row.id) > (since_ts, since_id)``.
    """

    model_config = ConfigDict(extra="forbid")

    generated_at: str  # ISO-8601 UTC
    id: int


class InsightSyncItem(BaseModel):
    """One entry of ``InsightSyncResponse.items``."""

    model_config = ConfigDict(extra="forbid")

    id: int  # server-side BookInsight.id; clients persist it for cursor reconstruction
    identity: DocumentIdentity
    payload: BookInsightPayload
    sources: list[Citation]
    model_id: str
    prompt_version: str
    schema_version: int
    tone: str
    language: str
    generated_at: str  # ISO-8601 UTC


class InsightSyncResponse(BaseModel):
    """Body of ``GET /ai/v1/insights/sync`` (PR-η)."""

    model_config = ConfigDict(extra="forbid")

    items: list[InsightSyncItem]
    server_time: str  # ISO-8601 UTC
    next_cursor: InsightSyncCursor | None = None


class InsightRegenerateBody(BaseModel):
    """Force a fresh generation, marking the existing row as superseded.

    `reason` is appended to the user prompt so the model knows what to fix.
    Rate-limited harder than regular lookup (AI_REGEN_DAILY_LIMIT per user/day).
    """

    identity: DocumentIdentity
    bundle: MetadataBundle
    reason: str = Field(min_length=1, max_length=500)


class AiStyle(BaseModel):
    """User-facing personalization. `tone` and `language` are cache-key knobs:
    they participate in the cache key (columns `book_insights.tone` and
    `book_insights.language`), so users with different combinations get
    separately-cached generations rather than one bleeding into the other.

    `language="auto"` is the universal default and emits no language clause in
    the prompt — preserves pre-PR4 behavior byte-for-byte. Any other value must
    be a lowercase ISO 639-1 code (e.g. `"en"`, `"it"`, `"zh"`).
    """

    model_config = ConfigDict(extra="forbid")

    tone: Literal["neutral", "enthusiastic", "scholarly", "casual"] = "neutral"
    language: str = "auto"

    @field_validator("language")
    @classmethod
    def _validate_language(cls, v: str) -> str:
        if v == "auto":
            return v
        if v not in ISO_639_1_CODES:
            raise ValueError(
                "language must be 'auto' or a lowercase ISO 639-1 code (e.g. 'en', 'it')"
            )
        return v


class ConfigResponse(BaseModel):
    configured: bool
    base_url_host: str | None = None
    model_id: str | None = None
    sources_enabled: list[str]
    daily_budget: int  # echoes AI_DAILY_BUDGET so the app can show "X/Y today"
    regen_daily_limit: int
    # PR-η / Lock #24: runtime-resolved PROMPT_VERSION (post-PR-ε sentinel
    # resolution). The Android client reads this so its local-cache PK can
    # invalidate correctly on server-side prompt bumps. Older deploys that
    # do not emit the field decode safely on the Android side because the
    # DTO's default is "1" (the legacy sentinel).
    prompt_version: str = "1"


class PreferencesResponse(BaseModel):
    ai_enabled: bool
    style: AiStyle


class PreferencesBody(BaseModel):
    """PUT body. Both fields optional so the app can update one without touching the other."""

    ai_enabled: bool | None = None
    style: AiStyle | None = None


class QuotaResponse(BaseModel):
    """Body of the 429 response so the app can show a useful message."""

    used: int
    limit: int
    resets_at: str  # ISO-8601, next UTC midnight


class RetrievalSourceHealth(BaseModel):
    """One row in ``AiHealthResponse.retrieval_sources``.

    Tri-state ``reachable``:
      * ``null`` — never observed by this process (fresh start, or no
        lookup has ever consulted this source).
      * ``true`` — last HTTP call to this source completed (any status code).
      * ``false`` — last attempt failed at the transport level (timeout, DNS,
        connection reset, etc.).
    """

    name: str
    reachable: bool | None = None
    last_checked_at: str | None = None  # ISO-8601


class AiHealthResponse(BaseModel):
    """Body of ``GET /ai/v1/health``.

    Process-local snapshot of the most recently observed reachability of the
    AI provider and configured retrieval sources. Reset to all-null on
    process restart. Multi-replica deployments report per-replica state.

    Field semantics:
      * ``provider_reachable`` — tri-state, same shape as
        ``RetrievalSourceHealth.reachable``.
      * ``provider_last_checked_at`` — non-null whenever
        ``provider_reachable`` is non-null.
      * ``model_id`` — most recently observed model on a successful chat
        completion (NOT the configured ``AI_MODEL``; see ``/ai/v1/config``
        for that). Null until the first success.
      * ``last_failure_at`` / ``last_failure_class`` — set when
        ``provider_reachable=false``; cleared on the next success.
    """

    provider_reachable: bool | None = None
    provider_last_checked_at: str | None = None  # ISO-8601
    model_id: str | None = None
    last_failure_at: str | None = None  # ISO-8601
    last_failure_class: str | None = None
    retrieval_sources: list[RetrievalSourceHealth] = Field(default_factory=list)
