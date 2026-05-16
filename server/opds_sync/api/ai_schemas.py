"""Pydantic schemas for the /ai/v1 surface and the persisted insight payload.

The structured BookInsight schema is also passed to the LLM as a JSON Schema
in the `response_format` of the chat completion request, so its shape is
load-bearing on both ends.
"""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

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


class BookInsightPayload(BaseModel):
    """The structured body of a book insight. Stored verbatim in book_insights.payload.

    Field order is the reading order for BookDetailScreen — the model is
    instructed to generate keys in declared order, which keeps the streaming
    narrative coherent (intro before analysis, etc.).
    """

    model_config = ConfigDict(extra="forbid")

    intro: str | None = None
    author: AuthorInsight | None = None
    series: SeriesInsight | None = None
    analysis: str | None = None
    content_warnings: list[str] | None = None
    confidence: Literal["high", "medium", "low"] = "low"
    schema_version: int = 2


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
