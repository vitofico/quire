"""Pydantic schemas for the /ai/v1 surface and the persisted insight payload.

The structured BookInsight schema is also passed to the LLM as a JSON Schema
in the `response_format` of the chat completion request, so its shape is
load-bearing on both ends.
"""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class DocumentIdentity(BaseModel):
    """Mirrors api.progress.DocumentIdentity. Keep separate to avoid cross-router import."""

    metadata_id: str | None = None
    content_hash: str


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
    bio: str | None = None
    notable_works: list[str] | None = None
    nationality: str | None = None
    active_years: str | None = None


class SeriesInsight(BaseModel):
    name: str
    position: int | None = None
    total_known: int | None = None


class BookInsightPayload(BaseModel):
    """The structured body of a book insight. Stored verbatim in book_insights.payload."""

    model_config = ConfigDict(extra="forbid")  # tighten the model contract

    schema_version: int = 1
    summary: str | None = None
    author: AuthorInsight | None = None
    series: SeriesInsight | None = None
    themes: list[str] | None = None
    tone: str | None = None
    content_advisory: list[str] | None = None
    suggested_for: str | None = None
    confidence: Literal["high", "medium", "low"] = "low"
    notes: str | None = None


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
    """User-facing personalization knobs. Deliberately small; extend via JSON migration-free.

    `interests` is a free-form list (e.g. ["themes", "writing_style", "historical_context",
    "comparable_books"]). The prompt composer turns it into a short "focus on …" line.
    """

    model_config = ConfigDict(extra="forbid")

    tone: Literal["neutral", "enthusiastic", "scholarly", "casual"] = "neutral"
    length: Literal["brief", "standard", "deep"] = "standard"
    author_focus: Literal["none", "moderate", "detailed"] = "moderate"
    include_spoilers: bool = False
    interests: list[str] = Field(default_factory=lambda: ["themes", "writing_style"])


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
