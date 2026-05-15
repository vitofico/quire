# Quire AI Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the AI substrate in opds-sync plus book-insights v1 on Android. End state: an opted-in user opens a book in Quire and sees a server-generated, structured insight (summary, author, series, themes, sources) cached on first generation and shared across all of that user's devices and other users on the same instance.

**Architecture:** All AI calls originate in opds-sync. The server has an OpenAI-compatible HTTP client (works with Ollama, vLLM, llama.cpp, OpenAI, Anthropic, OpenRouter, …), pre-prompt deterministic retrieval from Wikipedia and OpenLibrary, structured-JSON output validation, content-addressed cache keyed on `(metadata_id|content_hash, model_id, prompt_version)`, and per-user opt-in. Android adds two modules (`:core:metadata` for OPF extraction, `:data:ai` for the REST client), a Settings AI section with disclosure copy, and a new `BookDetailScreen` between library and reader where insight cards live.

**Tech Stack:**
- *Server:* Python 3.12, FastAPI, SQLAlchemy 2 async + Alembic, Pydantic v2, httpx (already a dep), pytest + testcontainers (already wired).
- *Android:* Kotlin 2.0, Jetpack Compose, OkHttp + Retrofit-style hand-rolled JSON-over-OkHttp (matching `:data:sync`), kotlinx-serialization, JUnit 4 + Truth + MockWebServer.
- *Build/test:* server tests via `pytest` from `server/`. Android via `scripts/dgradle <task>` from repo root.

**Spec:** `docs/superpowers/specs/2026-05-09-quire-ai-design.md`

**Branch / worktree:** Work on `worktree-feat+quire-ai` (or your own feature branch off `main`). Spec is committed at `558dced`.

**Conventional-commits format:** `:emoji: type: subject` matching `main`. Use `:sparkles: feat:`, `:white_check_mark: test:`, `:memo: docs:`, `:wrench: chore:`. Pre-commit hooks: `ruff` will run on Python files.

**Out of scope (Phase 2 & 3, separate plans):**
- Library intelligence (reader profile, recommendations endpoints + UI).
- Paragraph Q&A and AI-assisted notes.
- General web search via tool-calling.
- Per-user AI provider keys.

---

## File Structure

### Server (`server/`)

| File | Status | Responsibility |
|---|---|---|
| `opds_sync/config.py` | modify | Add AI env vars (`AI_ENABLED`, `AI_BASE_URL`, `AI_API_KEY`, `AI_MODEL`, `AI_TIMEOUT_S`, `AI_MAX_CONCURRENCY`, `AI_SOURCES`, `AI_RETRIEVAL_TIMEOUT_S`, `AI_PROMPT_VERSION`, `AI_RATE_PER_MIN`, `AI_DAILY_BUDGET`, `AI_REGEN_DAILY_LIMIT`). |
| `opds_sync/db/models.py` | modify | Add `BookInsight`, `UserAIPreference`, `ExternalSourceCacheEntry`, `AIUsageDaily` SQLAlchemy models. |
| `migrations/versions/0002_ai_tables.py` | new | Alembic migration for the four tables, indexes, partial unique constraint. |
| `opds_sync/core/ai/__init__.py` | new | Package marker. |
| `opds_sync/core/ai/client.py` | new | `AIClient` — OpenAI-compatible chat completions with structured output + one validation retry. |
| `opds_sync/core/ai/retrieval.py` | new | `lookup_wikipedia`, `lookup_openlibrary`, cache helpers, `Citation` model. |
| `opds_sync/core/ai/prompts.py` | new | System prompt + user-prompt composer. `PROMPT_VERSION` constant. |
| `opds_sync/core/ai/service.py` | new | `InsightOrchestrator` — cache lookup + alias reconciliation + lock + semaphore + persist. |
| `opds_sync/api/ai.py` | new | Router with all 6 endpoints. |
| `opds_sync/api/ai_schemas.py` | new | Pydantic DTOs for the AI surface. |
| `opds_sync/main.py` | modify | Build AI client + orchestrator at startup; mount `/ai/v1` router. |
| `tests/unit/test_ai_client.py` | new | AIClient tests (httpx MockTransport). |
| `tests/unit/test_ai_retrieval.py` | new | Retrieval tests (httpx MockTransport, cache, fallback). |
| `tests/unit/test_ai_prompts.py` | new | Prompt composition tests. |
| `tests/integration/test_ai_endpoints.py` | new | End-to-end tests against testcontainers + a fake AI provider. |

### Android (in-repo)

| File | Status | Responsibility |
|---|---|---|
| `settings.gradle.kts` | modify | Add `:core:metadata` and `:data:ai` to module list. |
| `core/metadata/build.gradle.kts` | new | Kotlin JVM module, depends on `:core:identity` and `:core:model`. |
| `core/metadata/src/main/java/io/theficos/ereader/core/metadata/MetadataBundle.kt` | new | Data class. |
| `core/metadata/src/main/java/io/theficos/ereader/core/metadata/OpfMetadataExtractor.kt` | new | Pure extractor; reuses the OPF parsing already in `:core:identity`. |
| `core/metadata/src/test/java/io/theficos/ereader/core/metadata/OpfMetadataExtractorTest.kt` | new | Fixture-driven unit tests. |
| `data/ai/build.gradle.kts` | new | Android Library module, depends on `:core:model`, `:core:metadata`, `:auth`, OkHttp, kotlinx-serialization. |
| `data/ai/src/main/java/io/theficos/ereader/data/ai/AiDtos.kt` | new | Wire-format DTOs mirroring `ai_schemas.py`. |
| `data/ai/src/main/java/io/theficos/ereader/data/ai/AiClient.kt` | new | OkHttp-backed client for `/ai/v1/*`. |
| `data/ai/src/test/java/io/theficos/ereader/data/ai/AiClientTest.kt` | new | MockWebServer-backed tests. |
| `app/build.gradle.kts` | modify | Add `implementation(project(":data:ai"))`, `implementation(project(":core:metadata"))`. |
| `app/src/main/java/io/theficos/ereader/di/AppContainer.kt` | modify | Construct `AiClient`, expose it; pass `OkHttpClient` from sync stack. |
| `app/src/main/java/io/theficos/ereader/data/ai/AiRepository.kt` | new | Caches `AiConfig` + `Preferences` in memory; exposes flows. |
| `app/src/main/java/io/theficos/ereader/ui/settings/SettingsScreen.kt` | modify | New "AI features" section. |
| `app/src/main/java/io/theficos/ereader/ui/settings/SettingsViewModel.kt` | modify | AI state + toggle handler. |
| `app/src/main/java/io/theficos/ereader/ui/bookdetail/BookDetailScreen.kt` | new | New screen between library and reader. |
| `app/src/main/java/io/theficos/ereader/ui/bookdetail/BookDetailViewModel.kt` | new | Loads book + insights; triggers AI call. |
| `app/src/main/java/io/theficos/ereader/ui/bookdetail/InsightCards.kt` | new | Composables for summary/author/series/themes/sources. |
| `app/src/main/java/io/theficos/ereader/ui/AppNavGraph.kt` | modify | New `book/{id}` route between library and `reader/{id}`. |
| `app/src/main/java/io/theficos/ereader/ui/library/LibraryScreen.kt` | modify | `onOpenBook` now navigates to book detail, not directly to reader. |
| `data/local/src/main/java/io/theficos/ereader/data/local/db/DocumentEntity.kt` | modify | Optionally store the metadata bundle JSON when known (or expose a side table — see Task 13.5). |

### Docs

| File | Status | Responsibility |
|---|---|---|
| `README.md` | modify | Update the "two destinations" sentence; add an "AI features (optional)" section. |
| `docs/sync-api.md` | modify | Document the `/ai/v1/*` endpoints. |
| `fastlane/metadata/android/en-US/full_description.txt` | modify | Note AI features as optional, off by default. |

---

## Build/test commands cheat sheet

| Want | Command |
|---|---|
| Server unit tests | `cd server && pytest tests/unit -v` |
| Server integration tests | `cd server && pytest tests/integration -v` |
| Server format/lint | `cd server && ruff check . && ruff format .` |
| One Android module's tests | `scripts/dgradle :core:metadata:test` |
| All Android tests | `scripts/dgradle test` |
| Build debug APK | `scripts/dgradle :app:assembleDebug` |

`scripts/dgradle` runs Gradle inside the project's Docker image; never use the host `./gradlew` (it produces non-reproducible outputs and needs the F-Droid toolchain).

---

## Task 1: Add AI settings to `Settings`

**Why first:** Every later task imports from `config.py`. No test infra needed; the `Settings` class is type-driven and exercised indirectly by every endpoint test.

**Files:**
- Modify: `server/opds_sync/config.py`
- Test: covered indirectly by Task 12 integration tests (no dedicated test).

- [ ] **Step 1: Edit `server/opds_sync/config.py`**

Replace the `Settings` class with the version below (additions only; existing fields preserved):

```python
from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="OPDS_SYNC_", env_file=".env", extra="ignore")

    database_url: str = "postgresql+asyncpg://postgres:postgres@localhost:5432/opds_sync"
    cwa_base_url: str = "http://calibre-web.calibre-web.svc.cluster.local:8083"
    cwa_probe_path: str = "/opds"
    cwa_probe_timeout_s: float = 3.0
    auth_cache_positive_ttl_s: int = 60
    auth_cache_negative_ttl_s: int = 10
    auth_cache_max_entries: int = 1024
    log_level: str = "INFO"

    # AI substrate (Phase 1)
    ai_enabled: bool = False
    ai_base_url: str | None = None
    ai_api_key: str | None = None
    ai_model: str | None = None
    ai_timeout_s: float = 120.0
    ai_max_concurrency: int = 4
    ai_sources: str = "wikipedia,openlibrary"  # CSV; "" disables retrieval
    ai_retrieval_timeout_s: float = 8.0
    ai_prompt_version: str = "1"

    # Quota protection — important when AI_BASE_URL points at a metered/cloud provider
    # (Ollama Cloud subscription, OpenAI, Anthropic, OpenRouter, …). Free-tier Ollama
    # Cloud burns quota the same as a paid API.
    ai_rate_per_min: int = 10          # process-wide token bucket against AI_BASE_URL
    ai_daily_budget: int = 200         # generations per user per UTC day; 0 disables
    ai_regen_daily_limit: int = 3      # tighter ceiling for /insights/regenerate per user/day


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
```

- [ ] **Step 2: Sanity check**

Run: `cd server && python -c "from opds_sync.config import get_settings; s = get_settings(); print(s.ai_enabled, s.ai_max_concurrency, s.ai_rate_per_min, s.ai_daily_budget)"`
Expected: `False 4 10 200`

- [ ] **Step 3: Commit**

```bash
git add server/opds_sync/config.py
git commit -m ":sparkles: feat(server): add AI substrate settings"
```

---

## Task 2: Alembic migration for the four AI tables

**Why now:** Models in Task 3 are easier to write once the migration shape is agreed.

**Files:**
- Create: `server/migrations/versions/0002_ai_tables.py`
- Test: covered by `test_schema.py` extension in Task 3.

- [ ] **Step 1: Create the migration**

```python
"""ai tables: book_insights, user_ai_preferences, external_source_cache

Revision ID: 0002
Revises: 0001
Create Date: 2026-05-09 00:00:00.000000
"""

import sqlalchemy as sa
from alembic import op

revision = "0002"
down_revision = "0001"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "book_insights",
        sa.Column("id", sa.BigInteger(), primary_key=True, autoincrement=True),
        sa.Column("metadata_id", sa.String(), nullable=True),
        sa.Column("content_hash", sa.String(), nullable=False),
        sa.Column("model_id", sa.String(), nullable=False),
        sa.Column("prompt_version", sa.String(), nullable=False),
        sa.Column("sources_used", sa.ARRAY(sa.String()), nullable=False),
        sa.Column("payload", sa.JSON(), nullable=False),
        sa.Column("sources", sa.JSON(), nullable=False),
        sa.Column(
            "generated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column("generated_by", sa.String(), nullable=False),
        # Regeneration lineage. When a user requests a re-do via /insights/regenerate
        # with a `reason`, the previous row is kept (auditable, rollback-friendly) but
        # marked `superseded_at`. The fresh row records the previous id chain in
        # `previous_insight_ids` so we can show "v2 of 3" in the UI if we ever want to.
        sa.Column("superseded_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("previous_insight_ids", sa.JSON(), nullable=True),
        sa.UniqueConstraint(
            "content_hash",
            "model_id",
            "prompt_version",
            name="uq_book_insights_content_hash_model_prompt",
        ),
    )
    # Partial unique index: metadata_id is nullable but where present must be unique per (model, prompt).
    # Only the live (non-superseded) row counts; superseded rows are history.
    op.create_index(
        "uq_book_insights_metadata_id_model_prompt",
        "book_insights",
        ["metadata_id", "model_id", "prompt_version"],
        unique=True,
        postgresql_where=sa.text("metadata_id IS NOT NULL AND superseded_at IS NULL"),
    )
    op.create_index(
        "ix_book_insights_content_hash",
        "book_insights",
        ["content_hash"],
        postgresql_where=sa.text("superseded_at IS NULL"),
    )
    op.create_index(
        "ix_book_insights_metadata_id",
        "book_insights",
        ["metadata_id"],
        postgresql_where=sa.text("metadata_id IS NOT NULL AND superseded_at IS NULL"),
    )

    op.create_table(
        "user_ai_preferences",
        sa.Column("user_id", sa.String(), primary_key=True),
        sa.Column("ai_enabled", sa.Boolean(), nullable=False, server_default=sa.text("false")),
        # Free-form style/personalization preferences. Nullable: defaults applied in code so
        # we can iterate on the shape without migration churn. See ai_schemas.AiStyle.
        sa.Column("style", sa.JSON(), nullable=True),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
    )

    op.create_table(
        "external_source_cache",
        sa.Column("source", sa.String(), nullable=False),
        sa.Column("key", sa.String(), nullable=False),
        sa.Column("payload", sa.JSON(), nullable=False),
        sa.Column(
            "fetched_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.PrimaryKeyConstraint("source", "key", name="pk_external_source_cache"),
    )

    # Per-user, per-UTC-day counter for the AI_DAILY_BUDGET gate. Incremented on every
    # successful generation (lookup miss → generate, or regenerate). Cache hits don't
    # count. Cleaned up lazily; old rows are harmless.
    op.create_table(
        "ai_usage_daily",
        sa.Column("user_id", sa.String(), nullable=False),
        sa.Column("day", sa.Date(), nullable=False),
        sa.Column("count", sa.Integer(), nullable=False, server_default=sa.text("0")),
        sa.Column("regen_count", sa.Integer(), nullable=False, server_default=sa.text("0")),
        sa.PrimaryKeyConstraint("user_id", "day", name="pk_ai_usage_daily"),
    )


def downgrade() -> None:
    op.drop_table("ai_usage_daily")
    op.drop_table("external_source_cache")
    op.drop_table("user_ai_preferences")
    op.drop_index("ix_book_insights_metadata_id", table_name="book_insights")
    op.drop_index("ix_book_insights_content_hash", table_name="book_insights")
    op.drop_index("uq_book_insights_metadata_id_model_prompt", table_name="book_insights")
    op.drop_table("book_insights")
```

- [ ] **Step 2: Verify migration applies cleanly**

Run: `cd server && pytest tests/integration/test_schema.py -v`
Expected: PASS (testcontainers boots Postgres, alembic upgrades through 0002).

If `test_schema.py` asserts on the exact set of tables, extend it to include the new ones (likely needed):

```python
# Look at the existing test; if it has an `expected_tables = {...}` set, add:
"book_insights", "user_ai_preferences", "external_source_cache", "ai_usage_daily"
```

- [ ] **Step 3: Commit**

```bash
git add server/migrations/versions/0002_ai_tables.py server/tests/integration/test_schema.py
git commit -m ":sparkles: feat(server): migration for AI tables"
```

---

## Task 3: SQLAlchemy models for AI tables

**Files:**
- Modify: `server/opds_sync/db/models.py`
- Test: covered by Task 12 integration.

- [ ] **Step 1: Append to `server/opds_sync/db/models.py`**

```python
from datetime import date
from sqlalchemy import ARRAY, JSON, Boolean, Date, Integer

class BookInsight(Base):
    __tablename__ = "book_insights"
    # All uniqueness/indexes for this table are PARTIAL (depend on `superseded_at`).
    # They live in the Alembic migration only — partial indexes can't be expressed
    # declaratively on the model.

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    metadata_id: Mapped[str | None] = mapped_column(String, nullable=True)
    content_hash: Mapped[str] = mapped_column(String, nullable=False)
    model_id: Mapped[str] = mapped_column(String, nullable=False)
    prompt_version: Mapped[str] = mapped_column(String, nullable=False)
    sources_used: Mapped[list[str]] = mapped_column(ARRAY(String), nullable=False)
    payload: Mapped[dict] = mapped_column(JSON, nullable=False)
    sources: Mapped[list[dict]] = mapped_column(JSON, nullable=False)
    generated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    generated_by: Mapped[str] = mapped_column(String, nullable=False)
    superseded_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    previous_insight_ids: Mapped[list[int] | None] = mapped_column(JSON, nullable=True)


class UserAIPreference(Base):
    __tablename__ = "user_ai_preferences"

    user_id: Mapped[str] = mapped_column(String, primary_key=True)
    ai_enabled: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    # Persisted as JSON; defaults live in api/ai_schemas.AiStyle so the migration
    # never needs to change when we add a new knob.
    style: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )


class ExternalSourceCacheEntry(Base):
    __tablename__ = "external_source_cache"

    source: Mapped[str] = mapped_column(String, primary_key=True)
    key: Mapped[str] = mapped_column(String, primary_key=True)
    payload: Mapped[dict] = mapped_column(JSON, nullable=False)
    fetched_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )


class AIUsageDaily(Base):
    __tablename__ = "ai_usage_daily"

    user_id: Mapped[str] = mapped_column(String, primary_key=True)
    day: Mapped[date] = mapped_column(Date, primary_key=True)
    count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    regen_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
```

The partial unique/filter indexes AND the partial unique constraint on `book_insights` are created by the migration only — they can't be expressed declaratively on the model.

- [ ] **Step 2: Sanity check imports**

Run: `cd server && python -c "from opds_sync.db.models import BookInsight, UserAIPreference, ExternalSourceCacheEntry, AIUsageDaily; print('ok')"`
Expected: `ok`

- [ ] **Step 3: Commit**

```bash
git add server/opds_sync/db/models.py
git commit -m ":sparkles: feat(server): SQLAlchemy models for AI tables"
```

---

## Task 4: AI Pydantic schemas

**Files:**
- Create: `server/opds_sync/api/ai_schemas.py`
- Test: `server/tests/unit/test_ai_schemas.py` (light — round-trip + defaults)

- [ ] **Step 1: Create `server/opds_sync/api/ai_schemas.py`**

```python
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
    daily_budget: int                   # echoes AI_DAILY_BUDGET so the app can show "X/Y today"
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
```

- [ ] **Step 2: Create `server/tests/unit/test_ai_schemas.py`**

```python
import pytest
from pydantic import ValidationError

from opds_sync.api.ai_schemas import (
    BookInsightPayload,
    DocumentIdentity,
    InsightLookupBody,
    MetadataBundle,
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
        InsightLookupBody.model_validate({"identity": {"metadata_id": "x"}, "bundle": {"title": "y"}})


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
        InsightRegenerateBody.model_validate({
            "identity": {"content_hash": "abc"},
            "bundle": {"title": "y"},
            "reason": "",
        })
```

- [ ] **Step 3: Run tests**

Run: `cd server && pytest tests/unit/test_ai_schemas.py -v`
Expected: 7 PASS.

- [ ] **Step 4: Commit**

```bash
git add server/opds_sync/api/ai_schemas.py server/tests/unit/test_ai_schemas.py
git commit -m ":sparkles: feat(server): AI Pydantic schemas"
```

---

## Task 5: AI client (OpenAI-compatible chat completions)

**Files:**
- Create: `server/opds_sync/core/ai/__init__.py` (empty)
- Create: `server/opds_sync/core/ai/client.py`
- Create: `server/tests/unit/test_ai_client.py`

- [ ] **Step 1: Create the package marker**

```bash
touch server/opds_sync/core/ai/__init__.py
```

- [ ] **Step 2: Write the failing test FIRST (`server/tests/unit/test_ai_client.py`)**

```python
import json

import httpx
import pytest

from opds_sync.api.ai_schemas import BookInsightPayload
from opds_sync.core.ai.client import AIClient, ProviderParseError, ProviderTimeout


def _make_chat_response(content: str) -> dict:
    return {
        "id": "x",
        "choices": [
            {"index": 0, "message": {"role": "assistant", "content": content}, "finish_reason": "stop"}
        ],
        "model": "test-model",
    }


@pytest.mark.asyncio
async def test_chat_structured_returns_validated_payload():
    payload = {
        "schema_version": 1,
        "summary": "A foundational sci-fi novel.",
        "confidence": "high",
    }
    handler = httpx.MockTransport(
        lambda req: httpx.Response(200, json=_make_chat_response(json.dumps(payload)))
    )
    client = AIClient(
        base_url="http://fake/v1",
        api_key="k",
        model="test-model",
        transport=handler,
    )
    result = await client.chat_structured(
        system="sys",
        user="usr",
        schema=BookInsightPayload,
        timeout_s=5.0,
    )
    assert isinstance(result, BookInsightPayload)
    assert result.summary == "A foundational sci-fi novel."


@pytest.mark.asyncio
async def test_chat_structured_retries_once_on_validation_error():
    bad = {"schema_version": 1, "summary": 42}  # summary must be str|None
    good = {"schema_version": 1, "summary": "ok", "confidence": "low"}
    seen: list[str] = []

    def handler(req: httpx.Request) -> httpx.Response:
        body = json.loads(req.content)
        seen.append(body["messages"][-1]["content"])  # remember the last user-side message
        content = json.dumps(bad if len(seen) == 1 else good)
        return httpx.Response(200, json=_make_chat_response(content))

    client = AIClient(
        base_url="http://fake/v1",
        api_key=None,
        model="m",
        transport=httpx.MockTransport(handler),
    )
    out = await client.chat_structured(system="s", user="u", schema=BookInsightPayload, timeout_s=5.0)
    assert out.summary == "ok"
    assert len(seen) == 2
    assert "validation" in seen[1].lower()


@pytest.mark.asyncio
async def test_chat_structured_raises_after_two_validation_failures():
    bad = {"schema_version": 1, "summary": 42}
    handler = httpx.MockTransport(
        lambda req: httpx.Response(200, json=_make_chat_response(json.dumps(bad)))
    )
    client = AIClient(base_url="http://fake/v1", api_key=None, model="m", transport=handler)
    with pytest.raises(ProviderParseError):
        await client.chat_structured(system="s", user="u", schema=BookInsightPayload, timeout_s=5.0)


@pytest.mark.asyncio
async def test_chat_structured_translates_timeout():
    def handler(req: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("boom")

    client = AIClient(
        base_url="http://fake/v1",
        api_key=None,
        model="m",
        transport=httpx.MockTransport(handler),
    )
    with pytest.raises(ProviderTimeout):
        await client.chat_structured(system="s", user="u", schema=BookInsightPayload, timeout_s=0.5)


@pytest.mark.asyncio
async def test_authorization_header_sent_when_key_present():
    seen: dict = {}

    def handler(req: httpx.Request) -> httpx.Response:
        seen["auth"] = req.headers.get("Authorization")
        return httpx.Response(
            200,
            json=_make_chat_response(json.dumps({"schema_version": 1, "confidence": "low"})),
        )

    client = AIClient(
        base_url="http://fake/v1",
        api_key="sk-abc",
        model="m",
        transport=httpx.MockTransport(handler),
    )
    await client.chat_structured(system="s", user="u", schema=BookInsightPayload, timeout_s=5.0)
    assert seen["auth"] == "Bearer sk-abc"


@pytest.mark.asyncio
async def test_no_auth_header_when_key_absent():
    seen: dict = {}

    def handler(req: httpx.Request) -> httpx.Response:
        seen["auth"] = req.headers.get("Authorization")
        return httpx.Response(
            200,
            json=_make_chat_response(json.dumps({"schema_version": 1, "confidence": "low"})),
        )

    client = AIClient(base_url="http://fake/v1", api_key=None, model="m", transport=httpx.MockTransport(handler))
    await client.chat_structured(system="s", user="u", schema=BookInsightPayload, timeout_s=5.0)
    assert seen["auth"] is None
```

- [ ] **Step 3: Run the test (expect failure)**

Run: `cd server && pytest tests/unit/test_ai_client.py -v`
Expected: ImportError (`AIClient` not defined yet).

- [ ] **Step 4: Implement `server/opds_sync/core/ai/client.py`**

```python
"""OpenAI-compatible chat-completions client with structured-output validation.

Works against any provider that speaks the OpenAI chat-completions JSON shape:
OpenAI itself, Ollama (post-0.4), vLLM, llama.cpp's `--api`, OpenRouter,
Anthropic via OpenAI-compat proxies, etc.

Strategy:
1. Send chat completion with `response_format = {"type": "json_object"}`. We
   don't depend on `json_schema` mode because Ollama/llama.cpp don't all
   support it; we instead inline the schema in the system prompt.
2. Parse the assistant message as JSON, then validate against the Pydantic
   schema.
3. On ValidationError, retry once with the validation error appended to the
   user message.
4. On second failure or non-JSON output, raise ProviderParseError.
"""

from __future__ import annotations

import json
import logging
from typing import TypeVar

import httpx
from pydantic import BaseModel, ValidationError

logger = logging.getLogger(__name__)

T = TypeVar("T", bound=BaseModel)


class ProviderError(Exception):
    """Base for AI provider failures."""


class ProviderUnreachable(ProviderError):
    pass


class ProviderTimeout(ProviderError):
    pass


class ProviderParseError(ProviderError):
    pass


class AIClient:
    def __init__(
        self,
        *,
        base_url: str,
        api_key: str | None,
        model: str,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._api_key = api_key
        self._model = model
        self._transport = transport  # tests inject MockTransport; prod is None
        self._user_agent = "opds-sync"

    async def chat_structured(
        self,
        *,
        system: str,
        user: str,
        schema: type[T],
        timeout_s: float,
    ) -> T:
        schema_text = json.dumps(schema.model_json_schema(), indent=2)
        full_system = (
            f"{system}\n\n"
            "You MUST respond with a single JSON object that conforms exactly to "
            "the following JSON Schema. No prose, no markdown, no code fences.\n\n"
            f"```\n{schema_text}\n```"
        )
        messages = [
            {"role": "system", "content": full_system},
            {"role": "user", "content": user},
        ]

        async with self._build_client(timeout_s) as http:
            response_text = await self._do_call(http, messages)
            try:
                return self._parse(response_text, schema)
            except (json.JSONDecodeError, ValidationError) as first_err:
                logger.info("ai.client.validation_retry err=%s", first_err)
                retry_messages = list(messages)
                retry_messages.append({"role": "assistant", "content": response_text})
                retry_messages.append(
                    {
                        "role": "user",
                        "content": (
                            "The previous response failed validation against the schema. "
                            f"Validation error: {first_err}. Reply again with a valid JSON "
                            "object that conforms exactly to the schema. Output only JSON."
                        ),
                    }
                )
                retry_text = await self._do_call(http, retry_messages)
                try:
                    return self._parse(retry_text, schema)
                except (json.JSONDecodeError, ValidationError) as second_err:
                    raise ProviderParseError(
                        f"Validation failed twice; last error: {second_err}"
                    ) from second_err

    def _build_client(self, timeout_s: float) -> httpx.AsyncClient:
        headers = {"User-Agent": self._user_agent, "Content-Type": "application/json"}
        if self._api_key:
            headers["Authorization"] = f"Bearer {self._api_key}"
        kwargs = {
            "headers": headers,
            "timeout": httpx.Timeout(timeout_s, connect=min(timeout_s, 10.0)),
        }
        if self._transport is not None:
            kwargs["transport"] = self._transport
        return httpx.AsyncClient(**kwargs)

    async def _do_call(self, http: httpx.AsyncClient, messages: list[dict]) -> str:
        body = {
            "model": self._model,
            "messages": messages,
            "response_format": {"type": "json_object"},
            "temperature": 0.2,
            "stream": False,
        }
        try:
            r = await http.post(f"{self._base_url}/chat/completions", json=body)
        except httpx.TimeoutException as e:
            raise ProviderTimeout(str(e)) from e
        except httpx.HTTPError as e:
            raise ProviderUnreachable(str(e)) from e

        if r.status_code >= 500:
            raise ProviderUnreachable(f"provider {r.status_code}: {r.text[:200]}")
        if r.status_code >= 400:
            raise ProviderParseError(f"provider {r.status_code}: {r.text[:200]}")

        data = r.json()
        choices = data.get("choices") or []
        if not choices:
            raise ProviderParseError("no choices in provider response")
        message = choices[0].get("message") or {}
        content = message.get("content")
        if not isinstance(content, str):
            raise ProviderParseError("provider returned non-string message content")
        return content

    @staticmethod
    def _parse(text: str, schema: type[T]) -> T:
        return schema.model_validate_json(text.strip())
```

- [ ] **Step 5: Run tests**

Run: `cd server && pytest tests/unit/test_ai_client.py -v`
Expected: 6 PASS.

- [ ] **Step 6: Commit**

```bash
git add server/opds_sync/core/ai/__init__.py server/opds_sync/core/ai/client.py server/tests/unit/test_ai_client.py
git commit -m ":sparkles: feat(server): OpenAI-compatible AI client with validation retry"
```

---

## Task 6: External-source retrieval (Wikipedia + OpenLibrary)

**Files:**
- Create: `server/opds_sync/core/ai/retrieval.py`
- Create: `server/tests/unit/test_ai_retrieval.py`

- [ ] **Step 1: Failing test for Wikipedia (`tests/unit/test_ai_retrieval.py`)**

```python
import json
from datetime import UTC, datetime, timedelta

import httpx
import pytest
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from opds_sync.core.ai.retrieval import (
    Retriever,
    _normalize_key,
)
from opds_sync.db.models import ExternalSourceCacheEntry


def _wiki_summary_response(title: str, extract: str) -> dict:
    return {
        "type": "standard",
        "title": title,
        "extract": extract,
        "content_urls": {"desktop": {"page": f"https://en.wikipedia.org/wiki/{title}"}},
    }


def _ol_search_response(works: list[dict]) -> dict:
    return {"docs": works}


@pytest.mark.asyncio
async def test_normalize_key_collapses_whitespace_and_lowercases():
    assert _normalize_key("  Isaac   Asimov ") == "isaac asimov"


@pytest.mark.asyncio
async def test_lookup_wikipedia_hits_cache_after_first_call(session: AsyncSession):
    calls: list[str] = []

    def handler(req: httpx.Request) -> httpx.Response:
        calls.append(str(req.url))
        return httpx.Response(
            200, json=_wiki_summary_response("Foundation_(novel)", "Foundation is a 1951 novel.")
        )

    r = Retriever(
        session=session,
        transport=httpx.MockTransport(handler),
        timeout_s=5.0,
    )
    cites1 = await r.lookup_wikipedia(author="Isaac Asimov", title="Foundation")
    cites2 = await r.lookup_wikipedia(author="Isaac Asimov", title="Foundation")
    assert len(cites1) >= 1
    assert cites1 == cites2
    assert len(calls) == 1  # second call hit cache

    rows = (await session.execute(select(ExternalSourceCacheEntry))).scalars().all()
    assert any(row.source == "wikipedia" for row in rows)


@pytest.mark.asyncio
async def test_lookup_wikipedia_refetches_after_30d(session: AsyncSession):
    # Pre-seed a stale cache row.
    stale = ExternalSourceCacheEntry(
        source="wikipedia",
        key="title:foundation",
        payload={"citations": []},
        fetched_at=datetime.now(UTC) - timedelta(days=31),
    )
    session.add(stale)
    await session.commit()

    fresh_called = False

    def handler(req: httpx.Request) -> httpx.Response:
        nonlocal fresh_called
        fresh_called = True
        return httpx.Response(200, json=_wiki_summary_response("Foundation", "Fresh."))

    r = Retriever(session=session, transport=httpx.MockTransport(handler), timeout_s=5.0)
    cites = await r.lookup_wikipedia(author=None, title="Foundation")
    assert fresh_called is True
    assert any("Fresh." in c.snippet for c in cites)


@pytest.mark.asyncio
async def test_lookup_wikipedia_returns_empty_on_404(session: AsyncSession):
    handler = httpx.MockTransport(lambda req: httpx.Response(404))
    r = Retriever(session=session, transport=httpx.MockTransport(lambda req: httpx.Response(404)), timeout_s=5.0)
    cites = await r.lookup_wikipedia(author=None, title="Definitely Nonexistent Book Xyz")
    assert cites == []


@pytest.mark.asyncio
async def test_lookup_wikipedia_returns_empty_on_timeout(session: AsyncSession):
    def handler(req: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("slow")

    r = Retriever(session=session, transport=httpx.MockTransport(handler), timeout_s=0.5)
    cites = await r.lookup_wikipedia(author=None, title="Anything")
    assert cites == []  # network failure is non-fatal


@pytest.mark.asyncio
async def test_lookup_openlibrary_uses_isbn_when_present(session: AsyncSession):
    seen_urls: list[str] = []

    def handler(req: httpx.Request) -> httpx.Response:
        seen_urls.append(str(req.url))
        return httpx.Response(
            200,
            json=_ol_search_response(
                [
                    {
                        "title": "Foundation",
                        "author_name": ["Isaac Asimov"],
                        "key": "/works/OL12345W",
                        "first_publish_year": 1951,
                    }
                ]
            ),
        )

    r = Retriever(session=session, transport=httpx.MockTransport(handler), timeout_s=5.0)
    cites = await r.lookup_openlibrary(author="Isaac Asimov", title="Foundation", isbn="9780553293357")
    assert any("isbn=9780553293357" in u for u in seen_urls)
    assert any(c.url and "openlibrary.org" in c.url for c in cites)
```

The `session` fixture is reused from `conftest.py`. If the fixture is named differently, look at `tests/integration/test_progress.py` for the canonical name and adjust.

- [ ] **Step 2: Add the `session` fixture for unit tests if missing**

The integration tests use a session-scoped Postgres container and a per-test session. Unit tests for retrieval need DB access for the cache table. Check `server/tests/conftest.py`. If a `session` fixture exists at module scope (used by integration tests), reuse it. Otherwise — and if its scope or naming differs in unit/ — copy it into a top-level `conftest.py` so unit tests can use it. The simplest path: place `tests/conftest.py` so both `tests/unit` and `tests/integration` get the same fixtures, and ensure it offers `session: AsyncSession` for tests like these.

Run: `cd server && pytest tests/unit/test_ai_retrieval.py -v`
Expected: ImportError (`Retriever`, `_normalize_key` not defined).

- [ ] **Step 3: Implement `server/opds_sync/core/ai/retrieval.py`**

```python
"""Deterministic retrieval from Wikipedia + OpenLibrary, cached in Postgres.

Each public lookup function:
  1. Computes a normalized cache key.
  2. Reads `external_source_cache`. Returns immediately if found and fresh.
  3. Otherwise issues an HTTP call (with a strict timeout). On any failure
     (timeout, non-2xx, JSON parse) returns []; the caller falls through to
     the AI without retrieval grounding. Failures are logged at info — they
     are not bugs, they are normal degraded behavior.
  4. Persists the result and returns.

URL choices:
  - Wikipedia REST: /api/rest_v1/page/summary/{title}
  - OpenLibrary search: /search.json?title=...&author=...&isbn=...&limit=3
"""

from __future__ import annotations

import logging
import re
from datetime import UTC, datetime, timedelta

import httpx
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from opds_sync.api.ai_schemas import Citation
from opds_sync.db.models import ExternalSourceCacheEntry

logger = logging.getLogger(__name__)

_TTL = timedelta(days=30)
_WIKI_BASE = "https://en.wikipedia.org/api/rest_v1"
_OL_BASE = "https://openlibrary.org"


def _normalize_key(s: str) -> str:
    return re.sub(r"\s+", " ", s.strip().lower())


class Retriever:
    def __init__(
        self,
        *,
        session: AsyncSession,
        transport: httpx.AsyncBaseTransport | None = None,
        timeout_s: float = 8.0,
    ) -> None:
        self._session = session
        self._transport = transport
        self._timeout_s = timeout_s

    async def lookup_wikipedia(
        self, *, author: str | None, title: str
    ) -> list[Citation]:
        key = f"title:{_normalize_key(title)}"
        cached = await self._read_cache("wikipedia", key)
        if cached is not None:
            return [Citation.model_validate(c) for c in cached.get("citations", [])]

        citations = await self._fetch_wikipedia(title)
        # Fallback to author summary if title returned nothing and we have an author.
        if not citations and author:
            author_key = f"author:{_normalize_key(author)}"
            cached_author = await self._read_cache("wikipedia", author_key)
            if cached_author is not None:
                return [Citation.model_validate(c) for c in cached_author.get("citations", [])]
            citations = await self._fetch_wikipedia(author)
            await self._write_cache(
                "wikipedia",
                author_key,
                {"citations": [c.model_dump() for c in citations]},
            )

        await self._write_cache(
            "wikipedia",
            key,
            {"citations": [c.model_dump() for c in citations]},
        )
        return citations

    async def lookup_openlibrary(
        self, *, author: str | None, title: str, isbn: str | None
    ) -> list[Citation]:
        key_bits = [f"title:{_normalize_key(title)}"]
        if author:
            key_bits.append(f"author:{_normalize_key(author)}")
        if isbn:
            key_bits.append(f"isbn:{_normalize_key(isbn)}")
        key = "|".join(key_bits)

        cached = await self._read_cache("openlibrary", key)
        if cached is not None:
            return [Citation.model_validate(c) for c in cached.get("citations", [])]

        params = {"title": title, "limit": "3"}
        if author:
            params["author"] = author
        if isbn:
            params["isbn"] = isbn

        try:
            async with self._http() as http:
                r = await http.get(f"{_OL_BASE}/search.json", params=params)
                if r.status_code != 200:
                    citations = []
                else:
                    citations = self._parse_openlibrary_response(r.json())
        except httpx.HTTPError as e:
            logger.info("ai.retrieval.openlibrary.fail err=%s", e)
            citations = []

        await self._write_cache(
            "openlibrary",
            key,
            {"citations": [c.model_dump() for c in citations]},
        )
        return citations

    async def _fetch_wikipedia(self, term: str) -> list[Citation]:
        try:
            async with self._http() as http:
                # Wikipedia's REST API takes a slug; URL-encode + replace spaces.
                slug = term.strip().replace(" ", "_")
                r = await http.get(f"{_WIKI_BASE}/page/summary/{slug}")
                if r.status_code == 404:
                    return []
                if r.status_code != 200:
                    logger.info("ai.retrieval.wikipedia.status status=%s term=%s", r.status_code, term)
                    return []
                data = r.json()
        except httpx.HTTPError as e:
            logger.info("ai.retrieval.wikipedia.fail err=%s term=%s", e, term)
            return []

        if data.get("type") == "disambiguation":
            return []  # skip ambiguous results to avoid grounding on the wrong entity

        extract = data.get("extract") or ""
        if not extract:
            return []
        url = (
            data.get("content_urls", {})
            .get("desktop", {})
            .get("page")
        )
        title = data.get("title") or term
        return [Citation(kind="wikipedia", title=title, url=url, snippet=extract[:1200])]

    @staticmethod
    def _parse_openlibrary_response(payload: dict) -> list[Citation]:
        out: list[Citation] = []
        for doc in (payload.get("docs") or [])[:3]:
            title = doc.get("title") or ""
            authors = doc.get("author_name") or []
            year = doc.get("first_publish_year")
            key = doc.get("key") or ""
            if not title:
                continue
            url = f"https://openlibrary.org{key}" if key.startswith("/") else None
            snippet_bits = [title]
            if authors:
                snippet_bits.append(f"by {', '.join(authors[:3])}")
            if year:
                snippet_bits.append(f"({year})")
            out.append(
                Citation(
                    kind="openlibrary",
                    title=title,
                    url=url,
                    snippet=" — ".join(snippet_bits),
                )
            )
        return out

    def _http(self) -> httpx.AsyncClient:
        kwargs = {
            "timeout": httpx.Timeout(self._timeout_s, connect=min(self._timeout_s, 5.0)),
            "headers": {"User-Agent": "opds-sync/ai-retrieval"},
        }
        if self._transport is not None:
            kwargs["transport"] = self._transport
        return httpx.AsyncClient(**kwargs)

    async def _read_cache(self, source: str, key: str) -> dict | None:
        row = (
            await self._session.execute(
                select(ExternalSourceCacheEntry).where(
                    ExternalSourceCacheEntry.source == source,
                    ExternalSourceCacheEntry.key == key,
                )
            )
        ).scalar_one_or_none()
        if row is None:
            return None
        if row.fetched_at < datetime.now(UTC) - _TTL:
            return None
        return row.payload

    async def _write_cache(self, source: str, key: str, payload: dict) -> None:
        existing = (
            await self._session.execute(
                select(ExternalSourceCacheEntry).where(
                    ExternalSourceCacheEntry.source == source,
                    ExternalSourceCacheEntry.key == key,
                )
            )
        ).scalar_one_or_none()
        if existing is None:
            self._session.add(
                ExternalSourceCacheEntry(
                    source=source,
                    key=key,
                    payload=payload,
                    fetched_at=datetime.now(UTC),
                )
            )
        else:
            existing.payload = payload
            existing.fetched_at = datetime.now(UTC)
        await self._session.commit()
```

- [ ] **Step 4: Run tests**

Run: `cd server && pytest tests/unit/test_ai_retrieval.py -v`
Expected: 6 PASS.

- [ ] **Step 5: Commit**

```bash
git add server/opds_sync/core/ai/retrieval.py server/tests/unit/test_ai_retrieval.py
git commit -m ":sparkles: feat(server): Wikipedia + OpenLibrary retrieval with cache"
```

---

## Task 7: Prompt composer

**Files:**
- Create: `server/opds_sync/core/ai/prompts.py`
- Create: `server/tests/unit/test_ai_prompts.py`

- [ ] **Step 1: Failing test (`tests/unit/test_ai_prompts.py`)**

```python
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
```

- [ ] **Step 2: Run (expect failure)**

Run: `cd server && pytest tests/unit/test_ai_prompts.py -v`
Expected: ImportError.

- [ ] **Step 3: Implement `server/opds_sync/core/ai/prompts.py`**

```python
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
```

- [ ] **Step 4: Run tests**

Run: `cd server && pytest tests/unit/test_ai_prompts.py -v`
Expected: 8 PASS.

- [ ] **Step 5: Commit**

```bash
git add server/opds_sync/core/ai/prompts.py server/tests/unit/test_ai_prompts.py
git commit -m ":sparkles: feat(server): book-insight prompt composer"
```

---

## Task 8: Insight orchestrator

**Files:**
- Create: `server/opds_sync/core/ai/service.py`
- Create: `server/tests/unit/test_ai_service.py`

> **Scope note (added in revision):** the orchestrator owns four
> responsibilities: cache, coalescing, semaphore, and (new) **quota**. The
> quota subsystem has two layers — a process-wide **token bucket** against
> `AI_BASE_URL` to protect the provider, and a per-user **daily budget**
> persisted in `ai_usage_daily`. Cache hits bypass both. See "Step 6"
> below for the quota implementation and tests.

- [ ] **Step 1: Failing test (`tests/unit/test_ai_service.py`)**

```python
import asyncio
from typing import Any

import pytest
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from opds_sync.api.ai_schemas import (
    BookInsightPayload,
    DocumentIdentity,
    MetadataBundle,
)
from opds_sync.core.ai.service import InsightOrchestrator


class FakeAIClient:
    def __init__(self) -> None:
        self.calls: list[dict] = []
        self.next_payload: dict[str, Any] = {
            "schema_version": 1,
            "summary": "A foundational sci-fi novel.",
            "confidence": "high",
        }

    async def chat_structured(self, *, system, user, schema, timeout_s):
        self.calls.append({"system": system, "user": user})
        return schema.model_validate(self.next_payload)


class FakeRetriever:
    def __init__(self) -> None:
        self.wiki_calls: int = 0
        self.ol_calls: int = 0

    async def lookup_wikipedia(self, **kw):
        self.wiki_calls += 1
        return []

    async def lookup_openlibrary(self, **kw):
        self.ol_calls += 1
        return []


@pytest.fixture
def make_orchestrator(session):
    def _make(sources_enabled=("wikipedia", "openlibrary"), max_concurrency=4):
        fake_retriever = FakeRetriever()
        orch = InsightOrchestrator(
            ai=FakeAIClient(),
            retriever_factory=lambda s: fake_retriever,
            sources_enabled=tuple(sources_enabled),
            model_id="test-model",
            prompt_version="t1",
            max_concurrency=max_concurrency,
            ai_timeout_s=5.0,
        )
        # Expose for tests that assert on call counts.
        orch.retriever = fake_retriever  # type: ignore[attr-defined]
        return orch
    return _make


@pytest.mark.asyncio
async def test_cache_hit_short_circuits(session: AsyncSession, make_orchestrator):
    orch = make_orchestrator()
    ident = DocumentIdentity(metadata_id="9780553293357", content_hash="ch1")
    bundle = MetadataBundle(title="Foundation", author="Isaac Asimov")

    first = await orch.generate(session, ident, bundle, user_id="u1")
    second_orch = make_orchestrator()  # fresh fakes; if it talks to AI we'll know
    second = await second_orch.generate(session, ident, bundle, user_id="u2")

    assert first.payload.summary == second.payload.summary
    assert second_orch.ai.calls == []  # served from cache
    assert second_orch.retriever.wiki_calls == 0


@pytest.mark.asyncio
async def test_alias_reconciliation_backfills_metadata_id(session: AsyncSession, make_orchestrator):
    orch = make_orchestrator()
    # First request: hash-only.
    ident_hash = DocumentIdentity(metadata_id=None, content_hash="ch-foo")
    bundle = MetadataBundle(title="Foundation")
    await orch.generate(session, ident_hash, bundle, user_id="u1")

    # Second request for the same content_hash, now with a metadata_id.
    second = make_orchestrator()
    ident_full = DocumentIdentity(metadata_id="urn-foo", content_hash="ch-foo")
    out = await second.generate(session, ident_full, bundle, user_id="u2")
    assert second.ai.calls == []  # alias-reconciliation hit the cache, no model call

    from opds_sync.db.models import BookInsight
    rows = (await session.execute(select(BookInsight).where(BookInsight.content_hash == "ch-foo"))).scalars().all()
    assert len(rows) == 1
    assert rows[0].metadata_id == "urn-foo"  # backfilled


@pytest.mark.asyncio
async def test_concurrent_generations_collapse_to_one_model_call(session: AsyncSession, make_orchestrator):
    orch = make_orchestrator()
    ident = DocumentIdentity(metadata_id=None, content_hash="ch-coalesce")
    bundle = MetadataBundle(title="Coalesce")

    results = await asyncio.gather(
        orch.generate(session, ident, bundle, user_id="u1"),
        orch.generate(session, ident, bundle, user_id="u2"),
        orch.generate(session, ident, bundle, user_id="u3"),
    )
    assert len(orch.ai.calls) == 1
    assert {r.payload.summary for r in results} == {"A foundational sci-fi novel."}


@pytest.mark.asyncio
async def test_invalidate_drops_cached_row(session: AsyncSession, make_orchestrator):
    orch = make_orchestrator()
    ident = DocumentIdentity(metadata_id=None, content_hash="ch-invalidate")
    await orch.generate(session, ident, MetadataBundle(title="X"), user_id="u1")

    n = await orch.invalidate(session, ident)
    assert n == 1

    second = make_orchestrator()
    second.ai.next_payload = {"schema_version": 1, "summary": "fresh", "confidence": "low"}
    out = await second.generate(session, ident, MetadataBundle(title="X"), user_id="u1")
    assert out.payload.summary == "fresh"


@pytest.mark.asyncio
async def test_get_returns_none_on_miss(session: AsyncSession, make_orchestrator):
    orch = make_orchestrator()
    ident = DocumentIdentity(metadata_id=None, content_hash="ch-miss")
    assert await orch.get(session, ident) is None


@pytest.mark.asyncio
async def test_series_from_bundle_persists_into_payload(session: AsyncSession, make_orchestrator):
    orch = make_orchestrator()
    orch.ai.next_payload = {
        "schema_version": 1,
        "summary": "ok",
        "series": {"name": "WrongName", "position": 99},  # model tries to override
        "confidence": "low",
    }
    ident = DocumentIdentity(metadata_id=None, content_hash="ch-series")
    bundle = MetadataBundle(title="X", series_name="Foundation", series_position=1)
    out = await orch.generate(session, ident, bundle, user_id="u1")
    assert out.payload.series.name == "Foundation"
    assert out.payload.series.position == 1
```

- [ ] **Step 2: Run (expect failure)**

Run: `cd server && pytest tests/unit/test_ai_service.py -v`
Expected: ImportError.

- [ ] **Step 3: Implement `server/opds_sync/core/ai/service.py`**

```python
"""Insight orchestrator.

Responsibilities:
- Cache lookup (metadata_id first, content_hash second) with alias
  reconciliation.
- Per-identity coalescing via in-process asyncio locks.
- Server-wide concurrency cap via asyncio.Semaphore.
- Pre-prompt retrieval from configured sources, in parallel.
- Series override: bundle's series wins over the model's.
- Persist + return.
"""

from __future__ import annotations

import asyncio
import logging
import time
from collections.abc import Callable
from typing import Protocol

from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession

from datetime import UTC, date, datetime, timedelta

from opds_sync.api.ai_schemas import (
    AiStyle,
    BookInsightPayload,
    BookInsightResponse,
    Citation,
    DocumentIdentity,
    MetadataBundle,
    SeriesInsight,
)
from opds_sync.core.ai.prompts import SYSTEM_PROMPT, compose_user_prompt
from opds_sync.db.models import AIUsageDaily, BookInsight

logger = logging.getLogger(__name__)


class QuotaExceeded(Exception):
    """Raised when the per-user daily budget is full. Carries values for the 429 body."""

    def __init__(self, *, used: int, limit: int, resets_at: datetime) -> None:
        self.used = used
        self.limit = limit
        self.resets_at = resets_at
        super().__init__(f"daily budget exhausted: {used}/{limit}")


class TokenBucket:
    """Process-wide async token bucket. Smooths bursts against AI_BASE_URL."""

    def __init__(self, *, rate_per_min: int) -> None:
        self._capacity = float(max(rate_per_min, 1))
        self._refill_per_s = self._capacity / 60.0
        self._tokens = self._capacity
        self._last = time.monotonic()
        self._lock = asyncio.Lock()

    async def acquire(self) -> None:
        while True:
            async with self._lock:
                now = time.monotonic()
                self._tokens = min(
                    self._capacity, self._tokens + (now - self._last) * self._refill_per_s
                )
                self._last = now
                if self._tokens >= 1.0:
                    self._tokens -= 1.0
                    return
                wait_s = (1.0 - self._tokens) / self._refill_per_s
            await asyncio.sleep(wait_s)


class _AIClientLike(Protocol):
    async def chat_structured(
        self, *, system: str, user: str, schema: type, timeout_s: float
    ): ...


class _RetrieverLike(Protocol):
    async def lookup_wikipedia(
        self, *, author: str | None, title: str
    ) -> list[Citation]: ...

    async def lookup_openlibrary(
        self, *, author: str | None, title: str, isbn: str | None
    ) -> list[Citation]: ...


class InsightOrchestrator:
    def __init__(
        self,
        *,
        ai: _AIClientLike,
        retriever_factory: Callable[[AsyncSession], _RetrieverLike],
        sources_enabled: tuple[str, ...],
        model_id: str,
        prompt_version: str,
        max_concurrency: int,
        ai_timeout_s: float,
        rate_per_min: int = 10,
        daily_budget: int = 200,
        regen_daily_limit: int = 3,
    ) -> None:
        self.ai = ai
        self.retriever_factory = retriever_factory
        self.sources_enabled = tuple(sources_enabled)
        self.model_id = model_id
        self.prompt_version = prompt_version
        self._sem = asyncio.Semaphore(max_concurrency)
        self._locks: dict[str, asyncio.Lock] = {}
        self._locks_master = asyncio.Lock()
        self._ai_timeout_s = ai_timeout_s
        self._bucket = TokenBucket(rate_per_min=rate_per_min)
        self._daily_budget = daily_budget
        self._regen_daily_limit = regen_daily_limit

    # ------- public API -------

    async def get(
        self, session: AsyncSession, ident: DocumentIdentity
    ) -> BookInsightResponse | None:
        row = await self._cache_lookup(session, ident, allow_backfill=False)
        if row is None:
            return None
        return self._row_to_response(row)

    async def generate(
        self,
        session: AsyncSession,
        ident: DocumentIdentity,
        bundle: MetadataBundle,
        *,
        user_id: str,
        style: AiStyle | None = None,
    ) -> BookInsightResponse:
        # Fast path — cache hits bypass budget *and* rate limit.
        row = await self._cache_lookup(session, ident, allow_backfill=True)
        if row is not None:
            return self._row_to_response(row)

        lock = await self._acquire_identity_lock(ident)
        async with lock:
            # Re-check inside the lock — another waiter may have populated.
            row = await self._cache_lookup(session, ident, allow_backfill=True)
            if row is not None:
                return self._row_to_response(row)

            await self._reserve_budget(session, user_id=user_id, is_regen=False)
            await self._bucket.acquire()
            row = await self._do_generate(
                session, ident, bundle, user_id=user_id, style=style, feedback=None,
                previous_insight_ids=None,
            )
            return self._row_to_response(row)

    async def regenerate(
        self,
        session: AsyncSession,
        ident: DocumentIdentity,
        bundle: MetadataBundle,
        *,
        user_id: str,
        reason: str,
        style: AiStyle | None = None,
    ) -> BookInsightResponse:
        """Supersede the existing live row (if any) and generate a fresh one.

        Counts against `regen_count` in `ai_usage_daily` (tighter limit than
        regular generation). The previous row is kept for audit; its id chain
        is recorded in `previous_insight_ids` of the new row.
        """
        lock = await self._acquire_identity_lock(ident)
        async with lock:
            existing = await self._cache_lookup(session, ident, allow_backfill=False)
            previous_ids: list[int] = []
            if existing is not None:
                previous_ids = list(existing.previous_insight_ids or [])
                previous_ids.append(existing.id)
                existing.superseded_at = datetime.now(UTC)
                await session.commit()

            await self._reserve_budget(session, user_id=user_id, is_regen=True)
            await self._bucket.acquire()
            row = await self._do_generate(
                session, ident, bundle, user_id=user_id, style=style, feedback=reason,
                previous_insight_ids=previous_ids or None,
            )
            return self._row_to_response(row)

    async def _do_generate(
        self,
        session: AsyncSession,
        ident: DocumentIdentity,
        bundle: MetadataBundle,
        *,
        user_id: str,
        style: AiStyle | None,
        feedback: str | None,
        previous_insight_ids: list[int] | None,
    ) -> BookInsight:
        async with self._sem:
            citations = await self._retrieve(session, bundle)
            user_prompt = compose_user_prompt(
                bundle, citations, style=style, feedback=feedback
            )
            t0 = time.monotonic()
            payload = await self.ai.chat_structured(
                system=SYSTEM_PROMPT,
                user=user_prompt,
                schema=BookInsightPayload,
                timeout_s=self._ai_timeout_s,
            )
            latency_ms = int((time.monotonic() - t0) * 1000)
            logger.info(
                "ai.generate content_hash=%s model=%s latency_ms=%d sources=%s regen=%s",
                ident.content_hash,
                self.model_id,
                latency_ms,
                ",".join(sorted({c.kind for c in citations})) or "-",
                bool(feedback),
            )

        if bundle.series_name:
            payload.series = SeriesInsight(
                name=bundle.series_name,
                position=bundle.series_position,
            )

        sources = list(citations)
        sources.append(
            Citation(kind="model", title=self.model_id, snippet="generated")
        )
        row = BookInsight(
            metadata_id=ident.metadata_id,
            content_hash=ident.content_hash,
            model_id=self.model_id,
            prompt_version=self.prompt_version,
            sources_used=list({c.kind for c in citations}),
            payload=payload.model_dump(),
            sources=[c.model_dump() for c in sources],
            generated_by=user_id,
            previous_insight_ids=previous_insight_ids,
        )
        session.add(row)
        await session.commit()
        await session.refresh(row)
        return row

    async def _reserve_budget(
        self,
        session: AsyncSession,
        *,
        user_id: str,
        is_regen: bool,
    ) -> None:
        """Atomically increment the day's count for this user and enforce limits.

        Raises QuotaExceeded if the relevant ceiling would be crossed. `count`
        and `regen_count` both increment on a regen (regenerations also burn
        daily budget) — only the ceiling differs.
        """
        if self._daily_budget <= 0 and not is_regen:
            return  # disabled
        today = datetime.now(UTC).date()
        usage = (
            await session.execute(
                select(AIUsageDaily).where(
                    AIUsageDaily.user_id == user_id, AIUsageDaily.day == today
                )
            )
        ).scalar_one_or_none()
        if usage is None:
            usage = AIUsageDaily(user_id=user_id, day=today, count=0, regen_count=0)
            session.add(usage)
            await session.flush()

        # Hard ceilings
        if self._daily_budget > 0 and usage.count >= self._daily_budget:
            raise QuotaExceeded(
                used=usage.count, limit=self._daily_budget,
                resets_at=_next_utc_midnight(today),
            )
        if is_regen and usage.regen_count >= self._regen_daily_limit:
            raise QuotaExceeded(
                used=usage.regen_count, limit=self._regen_daily_limit,
                resets_at=_next_utc_midnight(today),
            )

        usage.count += 1
        if is_regen:
            usage.regen_count += 1
        await session.commit()

    async def invalidate(
        self, session: AsyncSession, ident: DocumentIdentity
    ) -> int:
        stmt = delete(BookInsight).where(
            BookInsight.model_id == self.model_id,
            BookInsight.prompt_version == self.prompt_version,
        )
        if ident.metadata_id:
            stmt = stmt.where(
                (BookInsight.metadata_id == ident.metadata_id)
                | (BookInsight.content_hash == ident.content_hash)
            )
        else:
            stmt = stmt.where(BookInsight.content_hash == ident.content_hash)
        result = await session.execute(stmt)
        await session.commit()
        return result.rowcount or 0

    # ------- internals -------

    async def _retrieve(
        self, session: AsyncSession, bundle: MetadataBundle
    ) -> list[Citation]:
        retriever = self.retriever_factory(session)
        tasks = []
        if "wikipedia" in self.sources_enabled:
            tasks.append(
                retriever.lookup_wikipedia(author=bundle.author, title=bundle.title)
            )
        if "openlibrary" in self.sources_enabled:
            tasks.append(
                retriever.lookup_openlibrary(
                    author=bundle.author, title=bundle.title, isbn=bundle.isbn
                )
            )
        if not tasks:
            return []
        results = await asyncio.gather(*tasks, return_exceptions=True)
        out: list[Citation] = []
        for r in results:
            if isinstance(r, Exception):
                logger.info("ai.retrieval.exception err=%s", r)
                continue
            out.extend(r)
        return out

    async def _cache_lookup(
        self,
        session: AsyncSession,
        ident: DocumentIdentity,
        *,
        allow_backfill: bool,
    ) -> BookInsight | None:
        # Step 1: by metadata_id
        if ident.metadata_id:
            row = (
                await session.execute(
                    select(BookInsight).where(
                        BookInsight.metadata_id == ident.metadata_id,
                        BookInsight.model_id == self.model_id,
                        BookInsight.prompt_version == self.prompt_version,
                    )
                )
            ).scalar_one_or_none()
            if row is not None:
                return row
        # Step 2: by content_hash
        row = (
            await session.execute(
                select(BookInsight).where(
                    BookInsight.content_hash == ident.content_hash,
                    BookInsight.model_id == self.model_id,
                    BookInsight.prompt_version == self.prompt_version,
                )
            )
        ).scalar_one_or_none()
        if row is None:
            return None
        # Alias reconciliation: backfill metadata_id if we just learned it.
        if (
            allow_backfill
            and ident.metadata_id
            and row.metadata_id is None
        ):
            row.metadata_id = ident.metadata_id
            await session.commit()
            await session.refresh(row)
        return row

    async def _acquire_identity_lock(self, ident: DocumentIdentity) -> asyncio.Lock:
        key = ident.metadata_id or ident.content_hash
        async with self._locks_master:
            lock = self._locks.get(key)
            if lock is None:
                lock = asyncio.Lock()
                self._locks[key] = lock
            return lock

    def _row_to_response(self, row: BookInsight) -> BookInsightResponse:
        return BookInsightResponse(
            payload=BookInsightPayload.model_validate(row.payload),
            sources=[Citation.model_validate(c) for c in row.sources],
            model_id=row.model_id,
            prompt_version=row.prompt_version,
            generated_at=row.generated_at.isoformat(),
        )


def _next_utc_midnight(today: date) -> datetime:
    return datetime.combine(today + timedelta(days=1), datetime.min.time(), tzinfo=UTC)
```

- [ ] **Step 4: Run tests**

Run: `cd server && pytest tests/unit/test_ai_service.py -v`
Expected: 6 PASS (existing tests). New quota tests added in Step 6.

- [ ] **Step 5: Update existing `_cache_lookup` to ignore superseded rows**

Insert `BookInsight.superseded_at.is_(None)` into both queries in `_cache_lookup`
so the look-up always returns the live row. (Migration's partial index already
enforces uniqueness on the live row only.)

```python
# In both select(...) calls inside _cache_lookup, add:
BookInsight.superseded_at.is_(None),
```

- [ ] **Step 6: Add quota + style tests (`tests/unit/test_ai_service.py`)**

Append the following tests. They exercise the new `TokenBucket`, the
daily budget, regeneration lineage, and style threading.

```python
import time as _time
from datetime import UTC, date, datetime

from opds_sync.api.ai_schemas import AiStyle
from opds_sync.core.ai.service import QuotaExceeded, TokenBucket
from opds_sync.db.models import AIUsageDaily


@pytest.mark.asyncio
async def test_token_bucket_smooths_bursts():
    bucket = TokenBucket(rate_per_min=60)  # 1 per second
    start = _time.monotonic()
    # Capacity is 60, so the first 60 should be instant.
    for _ in range(3):
        await bucket.acquire()
    assert _time.monotonic() - start < 0.05


@pytest.mark.asyncio
async def test_daily_budget_blocks_after_limit(session, make_orchestrator):
    orch = make_orchestrator()
    orch._daily_budget = 2
    # First two go through.
    for i in range(2):
        ident = DocumentIdentity(metadata_id=None, content_hash=f"ch-budget-{i}")
        await orch.generate(session, ident, MetadataBundle(title=f"B{i}"), user_id="u-quota")
    # Third raises QuotaExceeded.
    with pytest.raises(QuotaExceeded) as exc:
        await orch.generate(
            session,
            DocumentIdentity(metadata_id=None, content_hash="ch-budget-3"),
            MetadataBundle(title="B3"),
            user_id="u-quota",
        )
    assert exc.value.used == 2
    assert exc.value.limit == 2


@pytest.mark.asyncio
async def test_cache_hits_bypass_budget(session, make_orchestrator):
    orch = make_orchestrator()
    orch._daily_budget = 1
    ident = DocumentIdentity(metadata_id=None, content_hash="ch-cache-hit")
    await orch.generate(session, ident, MetadataBundle(title="X"), user_id="u-cache")
    # Budget is now exhausted, but a cache hit must still serve.
    out = await orch.generate(session, ident, MetadataBundle(title="X"), user_id="u-cache")
    assert out.payload.summary == "A foundational sci-fi novel."


@pytest.mark.asyncio
async def test_regenerate_supersedes_and_records_lineage(session, make_orchestrator):
    orch = make_orchestrator()
    ident = DocumentIdentity(metadata_id=None, content_hash="ch-regen")
    first = await orch.generate(session, ident, MetadataBundle(title="X"), user_id="u1")
    orch.ai.next_payload = {"schema_version": 1, "summary": "fixed", "confidence": "high"}
    second = await orch.regenerate(
        session, ident, MetadataBundle(title="X"),
        user_id="u1", reason="Author bio was wrong.",
    )
    assert second.payload.summary == "fixed"

    rows = (await session.execute(
        select(BookInsight).where(BookInsight.content_hash == "ch-regen").order_by(BookInsight.id)
    )).scalars().all()
    assert len(rows) == 2
    assert rows[0].superseded_at is not None
    assert rows[1].superseded_at is None
    assert rows[1].previous_insight_ids == [rows[0].id]


@pytest.mark.asyncio
async def test_regen_has_tighter_daily_limit(session, make_orchestrator):
    orch = make_orchestrator()
    orch._regen_daily_limit = 1
    ident = DocumentIdentity(metadata_id=None, content_hash="ch-regen-limit")
    await orch.generate(session, ident, MetadataBundle(title="X"), user_id="u-regen")
    await orch.regenerate(session, ident, MetadataBundle(title="X"),
                          user_id="u-regen", reason="no good")
    with pytest.raises(QuotaExceeded):
        await orch.regenerate(session, ident, MetadataBundle(title="X"),
                              user_id="u-regen", reason="still no good")


@pytest.mark.asyncio
async def test_style_threaded_into_prompt(session, make_orchestrator):
    orch = make_orchestrator()
    ident = DocumentIdentity(metadata_id=None, content_hash="ch-style")
    await orch.generate(
        session, ident, MetadataBundle(title="X"), user_id="u-style",
        style=AiStyle(tone="scholarly", include_spoilers=True),
    )
    # Verify the user prompt actually included the style block.
    assert any("scholarly" in call["user"].lower() for call in orch.ai.calls)
```

- [ ] **Step 7: Run tests**

Run: `cd server && pytest tests/unit/test_ai_service.py -v`
Expected: 12 PASS (6 original + 6 new quota/regen/style tests).

- [ ] **Step 8: Commit**

```bash
git add server/opds_sync/core/ai/service.py server/tests/unit/test_ai_service.py
git commit -m ":sparkles: feat(server): insight orchestrator with coalescing, quotas, regen lineage"
```

---

## Task 9: AI router with all seven endpoints

**Files:**
- Create: `server/opds_sync/api/ai.py`

- [ ] **Step 1: Implement the router**

```python
"""/ai/v1/* endpoints. Auth = same Basic-auth proxy as /sync/v1."""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Annotated
from urllib.parse import urlparse

from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from opds_sync.api.ai_schemas import (
    AiStyle,
    BookInsightResponse,
    ConfigResponse,
    InsightGetBody,
    InsightInvalidateBody,
    InsightLookupBody,
    InsightRegenerateBody,
    PreferencesBody,
    PreferencesResponse,
    QuotaResponse,
)
from opds_sync.config import get_settings
from opds_sync.core.ai.service import InsightOrchestrator, QuotaExceeded
from opds_sync.core.auth import current_user_id
from opds_sync.db.models import UserAIPreference
from opds_sync.db.session import get_session

router = APIRouter(tags=["ai"])


def _orchestrator(request: Request) -> InsightOrchestrator | None:
    return getattr(request.app.state, "ai_orchestrator", None)


def _enabled_sources() -> list[str]:
    raw = (get_settings().ai_sources or "").strip()
    if not raw:
        return []
    return [s.strip() for s in raw.split(",") if s.strip()]


def _base_url_host() -> str | None:
    base = get_settings().ai_base_url
    if not base:
        return None
    return urlparse(base).hostname


async def _require_opt_in(session: AsyncSession, user_id: str) -> UserAIPreference:
    pref = (
        await session.execute(
            select(UserAIPreference).where(UserAIPreference.user_id == user_id)
        )
    ).scalar_one_or_none()
    if pref is None or not pref.ai_enabled:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="not_opted_in")
    return pref


def _style_from_pref(pref: UserAIPreference) -> AiStyle:
    """Build a validated AiStyle from the user's stored prefs, falling back to defaults."""
    if not pref.style:
        return AiStyle()
    try:
        return AiStyle.model_validate(pref.style)
    except Exception:
        # Old / malformed prefs row → use defaults rather than 500-ing the read.
        return AiStyle()


def _quota_http_exception(exc: QuotaExceeded) -> HTTPException:
    body = QuotaResponse(
        used=exc.used, limit=exc.limit, resets_at=exc.resets_at.isoformat()
    )
    return HTTPException(
        status_code=status.HTTP_429_TOO_MANY_REQUESTS,
        detail=body.model_dump(),
        headers={"Retry-After": str(max(int((exc.resets_at - datetime.now(UTC)).total_seconds()), 60))},
    )


@router.get("/config", response_model=ConfigResponse)
async def get_config(
    user_id: Annotated[str, Depends(current_user_id)],
) -> ConfigResponse:
    """Public to authed users; the app needs this to render the AI toggle."""
    settings = get_settings()
    return ConfigResponse(
        configured=bool(
            settings.ai_enabled and settings.ai_base_url and settings.ai_model
        ),
        base_url_host=_base_url_host() if settings.ai_enabled else None,
        model_id=settings.ai_model if settings.ai_enabled else None,
        sources_enabled=_enabled_sources() if settings.ai_enabled else [],
        daily_budget=settings.ai_daily_budget,
        regen_daily_limit=settings.ai_regen_daily_limit,
    )


@router.get("/preferences", response_model=PreferencesResponse)
async def get_preferences(
    user_id: Annotated[str, Depends(current_user_id)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> PreferencesResponse:
    pref = (
        await session.execute(
            select(UserAIPreference).where(UserAIPreference.user_id == user_id)
        )
    ).scalar_one_or_none()
    if pref is None:
        return PreferencesResponse(ai_enabled=False, style=AiStyle())
    return PreferencesResponse(
        ai_enabled=pref.ai_enabled,
        style=_style_from_pref(pref),
    )


@router.put("/preferences", response_model=PreferencesResponse)
async def put_preferences(
    body: PreferencesBody,
    user_id: Annotated[str, Depends(current_user_id)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> PreferencesResponse:
    pref = (
        await session.execute(
            select(UserAIPreference).where(UserAIPreference.user_id == user_id)
        )
    ).scalar_one_or_none()
    if pref is None:
        pref = UserAIPreference(
            user_id=user_id,
            ai_enabled=body.ai_enabled if body.ai_enabled is not None else False,
            style=body.style.model_dump() if body.style else None,
        )
        session.add(pref)
    else:
        if body.ai_enabled is not None:
            pref.ai_enabled = body.ai_enabled
        if body.style is not None:
            pref.style = body.style.model_dump()
    await session.commit()
    await session.refresh(pref)
    return PreferencesResponse(
        ai_enabled=pref.ai_enabled,
        style=_style_from_pref(pref),
    )


@router.post("/insights/lookup", response_model=BookInsightResponse)
async def lookup_insight(
    request: Request,
    body: InsightLookupBody,
    user_id: Annotated[str, Depends(current_user_id)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> BookInsightResponse:
    orch = _orchestrator(request)
    if orch is None:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, detail="ai_disabled")
    pref = await _require_opt_in(session, user_id)
    try:
        return await orch.generate(
            session,
            body.identity,
            body.bundle,
            user_id=user_id,
            style=_style_from_pref(pref),
        )
    except QuotaExceeded as exc:
        raise _quota_http_exception(exc) from exc


@router.post("/insights/regenerate", response_model=BookInsightResponse)
async def regenerate_insight(
    request: Request,
    body: InsightRegenerateBody,
    user_id: Annotated[str, Depends(current_user_id)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> BookInsightResponse:
    """Mark the existing live row as superseded and generate a fresh one
    incorporating the user's `reason`. Counts against regen budget."""
    orch = _orchestrator(request)
    if orch is None:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, detail="ai_disabled")
    pref = await _require_opt_in(session, user_id)
    try:
        return await orch.regenerate(
            session,
            body.identity,
            body.bundle,
            user_id=user_id,
            reason=body.reason,
            style=_style_from_pref(pref),
        )
    except QuotaExceeded as exc:
        raise _quota_http_exception(exc) from exc


@router.post("/insights/get", response_model=BookInsightResponse)
async def get_insight(
    request: Request,
    body: InsightGetBody,
    user_id: Annotated[str, Depends(current_user_id)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> BookInsightResponse:
    orch = _orchestrator(request)
    if orch is None:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, detail="ai_disabled")
    out = await orch.get(session, body.identity)
    if out is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, detail="not_cached")
    return out


@router.post("/insights/invalidate")
async def invalidate_insight(
    request: Request,
    body: InsightInvalidateBody,
    user_id: Annotated[str, Depends(current_user_id)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> dict:
    orch = _orchestrator(request)
    if orch is None:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, detail="ai_disabled")
    await _require_opt_in(session, user_id)
    n = await orch.invalidate(session, body.identity)
    return {"deleted": n}
```

- [ ] **Step 2: Wire into `server/opds_sync/main.py`**

Edit `main.py` so `create_app` builds the orchestrator and mounts the router:

```python
import logging

import httpx
from fastapi import FastAPI

from opds_sync.api import ai, health, progress
from opds_sync.config import get_settings
from opds_sync.core.ai.client import AIClient
from opds_sync.core.ai.retrieval import Retriever
from opds_sync.core.ai.service import InsightOrchestrator
from opds_sync.core.auth import CalibreAuthValidator
from opds_sync.db.session import configure, make_engine


def create_app() -> FastAPI:
    settings = get_settings()
    logging.basicConfig(level=settings.log_level)

    configure(make_engine(settings.database_url))

    app = FastAPI(title="opds-sync", version="0.3.0")

    httpx_client = httpx.AsyncClient(timeout=settings.cwa_probe_timeout_s)
    app.state.httpx_client = httpx_client
    app.state.auth_validator = CalibreAuthValidator(
        client=httpx_client,
        cwa_base_url=settings.cwa_base_url,
        probe_path=settings.cwa_probe_path,
        positive_ttl_s=settings.auth_cache_positive_ttl_s,
        negative_ttl_s=settings.auth_cache_negative_ttl_s,
        max_entries=settings.auth_cache_max_entries,
    )

    if settings.ai_enabled and settings.ai_base_url and settings.ai_model:
        ai_client = AIClient(
            base_url=settings.ai_base_url,
            api_key=settings.ai_api_key,
            model=settings.ai_model,
        )
        sources_enabled = tuple(
            s.strip() for s in (settings.ai_sources or "").split(",") if s.strip()
        )
        orch = InsightOrchestrator(
            ai=ai_client,
            retriever_factory=lambda s: Retriever(
                session=s, timeout_s=settings.ai_retrieval_timeout_s
            ),
            sources_enabled=sources_enabled,
            model_id=settings.ai_model,
            prompt_version=settings.ai_prompt_version,
            max_concurrency=settings.ai_max_concurrency,
            ai_timeout_s=settings.ai_timeout_s,
            rate_per_min=settings.ai_rate_per_min,
            daily_budget=settings.ai_daily_budget,
            regen_daily_limit=settings.ai_regen_daily_limit,
        )
        app.state.ai_orchestrator = orch

    @app.on_event("shutdown")
    async def _close() -> None:
        await httpx_client.aclose()

    app.include_router(health.router, prefix="/sync/v1")
    app.include_router(progress.router, prefix="/sync/v1")
    app.include_router(ai.router, prefix="/ai/v1")
    return app


app = create_app()
```

- [ ] **Step 3: Re-run unit tests**

Run: `cd server && pytest tests/unit -v`
Expected: all PASS (test_ai_service.py and test_ai_retrieval.py both still green).

- [ ] **Step 4: Commit**

```bash
git add server/opds_sync/api/ai.py server/opds_sync/main.py
git commit -m ":sparkles: feat(server): /ai/v1 endpoints + main.py wiring"
```

---

## Task 10: Integration tests for /ai/v1

**Files:**
- Create: `server/tests/integration/test_ai_endpoints.py`

This is the broad end-to-end check: app runs, AI is configured (with a fake
provider), endpoints behave correctly across opt-in / cache / invalidate
paths.

- [ ] **Step 1: Implement the test**

```python
import base64
import json

import httpx
import pytest
from sqlalchemy import select

from opds_sync.api.ai_schemas import BookInsightPayload
from opds_sync.config import get_settings
from opds_sync.core.ai.client import AIClient
from opds_sync.core.ai.service import InsightOrchestrator
from opds_sync.db.models import BookInsight, UserAIPreference


def _basic_header(user: str, password: str = "p") -> dict:
    return {
        "Authorization": "Basic " + base64.b64encode(f"{user}:{password}".encode()).decode()
    }


def _ai_chat_response(payload: dict) -> dict:
    return {
        "id": "x",
        "model": "test-model",
        "choices": [
            {"index": 0, "message": {"role": "assistant", "content": json.dumps(payload)}}
        ],
    }


@pytest.fixture
def configure_ai(monkeypatch):
    """Configure AI env at the Settings layer, install fake AI orchestrator on app.state."""

    def _apply(app, fake_ai_payload: dict, sources_enabled: tuple[str, ...] = ()):
        # Bypass real config; install a deterministic orchestrator on app.state.
        def fake_handler(req: httpx.Request) -> httpx.Response:
            return httpx.Response(200, json=_ai_chat_response(fake_ai_payload))

        ai = AIClient(
            base_url="http://fake/v1",
            api_key=None,
            model="test-model",
            transport=httpx.MockTransport(fake_handler),
        )

        class _NoOpRetriever:
            async def lookup_wikipedia(self, **kw):
                return []

            async def lookup_openlibrary(self, **kw):
                return []

        orch = InsightOrchestrator(
            ai=ai,
            retriever_factory=lambda s: _NoOpRetriever(),
            sources_enabled=sources_enabled,
            model_id="test-model",
            prompt_version="t1",
            max_concurrency=4,
            ai_timeout_s=5.0,
        )
        app.state.ai_orchestrator = orch
        return orch

    return _apply


@pytest.mark.asyncio
async def test_config_endpoint_when_disabled(client_factory):
    async with client_factory(ai_enabled=False) as client:
        r = await client.get("/ai/v1/config", headers=_basic_header("alice"))
        assert r.status_code == 200
        assert r.json() == {
            "configured": False,
            "base_url_host": None,
            "model_id": None,
            "sources_enabled": [],
        }


@pytest.mark.asyncio
async def test_config_endpoint_when_enabled(client_factory, configure_ai):
    async with client_factory(
        ai_enabled=True,
        ai_base_url="http://ollama.lan:11434/v1",
        ai_model="llama3.1:8b",
    ) as client:
        r = await client.get("/ai/v1/config", headers=_basic_header("alice"))
        assert r.status_code == 200
        body = r.json()
        assert body["configured"] is True
        assert body["base_url_host"] == "ollama.lan"
        assert body["model_id"] == "llama3.1:8b"


@pytest.mark.asyncio
async def test_lookup_blocked_when_not_opted_in(client_factory, configure_ai, app):
    configure_ai(app, {"schema_version": 1, "summary": "ok", "confidence": "low"})
    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        r = await client.post(
            "/ai/v1/insights/lookup",
            headers=_basic_header("alice"),
            json={
                "identity": {"content_hash": "ch1"},
                "bundle": {"title": "Foundation"},
            },
        )
        assert r.status_code == 409
        assert r.json()["detail"] == "not_opted_in"


@pytest.mark.asyncio
async def test_lookup_generates_and_caches(client_factory, configure_ai, app, session):
    orch = configure_ai(
        app, {"schema_version": 1, "summary": "Foundational sci-fi.", "confidence": "high"}
    )
    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        # Opt in first.
        await client.put(
            "/ai/v1/preferences",
            headers=_basic_header("alice"),
            json={"ai_enabled": True},
        )

        # First lookup: cold cache, should generate.
        body = {
            "identity": {"metadata_id": "9780553293357", "content_hash": "ch1"},
            "bundle": {"title": "Foundation", "author": "Isaac Asimov"},
        }
        r1 = await client.post("/ai/v1/insights/lookup", headers=_basic_header("alice"), json=body)
        assert r1.status_code == 200
        assert r1.json()["payload"]["summary"] == "Foundational sci-fi."

        # Second lookup, different user: hot cache, no model call.
        r2 = await client.post("/ai/v1/insights/lookup", headers=_basic_header("bob"), json=body)
        # Bob isn't opted in yet — but cache hits short-circuit BEFORE the opt-in
        # check is reached only on the GET path. POST/lookup still requires opt-in.
        assert r2.status_code == 409  # bob not opted in

        # GET path served from cache (no opt-in required).
        r3 = await client.post(
            "/ai/v1/insights/get",
            headers=_basic_header("bob"),
            json={"identity": {"metadata_id": "9780553293357", "content_hash": "ch1"}},
        )
        assert r3.status_code == 200
        assert r3.json()["payload"]["summary"] == "Foundational sci-fi."


@pytest.mark.asyncio
async def test_invalidate_drops_cache(client_factory, configure_ai, app, session):
    configure_ai(
        app, {"schema_version": 1, "summary": "v1", "confidence": "low"}
    )
    async with client_factory(ai_enabled=True, ai_base_url="http://x", ai_model="m") as client:
        await client.put(
            "/ai/v1/preferences", headers=_basic_header("alice"), json={"ai_enabled": True}
        )
        await client.post(
            "/ai/v1/insights/lookup",
            headers=_basic_header("alice"),
            json={
                "identity": {"content_hash": "ch-inv"},
                "bundle": {"title": "X"},
            },
        )
        rows_before = (await session.execute(select(BookInsight))).scalars().all()
        assert len(rows_before) >= 1

        r = await client.post(
            "/ai/v1/insights/invalidate",
            headers=_basic_header("alice"),
            json={"identity": {"content_hash": "ch-inv"}},
        )
        assert r.status_code == 200
        assert r.json()["deleted"] >= 1

        # GET now misses.
        r2 = await client.post(
            "/ai/v1/insights/get",
            headers=_basic_header("alice"),
            json={"identity": {"content_hash": "ch-inv"}},
        )
        assert r2.status_code == 404
```

The integration suite already exposes a `client_factory` (or equivalent
pytest fixture that yields an `httpx.AsyncClient` against the FastAPI app)
and a session-scoped `session` fixture. Look at
`tests/integration/test_progress.py` and `conftest.py` for the names. If
`client_factory` does not exist, add a minimal fixture in `conftest.py`:

```python
import os
from contextlib import asynccontextmanager

import httpx
import pytest


@pytest.fixture
def client_factory(monkeypatch, postgres_url, alembic_upgrade):
    @asynccontextmanager
    async def _ctx(**env: str | bool):
        # Set env vars via monkeypatch so get_settings() picks them up.
        monkeypatch.setenv("OPDS_SYNC_DATABASE_URL", postgres_url)
        for k, v in env.items():
            monkeypatch.setenv(f"OPDS_SYNC_{k.upper()}", str(v))
        # Reset the cached settings.
        from opds_sync.config import get_settings
        get_settings.cache_clear()
        from opds_sync.main import create_app
        app = create_app()

        # Stub the auth validator to accept any Basic header and use the user portion as the user_id.
        class _StubValidator:
            async def __call__(self, request):
                # mimic CalibreAuthValidator.__call__'s contract
                ...
        # Simpler: monkeypatch current_user_id directly.
        from opds_sync.core import auth as auth_module
        async def _fake_current_user_id(request):
            header = request.headers.get("Authorization", "")
            if not header.startswith("Basic "):
                raise httpx.HTTPError("no auth")
            import base64
            decoded = base64.b64decode(header[6:]).decode()
            return decoded.split(":", 1)[0]
        monkeypatch.setattr(auth_module, "current_user_id", _fake_current_user_id)

        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as ac:
            yield ac
    return _ctx
```

**If a similar fixture already exists in `conftest.py` for the progress
endpoints, mirror its contract instead of building a new one.** The test
uses `app` and `session` as fixtures too — verify those exist or add them.

- [ ] **Step 2: Run integration tests**

Run: `cd server && pytest tests/integration/test_ai_endpoints.py -v`
Expected: 5 PASS.

- [ ] **Step 3: Commit**

```bash
git add server/tests/integration/test_ai_endpoints.py server/tests/conftest.py
git commit -m ":white_check_mark: test(server): /ai/v1 integration tests"
```

---

## Task 11: Server lint + final unit pass

- [ ] **Step 1: Lint**

Run:
```bash
cd server && ruff check . && ruff format --check .
```

Fix any issues with `ruff format .`.

- [ ] **Step 2: Full server test pass**

Run: `cd server && pytest -v`
Expected: all green.

- [ ] **Step 3: Commit any formatting fixes**

```bash
git add server/
git commit -m ":wrench: chore(server): ruff formatting"
```

(If there's nothing to commit, skip.)

---

## Task 12: `:core:metadata` Android module

**Why:** Provide a clean place to extract the metadata bundle from the EPUB
OPF. Reuses the OPF parsing already living in `:core:identity`.

**Files:**
- Modify: `settings.gradle.kts`
- Create: `core/metadata/build.gradle.kts`
- Create: `core/metadata/src/main/java/io/theficos/ereader/core/metadata/MetadataBundle.kt`
- Create: `core/metadata/src/main/java/io/theficos/ereader/core/metadata/OpfMetadataExtractor.kt`
- Create: `core/metadata/src/test/java/io/theficos/ereader/core/metadata/OpfMetadataExtractorTest.kt`
- Create: `core/metadata/src/test/resources/foundation.opf` (fixture)

- [ ] **Step 1: Add the module to `settings.gradle.kts`**

```kotlin
include(
    ":app",
    ":core:model",
    ":core:identity",
    ":core:metadata",
    ":data:local",
    ":data:opds",
    ":data:sync",
    ":data:ai",   // also added in this plan; harmless if not yet present
    ":reader",
    ":auth",
)
```

(`:data:ai` doesn't exist yet — Gradle won't fail at config time as long as
we don't depend on it from anywhere else. If it does fail, comment the line
out and re-add it in Task 13.)

- [ ] **Step 2: Create `core/metadata/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:identity"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
```

- [ ] **Step 3: Implement `MetadataBundle.kt`**

```kotlin
package io.theficos.ereader.core.metadata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The bundle of EPUB metadata sent to the server when requesting AI insights.
 * Mirrors `MetadataBundle` in `server/opds_sync/api/ai_schemas.py`.
 */
@Serializable
data class MetadataBundle(
    val title: String,
    val author: String? = null,
    val language: String? = null,
    val isbn: String? = null,
    val publisher: String? = null,
    @SerialName("publish_date") val publishDate: String? = null,
    val subjects: List<String> = emptyList(),
    val description: String? = null,
    @SerialName("series_name") val seriesName: String? = null,
    @SerialName("series_position") val seriesPosition: Int? = null,
)
```

- [ ] **Step 4: Inspect `:core:identity` for the existing OPF parser**

Run: `grep -n "fun.*opf\|<dc:\|<package" core/identity/src/main/java/io/theficos/ereader/core/identity/EpubIdentityExtractor.kt | head -30`

Expected: there should be code that already parses the OPF XML. The
metadata extractor reuses that parser by either (a) calling into a shared
helper if one exists, or (b) duplicating the XML parsing if it doesn't (fine
— same XML doc, different fields). Inspect and pick the right approach. The
implementation below assumes (b) and is self-contained; collapse to (a) if
`:core:identity` exposes a reusable XML parsing helper.

- [ ] **Step 5: Implement `OpfMetadataExtractor.kt`**

```kotlin
package io.theficos.ereader.core.metadata

import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Extracts a [MetadataBundle] from an OPF document's bytes.
 *
 * Tolerant: missing OPF, malformed XML, or missing `<metadata>` produces a
 * minimal bundle (title only, possibly empty). The caller decides what to do
 * with that.
 */
object OpfMetadataExtractor {

    fun extract(opfBytes: ByteArray, fallbackTitle: String): MetadataBundle {
        val doc = try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isValidating = false
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            factory.newDocumentBuilder().parse(opfBytes.inputStream())
        } catch (_: Exception) {
            return MetadataBundle(title = fallbackTitle)
        }
        val metadataElems = doc.getElementsByTagName("metadata")
        if (metadataElems.length == 0) {
            return MetadataBundle(title = fallbackTitle)
        }
        val metadata = metadataElems.item(0) as Element

        val title = textOf(metadata, "title") ?: fallbackTitle
        val author = textOf(metadata, "creator")
        val language = textOf(metadata, "language")
        val publisher = textOf(metadata, "publisher")
        val publishDate = textOf(metadata, "date")
        val description = textOf(metadata, "description")
        val isbn = identifiersOf(metadata).firstOrNull { it.startsWith("urn:isbn:") || isPlausibleIsbn(it) }
            ?.removePrefix("urn:isbn:")?.removePrefix("isbn:")?.replace("-", "")?.replace(" ", "")
        val subjects = collectText(metadata, "subject")

        // EPUB 3 series via belongs-to-collection (Calibre-style)
        val (seriesName, seriesPosition) = parseSeries(metadata)

        return MetadataBundle(
            title = title.trim(),
            author = author?.trim(),
            language = language?.trim()?.lowercase(),
            isbn = isbn,
            publisher = publisher?.trim(),
            publishDate = publishDate?.trim(),
            subjects = subjects,
            description = description?.trim(),
            seriesName = seriesName?.trim(),
            seriesPosition = seriesPosition,
        )
    }

    private fun textOf(parent: Element, localName: String): String? {
        val nodes = parent.getElementsByTagNameNS("*", localName)
        if (nodes.length == 0) return null
        return nodes.item(0).textContent?.takeIf { it.isNotBlank() }
    }

    private fun collectText(parent: Element, localName: String): List<String> {
        val nodes = parent.getElementsByTagNameNS("*", localName)
        return (0 until nodes.length).mapNotNull { i ->
            nodes.item(i).textContent?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    private fun identifiersOf(parent: Element): List<String> {
        val nodes = parent.getElementsByTagNameNS("*", "identifier")
        return (0 until nodes.length).mapNotNull { i ->
            nodes.item(i).textContent?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    private fun isPlausibleIsbn(s: String): Boolean {
        val cleaned = s.replace("-", "").replace(" ", "")
        return cleaned.length in setOf(10, 13) && cleaned.all { it.isDigit() || it == 'X' }
    }

    /**
     * EPUB 3 collection metadata. Looks for:
     *   <meta property="belongs-to-collection" id="c01">Foundation</meta>
     *   <meta refines="#c01" property="group-position">1</meta>
     * Falls back to Calibre's older format:
     *   <meta name="calibre:series" content="Foundation"/>
     *   <meta name="calibre:series_index" content="1"/>
     */
    private fun parseSeries(metadata: Element): Pair<String?, Int?> {
        // Try Calibre legacy first — it's the most common shape in the wild.
        val metas = metadata.getElementsByTagNameNS("*", "meta")
        var calibreName: String? = null
        var calibreIndex: Int? = null
        for (i in 0 until metas.length) {
            val m = metas.item(i) as? Element ?: continue
            when (m.getAttribute("name")) {
                "calibre:series" -> calibreName = m.getAttribute("content").takeIf { it.isNotBlank() }
                "calibre:series_index" -> calibreIndex = m.getAttribute("content")
                    .toFloatOrNull()?.toInt()
            }
        }
        if (calibreName != null) return calibreName to calibreIndex

        // EPUB 3 belongs-to-collection
        var name: String? = null
        var position: Int? = null
        var collectionId: String? = null
        for (i in 0 until metas.length) {
            val m = metas.item(i) as? Element ?: continue
            if (m.getAttribute("property") == "belongs-to-collection") {
                name = m.textContent?.trim().takeIf { !it.isNullOrEmpty() }
                collectionId = m.getAttribute("id").takeIf { it.isNotBlank() }
                break
            }
        }
        if (collectionId != null) {
            for (i in 0 until metas.length) {
                val m = metas.item(i) as? Element ?: continue
                if (m.getAttribute("refines") == "#$collectionId" &&
                    m.getAttribute("property") == "group-position"
                ) {
                    position = m.textContent?.trim()?.toFloatOrNull()?.toInt()
                }
            }
        }
        return name to position
    }
}
```

- [ ] **Step 6: Create the test fixture `core/metadata/src/test/resources/foundation.opf`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="bookid">urn:isbn:9780553293357</dc:identifier>
    <dc:title>Foundation</dc:title>
    <dc:creator>Isaac Asimov</dc:creator>
    <dc:language>en</dc:language>
    <dc:publisher>Bantam Spectra</dc:publisher>
    <dc:date>1991-10-01</dc:date>
    <dc:subject>Science Fiction</dc:subject>
    <dc:subject>Galactic empire</dc:subject>
    <dc:description>The story of psychohistory and the fall of the Galactic Empire.</dc:description>
    <meta name="calibre:series" content="Foundation"/>
    <meta name="calibre:series_index" content="1.0"/>
  </metadata>
</package>
```

- [ ] **Step 7: Implement `OpfMetadataExtractorTest.kt`**

```kotlin
package io.theficos.ereader.core.metadata

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OpfMetadataExtractorTest {

    private fun loadFixture(name: String): ByteArray =
        checkNotNull(
            this::class.java.classLoader!!.getResourceAsStream(name)
        ) { "fixture not found: $name" }.use { it.readBytes() }

    @Test
    fun `extracts full metadata bundle from foundation opf`() {
        val bundle = OpfMetadataExtractor.extract(loadFixture("foundation.opf"), fallbackTitle = "fallback")
        assertThat(bundle.title).isEqualTo("Foundation")
        assertThat(bundle.author).isEqualTo("Isaac Asimov")
        assertThat(bundle.language).isEqualTo("en")
        assertThat(bundle.publisher).isEqualTo("Bantam Spectra")
        assertThat(bundle.publishDate).isEqualTo("1991-10-01")
        assertThat(bundle.isbn).isEqualTo("9780553293357")
        assertThat(bundle.subjects).containsExactly("Science Fiction", "Galactic empire")
        assertThat(bundle.description).contains("psychohistory")
        assertThat(bundle.seriesName).isEqualTo("Foundation")
        assertThat(bundle.seriesPosition).isEqualTo(1)
    }

    @Test
    fun `falls back to title when opf is malformed`() {
        val bundle = OpfMetadataExtractor.extract(byteArrayOf(0x00, 0x01), fallbackTitle = "Untitled")
        assertThat(bundle.title).isEqualTo("Untitled")
        assertThat(bundle.author).isNull()
    }

    @Test
    fun `parses epub3 belongs-to-collection`() {
        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="x">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="x">x</dc:identifier>
                <dc:title>Foundation and Empire</dc:title>
                <meta property="belongs-to-collection" id="c01">Foundation</meta>
                <meta refines="#c01" property="group-position">2</meta>
              </metadata>
            </package>
        """.trimIndent().toByteArray()
        val bundle = OpfMetadataExtractor.extract(opf, fallbackTitle = "fb")
        assertThat(bundle.seriesName).isEqualTo("Foundation")
        assertThat(bundle.seriesPosition).isEqualTo(2)
    }

    @Test
    fun `extracts isbn from raw identifier`() {
        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier>978-0-14-103614-4</dc:identifier>
                <dc:title>X</dc:title>
              </metadata>
            </package>
        """.trimIndent().toByteArray()
        val bundle = OpfMetadataExtractor.extract(opf, fallbackTitle = "fb")
        assertThat(bundle.isbn).isEqualTo("9780141036144")
    }
}
```

- [ ] **Step 8: Run module tests**

Run: `scripts/dgradle :core:metadata:test`
Expected: 4 PASS.

- [ ] **Step 9: Commit**

```bash
git add settings.gradle.kts core/metadata
git commit -m ":sparkles: feat(android): :core:metadata module — OPF metadata extractor"
```

---

## Task 13: `:data:ai` Android module

**Files:**
- Create: `data/ai/build.gradle.kts`
- Create: `data/ai/src/main/AndroidManifest.xml`
- Create: `data/ai/src/main/java/io/theficos/ereader/data/ai/AiDtos.kt`
- Create: `data/ai/src/main/java/io/theficos/ereader/data/ai/AiClient.kt`
- Create: `data/ai/src/test/java/io/theficos/ereader/data/ai/AiClientTest.kt`

- [ ] **Step 1: Create `data/ai/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.theficos.ereader.data.ai"
    compileSdk = 34
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions { jvmTarget = "21" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    api(project(":core:model"))
    api(project(":core:metadata"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
```

- [ ] **Step 2: Create `data/ai/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

(Empty manifest — module library with no components.)

- [ ] **Step 3: Implement `AiDtos.kt`**

```kotlin
package io.theficos.ereader.data.ai

import io.theficos.ereader.core.metadata.MetadataBundle
import io.theficos.ereader.core.model.DocumentIdentity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AiConfig(
    val configured: Boolean,
    @SerialName("base_url_host") val baseUrlHost: String? = null,
    @SerialName("model_id") val modelId: String? = null,
    @SerialName("sources_enabled") val sourcesEnabled: List<String> = emptyList(),
)

@Serializable
data class AiPreferences(
    @SerialName("ai_enabled") val aiEnabled: Boolean,
)

@Serializable
data class Citation(
    val kind: String,
    val title: String,
    val url: String? = null,
    val snippet: String = "",
)

@Serializable
data class AuthorInsight(
    val bio: String? = null,
    @SerialName("notable_works") val notableWorks: List<String>? = null,
    val nationality: String? = null,
    @SerialName("active_years") val activeYears: String? = null,
)

@Serializable
data class SeriesInsight(
    val name: String,
    val position: Int? = null,
    @SerialName("total_known") val totalKnown: Int? = null,
)

@Serializable
data class BookInsightPayload(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val summary: String? = null,
    val author: AuthorInsight? = null,
    val series: SeriesInsight? = null,
    val themes: List<String>? = null,
    val tone: String? = null,
    @SerialName("content_advisory") val contentAdvisory: List<String>? = null,
    @SerialName("suggested_for") val suggestedFor: String? = null,
    val confidence: String = "low",
    val notes: String? = null,
)

@Serializable
data class BookInsightResponse(
    val payload: BookInsightPayload,
    val sources: List<Citation>,
    @SerialName("model_id") val modelId: String,
    @SerialName("prompt_version") val promptVersion: String,
    @SerialName("generated_at") val generatedAt: String,
)

@Serializable
data class InsightLookupBody(val identity: DocumentIdentity, val bundle: MetadataBundle)

@Serializable
data class InsightGetBody(val identity: DocumentIdentity)
```

- [ ] **Step 4: Implement `AiClient.kt`**

```kotlin
package io.theficos.ereader.data.ai

import io.theficos.ereader.core.metadata.MetadataBundle
import io.theficos.ereader.core.model.DocumentIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * REST client for /ai/v1/* on opds-sync.
 *
 * Auth: relies on the OkHttpClient already having BasicAuthInterceptor wired
 * (the same one used by :data:sync). This client does not add headers.
 */
class AiClient(
    private val baseUrl: String,
    private val http: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun getConfig(): AiConfig =
        get("/ai/v1/config")

    suspend fun getPreferences(): AiPreferences =
        get("/ai/v1/preferences")

    suspend fun setPreferences(enabled: Boolean): AiPreferences =
        put("/ai/v1/preferences", AiPreferences(enabled))

    /** Lookup-or-generate. May block for tens of seconds while a model runs. */
    suspend fun lookupInsight(
        identity: DocumentIdentity,
        bundle: MetadataBundle,
    ): BookInsightResponse =
        post("/ai/v1/insights/lookup", InsightLookupBody(identity, bundle))

    /** Cache-only read. Throws [InsightNotCachedException] on 404. */
    suspend fun getInsight(identity: DocumentIdentity): BookInsightResponse =
        try {
            post("/ai/v1/insights/get", InsightGetBody(identity))
        } catch (e: AiHttpException) {
            if (e.code == 404) throw InsightNotCachedException() else throw e
        }

    suspend fun invalidateInsight(identity: DocumentIdentity) {
        postUnit("/ai/v1/insights/invalidate", InsightGetBody(identity))
    }

    private suspend inline fun <reified T> get(path: String): T =
        execute(Request.Builder().url("$baseUrl$path").get())

    private suspend inline fun <reified Body, reified Resp> post(path: String, body: Body): Resp =
        execute(
            Request.Builder()
                .url("$baseUrl$path")
                .post(json.encodeToString(body).toRequestBody(mediaType))
        )

    private suspend inline fun <reified Body> postUnit(path: String, body: Body) {
        executeRaw(
            Request.Builder()
                .url("$baseUrl$path")
                .post(json.encodeToString(body).toRequestBody(mediaType))
        )
    }

    private suspend inline fun <reified Body, reified Resp> put(path: String, body: Body): Resp =
        execute(
            Request.Builder()
                .url("$baseUrl$path")
                .put(json.encodeToString(body).toRequestBody(mediaType))
        )

    private suspend inline fun <reified Resp> execute(builder: Request.Builder): Resp =
        withContext(Dispatchers.IO) {
            http.newCall(builder.build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw AiHttpException(resp.code, body)
                }
                json.decodeFromString<Resp>(body)
            }
        }

    private suspend fun executeRaw(builder: Request.Builder) {
        withContext(Dispatchers.IO) {
            http.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw AiHttpException(resp.code, resp.body?.string().orEmpty())
                }
            }
        }
    }
}

class AiHttpException(val code: Int, val body: String) :
    RuntimeException("AI request failed: $code body=${body.take(200)}")

class InsightNotCachedException : RuntimeException("insight not cached")
```

- [ ] **Step 5: Implement `AiClientTest.kt`**

```kotlin
package io.theficos.ereader.data.ai

import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.core.metadata.MetadataBundle
import io.theficos.ereader.core.model.DocumentIdentity
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class AiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: AiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = AiClient(
            baseUrl = server.url("").toString().trimEnd('/'),
            http = OkHttpClient.Builder()
                .callTimeout(5, TimeUnit.SECONDS)
                .build(),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `getConfig parses response`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"configured":true,"base_url_host":"ollama.lan","model_id":"llama3.1:8b","sources_enabled":["wikipedia"]}"""
            )
        )
        val cfg = client.getConfig()
        assertThat(cfg.configured).isTrue()
        assertThat(cfg.baseUrlHost).isEqualTo("ollama.lan")
        assertThat(cfg.modelId).isEqualTo("llama3.1:8b")
        assertThat(cfg.sourcesEnabled).containsExactly("wikipedia")
    }

    @Test
    fun `setPreferences sends PUT with body`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"ai_enabled":true}""")
        )
        val out = client.setPreferences(true)
        val req: RecordedRequest = server.takeRequest()
        assertThat(req.method).isEqualTo("PUT")
        assertThat(req.path).isEqualTo("/ai/v1/preferences")
        assertThat(req.body.readUtf8()).contains("\"ai_enabled\":true")
        assertThat(out.aiEnabled).isTrue()
    }

    @Test
    fun `lookupInsight serializes identity and bundle`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"payload":{"schema_version":1,"summary":"hi","confidence":"high"},"sources":[],"model_id":"m","prompt_version":"1","generated_at":"2026-05-09T00:00:00+00:00"}"""
            )
        )
        val bundle = MetadataBundle(title = "Foundation", author = "Isaac Asimov")
        val out = client.lookupInsight(
            DocumentIdentity(metadataId = "x", contentHash = "ch"),
            bundle,
        )
        val req = server.takeRequest()
        assertThat(req.path).isEqualTo("/ai/v1/insights/lookup")
        assertThat(req.body.readUtf8()).contains("Foundation")
        assertThat(out.payload.summary).isEqualTo("hi")
    }

    @Test
    fun `getInsight throws InsightNotCachedException on 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"not_cached"}"""))
        try {
            client.getInsight(DocumentIdentity(null, "ch"))
            error("expected throw")
        } catch (e: InsightNotCachedException) {
            // expected
        }
    }

    @Test
    fun `non 2xx other than 404 throws AiHttpException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":"not_opted_in"}"""))
        try {
            client.lookupInsight(
                DocumentIdentity(null, "ch"),
                MetadataBundle(title = "X"),
            )
            error("expected throw")
        } catch (e: AiHttpException) {
            assertThat(e.code).isEqualTo(409)
        }
    }
}
```

The test imports `DocumentIdentity` from `:core:model`. Verify it exists:

```bash
grep -n "class DocumentIdentity\|data class DocumentIdentity" core/model/src/main/java/io/theficos/ereader/core/model/DocumentIdentity.kt
```

If the property names differ from `metadataId`/`contentHash`, adjust the
`AiDtos.kt` and the test accordingly.

- [ ] **Step 6: Run module tests**

Run: `scripts/dgradle :data:ai:test`
Expected: 5 PASS.

- [ ] **Step 7: Commit**

```bash
git add data/ai
git commit -m ":sparkles: feat(android): :data:ai module — REST client for /ai/v1"
```

---

## Task 14: Wire `:data:ai` into `:app`'s DI

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/io/theficos/ereader/di/AppContainer.kt`
- Create: `app/src/main/java/io/theficos/ereader/data/ai/AiRepository.kt`

- [ ] **Step 1: Add module deps to `app/build.gradle.kts`**

Add to the `dependencies { ... }` block (preserve all existing entries):

```kotlin
implementation(project(":core:metadata"))
implementation(project(":data:ai"))
```

- [ ] **Step 2: Inspect `AppContainer.kt`**

Run: `cat app/src/main/java/io/theficos/ereader/di/AppContainer.kt | head -120`

Identify the OkHttpClient instance used by `:data:sync` (it's the
`BasicAuthInterceptor`-wired client). The AI client must reuse it so it
inherits the calibre-web Basic-auth header.

- [ ] **Step 3: Add `aiClient` to `AppContainer`**

In `AppContainer.kt`, after the existing sync-client construction, add:

```kotlin
val aiClient: io.theficos.ereader.data.ai.AiClient by lazy {
    io.theficos.ereader.data.ai.AiClient(
        baseUrl = serverConfig.opdsSyncBaseUrl,  // same URL as the sync client
        http = syncOkHttp,                       // the existing auth-wired client
    )
}
```

The exact names (`serverConfig.opdsSyncBaseUrl`, `syncOkHttp`) depend on
what's already in `AppContainer.kt`. Use whatever the sync client uses.

- [ ] **Step 4: Implement `AiRepository.kt`**

```kotlin
package io.theficos.ereader.data.ai

import io.theficos.ereader.core.metadata.MetadataBundle
import io.theficos.ereader.core.model.DocumentIdentity
import io.theficos.ereader.data.ai.AiClient
import io.theficos.ereader.data.ai.AiConfig
import io.theficos.ereader.data.ai.AiPreferences
import io.theficos.ereader.data.ai.BookInsightResponse
import io.theficos.ereader.data.ai.InsightNotCachedException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-app cache + facade over [AiClient]. Holds the latest server config and
 * user preferences, and exposes simple suspend functions the UI uses.
 *
 * Caching: AiConfig and AiPreferences are cached in-process; the UI
 * subscribes to the StateFlows. They re-fetch on app launch via [refresh].
 */
class AiRepository(
    private val client: AiClient,
) {
    private val _config = MutableStateFlow<AiConfig?>(null)
    val config: StateFlow<AiConfig?> = _config.asStateFlow()

    private val _prefs = MutableStateFlow<AiPreferences?>(null)
    val preferences: StateFlow<AiPreferences?> = _prefs.asStateFlow()

    private val refreshMutex = Mutex()

    suspend fun refresh() = refreshMutex.withLock {
        runCatching { client.getConfig() }.getOrNull()?.let { _config.value = it }
        runCatching { client.getPreferences() }.getOrNull()?.let { _prefs.value = it }
    }

    suspend fun setEnabled(enabled: Boolean) {
        val out = client.setPreferences(enabled)
        _prefs.value = out
    }

    suspend fun lookupInsight(
        identity: DocumentIdentity,
        bundle: MetadataBundle,
    ): BookInsightResponse = client.lookupInsight(identity, bundle)

    suspend fun getCachedInsight(identity: DocumentIdentity): BookInsightResponse? =
        try {
            client.getInsight(identity)
        } catch (_: InsightNotCachedException) {
            null
        }

    suspend fun invalidate(identity: DocumentIdentity) {
        client.invalidateInsight(identity)
    }
}
```

- [ ] **Step 5: Wire `AiRepository` into `AppContainer`**

```kotlin
val aiRepository: io.theficos.ereader.data.ai.AiRepository by lazy {
    io.theficos.ereader.data.ai.AiRepository(aiClient)
}
```

- [ ] **Step 6: Compile**

Run: `scripts/dgradle :app:compileDebugKotlin`
Expected: SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/io/theficos/ereader/di/AppContainer.kt app/src/main/java/io/theficos/ereader/data/ai/AiRepository.kt
git commit -m ":sparkles: feat(app): wire AiClient + AiRepository into AppContainer"
```

---

## Task 15: Settings AI section (UI + viewmodel)

**Files:**
- Modify: `app/src/main/java/io/theficos/ereader/ui/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/io/theficos/ereader/ui/settings/SettingsScreen.kt`

- [ ] **Step 1: Inspect existing screen + viewmodel**

Run: `cat app/src/main/java/io/theficos/ereader/ui/settings/SettingsViewModel.kt | head -80`
Run: `cat app/src/main/java/io/theficos/ereader/ui/settings/SettingsScreen.kt | head -80`

Identify the existing state shape (likely a sealed class / data class
holding loaded state). Match its style.

- [ ] **Step 2: Add AI state to `SettingsViewModel`**

Add to the existing viewmodel class (preserving everything else):

```kotlin
import io.theficos.ereader.data.ai.AiConfig
import io.theficos.ereader.data.ai.AiPreferences
import io.theficos.ereader.data.ai.AiRepository
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

// In the constructor params (alongside existing ones), add: aiRepository: AiRepository
// In init { ... } add: viewModelScope.launch { aiRepository.refresh() }

data class AiState(
    val config: AiConfig? = null,
    val preferences: AiPreferences? = null,
    val toggling: Boolean = false,
)

val ai: StateFlow<AiState> = combine(
    aiRepository.config,
    aiRepository.preferences,
) { c, p -> AiState(config = c, preferences = p) }.stateIn(
    viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), AiState()
)

fun toggleAi(enabled: Boolean) {
    viewModelScope.launch {
        runCatching { aiRepository.setEnabled(enabled) }
    }
}

fun setStyleTone(tone: String) {
    viewModelScope.launch {
        runCatching { aiRepository.setStyleTone(tone) }
    }
}
```

`AiRepository.setStyleTone` reads the current preferences, mutates the `style.tone`
field, and PUTs the result. Keep the rest of the style block untouched. The PUT body
sends `{"style": { ...full style... }}` — server's `PreferencesBody.style` is optional
but when present must be a full `AiStyle`.

- [ ] **Step 3: Add the AI section to `SettingsScreen`**

Add a new section in the screen below existing sections. Match the screen's
existing style for section headers and list items:

```kotlin
val aiState by viewModel.ai.collectAsState()

SectionHeader("AI features")

if (aiState.config?.configured != true) {
    ListItem(
        headlineContent = { Text("AI not configured") },
        supportingContent = {
            Text(
                "Your administrator has not configured an AI endpoint on this server.",
                style = MaterialTheme.typography.bodySmall,
            )
        },
    )
} else {
    val cfg = aiState.config!!
    val enabled = aiState.preferences?.aiEnabled == true
    ListItem(
        headlineContent = { Text("Enable AI features for this account") },
        supportingContent = {
            Column {
                Text(
                    "When enabled, Quire sends the title, author, and other " +
                        "EPUB metadata of books you open to ${cfg.baseUrlHost ?: "the AI endpoint"} " +
                        "(model ${cfg.modelId}) to generate insights.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (cfg.sourcesEnabled.isNotEmpty()) {
                    Text(
                        "External sources used: ${cfg.sourcesEnabled.joinToString(", ")}.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "Nothing is sent until you opt in.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        trailingContent = {
            Switch(
                checked = enabled,
                onCheckedChange = { viewModel.toggleAi(it) },
            )
        },
    )

    if (enabled) {
        // Personalization — v0: just the Tone dropdown. Other knobs (length,
        // author_focus, spoilers, interests) hidden until we've evaluated the
        // first batch of insights and decided which actually move the needle.
        val tone = aiState.preferences?.style?.tone ?: "neutral"
        var menuOpen by remember { mutableStateOf(false) }
        ListItem(
            headlineContent = { Text("Insight tone") },
            supportingContent = {
                Text(
                    "How book insights are written.",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            trailingContent = {
                TextButton(onClick = { menuOpen = true }) { Text(tone.replaceFirstChar { it.uppercase() }) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    listOf("neutral", "enthusiastic", "scholarly", "casual").forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.replaceFirstChar { it.uppercase() }) },
                            onClick = {
                                viewModel.setStyleTone(option)
                                menuOpen = false
                            },
                        )
                    }
                }
            },
        )
        // (Advanced knobs — length, author focus, spoilers, interests — deliberately
        // omitted from v0. Add when we have user feedback on insight quality.)
    }
}
```

If the screen uses a different list-item primitive than Material 3
`ListItem`, adapt accordingly. The composable layout doesn't matter — what
matters is that the Switch calls `viewModel.toggleAi(it)` and the dropdown
calls `viewModel.setStyleTone(option)`.

- [ ] **Step 4: Compile and run lint**

Run: `scripts/dgradle :app:compileDebugKotlin`
Expected: SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/theficos/ereader/ui/settings
git commit -m ":sparkles: feat(android): Settings AI section with disclosure + opt-in toggle"
```

---

## Task 16: BookDetailScreen + viewmodel + insight cards

> **UX direction (revision):** BookDetailScreen is **off the read path** — it
> is reached only via an explicit info icon on library tiles, never via a
> plain tap. Tap-to-read remains a single gesture (Task 17). The screen
> therefore does NOT need a "Read" button as its primary action; reading is
> something the user does from the library, not from here. The screen is for
> *inspecting* a book (insights, regenerate, future "find similar" hooks).

**Files:**
- Create: `app/src/main/java/io/theficos/ereader/ui/bookdetail/BookDetailScreen.kt`
- Create: `app/src/main/java/io/theficos/ereader/ui/bookdetail/BookDetailViewModel.kt`
- Create: `app/src/main/java/io/theficos/ereader/ui/bookdetail/InsightCards.kt`

- [ ] **Step 1: Implement `BookDetailViewModel.kt`**

```kotlin
package io.theficos.ereader.ui.bookdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.ereader.core.metadata.MetadataBundle
import io.theficos.ereader.core.metadata.OpfMetadataExtractor
import io.theficos.ereader.core.model.Document
import io.theficos.ereader.core.model.DocumentIdentity
import io.theficos.ereader.data.ai.AiHttpException
import io.theficos.ereader.data.ai.AiRepository
import io.theficos.ereader.data.ai.BookInsightPayload
import io.theficos.ereader.data.ai.BookInsightResponse
import io.theficos.ereader.data.ai.Citation
import io.theficos.ereader.data.local.DocumentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface InsightUiState {
    data object Hidden : InsightUiState                 // AI not configured / not opted in
    data object Loading : InsightUiState                // first-fetch in flight
    data class Loaded(val payload: BookInsightPayload, val sources: List<Citation>) : InsightUiState
    data class Error(val message: String) : InsightUiState
}

data class BookDetailState(
    val document: Document? = null,
    val insight: InsightUiState = InsightUiState.Hidden,
)

class BookDetailViewModel(
    private val documentId: Long,
    private val documents: DocumentRepository,
    private val ai: AiRepository,
    private val openOpfBytes: suspend (Document) -> ByteArray?,  // injected to avoid binding to Readium here
) : ViewModel() {

    private val _state = MutableStateFlow(BookDetailState())
    val state: StateFlow<BookDetailState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val doc = documents.findById(documentId) ?: run {
            _state.value = BookDetailState(insight = InsightUiState.Hidden)
            return
        }
        _state.value = BookDetailState(document = doc)

        val cfg = ai.config.value
        val pref = ai.preferences.value
        if (cfg?.configured != true || pref?.aiEnabled != true) {
            _state.value = _state.value.copy(insight = InsightUiState.Hidden)
            return
        }

        // Try cached read first; do not wait for OPF parsing if cache hits.
        val ident = DocumentIdentity(
            metadataId = doc.identity.metadataId,
            contentHash = doc.identity.contentHash,
        )
        val cached = runCatching { ai.getCachedInsight(ident) }.getOrNull()
        if (cached != null) {
            _state.value = _state.value.copy(
                insight = InsightUiState.Loaded(cached.payload, cached.sources),
            )
            return
        }

        _state.value = _state.value.copy(insight = InsightUiState.Loading)
        val opfBytes = openOpfBytes(doc)
        val bundle = if (opfBytes != null) {
            OpfMetadataExtractor.extract(opfBytes, fallbackTitle = doc.title)
        } else {
            MetadataBundle(title = doc.title, author = doc.author)
        }
        runCatching { ai.lookupInsight(ident, bundle) }
            .onSuccess { resp ->
                _state.value = _state.value.copy(
                    insight = InsightUiState.Loaded(resp.payload, resp.sources),
                )
            }
            .onFailure { e ->
                val msg = when (e) {
                    is AiHttpException -> "Couldn't generate insights (${e.code})."
                    else -> "Couldn't generate insights."
                }
                _state.value = _state.value.copy(insight = InsightUiState.Error(msg))
            }
    }

    fun retry() {
        viewModelScope.launch { load() }
    }

    /** User reported the previous insight is wrong/unsatisfying. `reason` is required
     *  (server validates min_length=1). Counts against the per-user regen daily limit
     *  — surface 429 as a friendly message in the UI. */
    fun regenerate(reason: String) {
        viewModelScope.launch {
            val doc = state.value.document ?: return@launch
            val ident = DocumentIdentity(
                metadataId = doc.identity.metadataId,
                contentHash = doc.identity.contentHash,
            )
            _state.value = _state.value.copy(insight = InsightUiState.Loading)
            // The server needs the bundle again (regen body is identical shape to lookup)
            // — reuse the same OPF extraction path. If OPF read fails fall back to the
            // local doc fields.
            val opfBytes = openOpfBytes(doc)
            val bundle = if (opfBytes != null) {
                OpfMetadataExtractor.extract(opfBytes, fallbackTitle = doc.title)
            } else {
                MetadataBundle(title = doc.title, author = doc.author)
            }
            runCatching { ai.regenerateInsight(ident, bundle, reason) }
                .onSuccess { resp ->
                    _state.value = _state.value.copy(
                        insight = InsightUiState.Loaded(resp.payload, resp.sources),
                    )
                }
                .onFailure { e ->
                    val msg = when {
                        e is AiHttpException && e.code == 429 ->
                            "You've reached today's regeneration limit. Try again tomorrow."
                        e is AiHttpException -> "Couldn't regenerate (${e.code})."
                        else -> "Couldn't regenerate."
                    }
                    _state.value = _state.value.copy(insight = InsightUiState.Error(msg))
                }
        }
    }
}
```

`AiRepository.regenerateInsight(ident, bundle, reason)` is a thin wrapper around
`AiClient.regenerateInsight` (Task 13). Add a method there too — body shape is the
same as `InsightLookupBody` plus a `reason: String` field. Server endpoint:
`POST /ai/v1/insights/regenerate`.

The `openOpfBytes` callback is wired by the screen's host (the nav graph or
the app's container) — typically by opening the EPUB at `doc.localPath`,
locating the OPF entry inside the ZIP, reading its bytes. If a helper
already exists in `:core:identity` for this (it likely does, since identity
extraction needs the OPF too), reuse it.

- [ ] **Step 2: Implement `InsightCards.kt`**

```kotlin
package io.theficos.ereader.ui.bookdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.theficos.ereader.data.ai.AuthorInsight
import io.theficos.ereader.data.ai.BookInsightPayload
import io.theficos.ereader.data.ai.Citation
import io.theficos.ereader.data.ai.SeriesInsight

@Composable
fun InsightSection(state: InsightUiState, onRetry: () -> Unit) {
    when (state) {
        InsightUiState.Hidden -> Unit
        InsightUiState.Loading -> LoadingCard()
        is InsightUiState.Error -> ErrorCard(state.message, onRetry)
        is InsightUiState.Loaded -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.payload.summary?.let { SummaryCard(it, suggestedFor = state.payload.suggestedFor) }
            state.payload.author?.let { AuthorCard(it) }
            state.payload.series?.let { SeriesCard(it) }
            state.payload.themes?.takeIf { it.isNotEmpty() }?.let {
                ThemesCard(themes = it, advisory = state.payload.contentAdvisory)
            }
            if (state.sources.isNotEmpty()) SourcesFooter(state.sources)
        }
    }
}

@Composable
private fun LoadingCard() {
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Generating insights…", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun SummaryCard(summary: String, suggestedFor: String?) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("About this book", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(summary, style = MaterialTheme.typography.bodyMedium)
            if (!suggestedFor.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text("Suggested for: $suggestedFor", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun AuthorCard(a: AuthorInsight) {
    if (a.bio.isNullOrBlank() && a.notableWorks.isNullOrEmpty()) return
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("About the author", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            a.bio?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            a.notableWorks?.takeIf { it.isNotEmpty() }?.let {
                Spacer(Modifier.height(6.dp))
                Text("Notable works: ${it.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SeriesCard(s: SeriesInsight) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Series", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            val pos = s.position?.toString().orEmpty()
            val total = s.totalKnown?.let { " of $it" } ?: ""
            Text("${s.name}${if (pos.isNotEmpty()) " — book $pos$total" else ""}")
        }
    }
}

@Composable
private fun ThemesCard(themes: List<String>, advisory: List<String>?) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Themes", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(themes.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            advisory?.takeIf { it.isNotEmpty() }?.let {
                Spacer(Modifier.height(6.dp))
                Text("Content advisory: ${it.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SourcesFooter(sources: List<Citation>) {
    val uriHandler = LocalUriHandler.current
    val labels = sources.mapNotNull { c ->
        when (c.kind) {
            "wikipedia" -> "Wikipedia" to c.url
            "openlibrary" -> "OpenLibrary" to c.url
            "model" -> "AI model: ${c.title}" to null
            "opf" -> "Book metadata" to null
            else -> c.title to c.url
        }
    }
    val text = buildAnnotatedString {
        append("Based on: ")
        labels.forEachIndexed { i, (label, url) ->
            if (i > 0) append(" · ")
            if (url != null) {
                pushStringAnnotation("URL", url)
                withStyle(SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)) {
                    append(label)
                }
                pop()
            } else {
                append(label)
            }
        }
    }
    ClickableText(
        text = text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        onClick = { offset ->
            text.getStringAnnotations("URL", offset, offset).firstOrNull()?.let {
                uriHandler.openUri(it.item)
            }
        },
    )
}
```

- [ ] **Step 3: Implement `BookDetailScreen.kt`**

```kotlin
package io.theficos.ereader.ui.bookdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun BookDetailScreen(
    viewModel: BookDetailViewModel,
    onOpenReader: (documentId: Long) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val doc = state.document
    var regenDialogOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(doc?.title ?: "Book") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (doc != null) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(doc.title, style = MaterialTheme.typography.headlineSmall)
                    doc.author?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                    // Secondary action — primary read flow lives on the library tile.
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { onOpenReader(doc.id) }) { Text("Open in reader") }
                }
            }
            InsightSection(state.insight, onRetry = { viewModel.retry() })
            if (state.insight is InsightUiState.Loaded) {
                TextButton(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    onClick = { regenDialogOpen = true },
                ) { Text("Not quite right? Regenerate") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (regenDialogOpen) {
        RegenerateDialog(
            onDismiss = { regenDialogOpen = false },
            onSubmit = { reason ->
                regenDialogOpen = false
                viewModel.regenerate(reason)
            },
        )
    }
}

@Composable
private fun RegenerateDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Regenerate insight") },
        text = {
            Column {
                Text(
                    "Tell the AI what was wrong or missing. This counts against your daily regeneration budget.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it.take(500) },
                    label = { Text("Reason") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = reason.isNotBlank(),
                onClick = { onSubmit(reason.trim()) },
            ) { Text("Regenerate") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
```

Imports needed in addition to those already shown: `androidx.compose.material3.AlertDialog`,
`androidx.compose.material3.OutlinedTextField`, `androidx.compose.material3.TextButton`,
`androidx.compose.runtime.{mutableStateOf, remember, setValue, getValue}`.

- [ ] **Step 4: Compile**

Run: `scripts/dgradle :app:compileDebugKotlin`
Expected: SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/theficos/ereader/ui/bookdetail
git commit -m ":sparkles: feat(android): BookDetailScreen + AI insight cards"
```

---

## Task 17: Wire BookDetailScreen into navigation (as a side branch)

> **UX direction (revision):** The library tile's primary tap behavior is
> **unchanged** — it still downloads-then-opens the reader in one gesture.
> BookDetailScreen is reachable only via an explicit **info icon** on each
> tile. Result: zero-friction reading for the common case, optional AI
> inspection for users who want it. Long-press is reserved for future
> selection / multi-select operations.

**Files:**
- Modify: `app/src/main/java/io/theficos/ereader/ui/AppNavGraph.kt`
- Modify: `app/src/main/java/io/theficos/ereader/ui/library/LibraryScreen.kt` — add the info icon and an `onShowDetails` callback.
- Modify: `app/src/main/java/io/theficos/ereader/di/AppContainer.kt` (add a viewmodel factory + opf-bytes helper)

- [ ] **Step 1: Inspect existing nav graph + library screen**

Run: `cat app/src/main/java/io/theficos/ereader/ui/AppNavGraph.kt`
Run: `cat app/src/main/java/io/theficos/ereader/ui/library/LibraryScreen.kt | head -120`

Identify the existing `library` and `reader/{id}` routes and the
`onOpenBook = { id -> nav.navigate("reader/$id") }` callback. Note the
component used to render each tile (often a `Card` or `ListItem` inside an
`LazyColumn`/`LazyVerticalGrid`).

- [ ] **Step 2: Add a `book/{id}` route — non-replacing, parallel to `reader/{id}`**

In `AppNavGraph.kt`, add a new composable destination *in addition to* the
existing `reader/{id}` route. Do NOT change the existing route.

```kotlin
composable(
    route = "book/{id}",
    arguments = listOf(navArgument("id") { type = NavType.LongType }),
) { backStack ->
    val id = backStack.arguments!!.getLong("id")
    val viewModel = remember(id) {
        appContainer.bookDetailViewModelFactory.create(id)
    }
    BookDetailScreen(
        viewModel = viewModel,
        onOpenReader = { docId -> nav.navigate("reader/$docId") },
        onBack = { nav.popBackStack() },
    )
}
```

- [ ] **Step 3: Add an info icon to library tiles**

In `LibraryScreen.kt`, extend the existing tile composable to take a new
`onShowDetails: (Long) -> Unit` callback. Add a small `IconButton` showing
`Icons.Outlined.Info` (or `Icons.Filled.InfoOutline` if your screen already
imports a different icon set) in the trailing slot of each tile.

```kotlin
// Where each book row/card is built:
ListItem(
    headlineContent = { Text(book.title) },
    supportingContent = { book.author?.let { Text(it) } },
    leadingContent = { /* existing cover */ },
    trailingContent = {
        IconButton(onClick = { onShowDetails(book.id) }) {
            Icon(Icons.Outlined.Info, contentDescription = "Book details and AI insights")
        }
    },
    modifier = Modifier.clickable { onOpenBook(book.id) },  // unchanged primary action
)
```

Notes:
- The tile's `clickable { onOpenBook(book.id) }` is **unchanged** — primary tap
  still triggers download/open as before.
- Only the trailing slot is new. If the existing screen uses a `Card` instead
  of `ListItem`, place the `IconButton` in the top-right corner using a `Box`
  with `Modifier.align(Alignment.TopEnd)`.
- Only render the info icon when AI is configured server-side (read
  `aiRepository.config.value?.configured == true`). When AI is off, the icon is
  pure clutter — hide it.

- [ ] **Step 4: Pass `onShowDetails` through to the nav graph**

In `AppNavGraph.kt`, where `LibraryScreen` is invoked:

```kotlin
LibraryScreen(
    viewModel = libraryViewModel,
    onOpenBook = { id -> nav.navigate("reader/$id") },        // unchanged
    onShowDetails = { id -> nav.navigate("book/$id") },       // NEW — info-icon target
)
```

The existing `onOpenBook` callback is preserved verbatim — only `onShowDetails`
is new.

- [ ] **Step 5: Add the viewmodel factory + OPF helper to `AppContainer`**

```kotlin
class BookDetailViewModelFactory(
    private val documents: DocumentRepository,
    private val ai: AiRepository,
    private val openOpfBytes: suspend (Document) -> ByteArray?,
) {
    fun create(documentId: Long) = BookDetailViewModel(
        documentId = documentId,
        documents = documents,
        ai = ai,
        openOpfBytes = openOpfBytes,
    )
}

val bookDetailViewModelFactory: BookDetailViewModelFactory by lazy {
    BookDetailViewModelFactory(
        documents = documentRepository,           // existing
        ai = aiRepository,                        // from Task 14
        openOpfBytes = { doc -> readOpfBytes(doc) },
    )
}

private suspend fun readOpfBytes(doc: Document): ByteArray? = withContext(Dispatchers.IO) {
    runCatching {
        java.util.zip.ZipFile(doc.localPath).use { zip ->
            // EPUB convention: META-INF/container.xml points to the OPF.
            val container = zip.getEntry("META-INF/container.xml") ?: return@use null
            val containerXml = zip.getInputStream(container).readBytes().decodeToString()
            val opfPath = Regex("""full-path="([^"]+)"""")
                .find(containerXml)?.groupValues?.get(1)
                ?: return@use null
            val opfEntry = zip.getEntry(opfPath) ?: return@use null
            zip.getInputStream(opfEntry).readBytes()
        }
    }.getOrNull()
}
```

If `:core:identity` already exposes a function like
`fun readOpfFromEpub(path: String): ByteArray?`, use that instead and delete
the inline copy above.

- [ ] **Step 6: Compile + manual smoke**

Run: `scripts/dgradle :app:assembleDebug`
Expected: SUCCESS.

Manually verify both the unchanged read path **and** the new inspect path:
1. Install the debug APK on a device or emulator.
2. Configure opds-sync env: `OPDS_SYNC_AI_ENABLED=true`,
   `OPDS_SYNC_AI_BASE_URL=http://<your-ai-endpoint>/v1`,
   `OPDS_SYNC_AI_MODEL=<your-model-id>`,
   `OPDS_SYNC_AI_SOURCES=wikipedia,openlibrary`.
3. App with AI **off** in Settings: tap a book → reader opens directly,
   one tap, no detail screen, no info icon visible on tiles.
4. Settings → AI features → toggle on. Verify the disclosure text mentions
   the configured host and model.
5. Return to library: info icon appears in the trailing slot of each tile.
6. Tap a tile (primary action): reader opens — **no detour through book detail**.
   Confirm there is no extra latency vs. AI-off.
7. Back out, tap the info icon on the same tile: BookDetailScreen opens. After
   ≤60s the insight cards populate. Sources footer is clickable.
8. Reopen the detail for the same book: insights load instantly from cache.
9. Tap "Not quite right? Regenerate", enter a short reason, confirm. New cards
   replace the old; the regen counter increments server-side. Repeat past the
   `AI_REGEN_DAILY_LIMIT` ceiling and confirm the user-friendly 429 message.
10. Tap "Open in reader" from the detail screen — reader opens as expected.

Tail `opds-sync` logs for `ai.generate` lines to confirm:
- Step 6 triggers zero `ai.generate` calls (tap-to-read does not fetch insights).
- Step 7 triggers exactly one `ai.generate`.
- Step 8 triggers zero (cache hit).
- Step 9 triggers one with `regen=True`.

If the manual run reveals UI bugs (cropping, missing fields, etc.), fix
them before committing.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/theficos/ereader/ui/AppNavGraph.kt \
        app/src/main/java/io/theficos/ereader/ui/library/LibraryScreen.kt \
        app/src/main/java/io/theficos/ereader/di/AppContainer.kt
git commit -m ":sparkles: feat(android): info-icon → BookDetailScreen (off the read path)"
```

---

## Task 18: README + sync-api docs + fastlane

**Files:**
- Modify: `README.md`
- Modify: `docs/sync-api.md`
- Modify: `fastlane/metadata/android/en-US/full_description.txt`

- [ ] **Step 1: Update `README.md`**

Find the sentence in the "Privacy" section that reads:

> Network calls go to exactly two places: your calibre-web instance and your opds-sync server.

Replace with:

```
Network calls go to your calibre-web instance and your opds-sync server.
If your administrator has enabled AI features and you have opted in,
opds-sync will additionally call the AI endpoint your administrator
configured (such as a self-hosted Ollama, or a third-party provider you
have chosen) and the public Wikipedia and OpenLibrary APIs to ground the
generated insights. None of these AI-related calls happen unless you
opt in from Quire's settings; the Android app itself talks only to your
calibre-web instance and your opds-sync server.
```

Also append a new section just before "Install":

```
## AI features (optional)

Quire optionally calls AI for book insights and library analysis. AI is
**off by default**. The opds-sync admin enables it server-side by
configuring an OpenAI-compatible endpoint (Ollama, llama.cpp, vLLM,
OpenAI, OpenRouter, …); each user then opts in from Quire's settings.

When enabled, opds-sync sends the EPUB metadata (title, author,
publisher, description, subjects) of books a user opens to the
configured AI endpoint, plus deterministic queries to Wikipedia and
OpenLibrary to ground the generated insights with citations. The
generated insight is cached server-side per book and reused across all
of that user's devices and other opted-in users on the same instance.

For configuration details see [`server/README.md`](server/README.md).
```

- [ ] **Step 2: Update `docs/sync-api.md`**

Append a new top-level section documenting the `/ai/v1` surface. Document
each of the seven endpoints with method, path, body, response, and the opt-in
gating rules. Use the existing tone/format of the doc.

```markdown
## AI endpoints (`/ai/v1`)

Optional. Returns 503 when the server is not configured for AI
(`OPDS_SYNC_AI_ENABLED=false` or missing `AI_BASE_URL`/`AI_MODEL`).

### Quota model

Two layers protect the configured AI endpoint:

- **`AI_RATE_PER_MIN`** (default 10): process-wide token bucket against the
  AI provider. Cache reads bypass it; first-time generations may queue
  briefly under load.
- **`AI_DAILY_BUDGET`** (default 200) and **`AI_REGEN_DAILY_LIMIT`**
  (default 3): per-user counters in `ai_usage_daily`. Exceeding either
  returns **429** with a JSON body `{ "used", "limit", "resets_at" }` and
  a `Retry-After` header (seconds until next UTC midnight). Set
  `AI_DAILY_BUDGET=0` to disable the per-user cap.

### `GET /ai/v1/config`

Returns the user-visible AI configuration. Public to authed users.

```json
{
  "configured": true,
  "base_url_host": "ollama.example.lan",
  "model_id": "llama3.1:8b",
  "sources_enabled": ["wikipedia", "openlibrary"],
  "daily_budget": 200,
  "regen_daily_limit": 3
}
```

### `GET /ai/v1/preferences` / `PUT /ai/v1/preferences`

Per-user opt-in flag plus style personalization knobs.

```json
{
  "ai_enabled": true,
  "style": {
    "tone": "neutral",
    "length": "standard",
    "author_focus": "moderate",
    "include_spoilers": false,
    "interests": ["themes", "writing_style"]
  }
}
```

PUT accepts either field independently — send `{ "ai_enabled": true }` to flip
the toggle without changing style, or `{ "style": { ... full AiStyle ... } }`
to update preferences without touching opt-in. Response always returns the full
resolved state.

### `POST /ai/v1/insights/lookup`

Cache hit returns the existing insight; cache miss generates synchronously.
Requires opt-in. May return **429** with `{ "used", "limit", "resets_at" }`
and `Retry-After` if the user's daily budget is exhausted. Body:

```json
{
  "identity": { "metadata_id": "9780553293357", "content_hash": "abc..." },
  "bundle":   { "title": "Foundation", "author": "Isaac Asimov", "...": "..." }
}
```

Response: a `BookInsight` with `payload`, `sources`, `model_id`,
`prompt_version`, `generated_at`. See `opds_sync/api/ai_schemas.py` for
the full payload schema.

### `POST /ai/v1/insights/regenerate`

Force a fresh generation. The existing live row is marked `superseded_at` (kept
for audit; queries return the new live row only). The new row records the
previous row's id in `previous_insight_ids`.

Requires opt-in. Subject to a tighter daily ceiling
(`AI_REGEN_DAILY_LIMIT`, default 3 per user). Returns 429 with the same body
shape as `lookup` when exceeded.

Body adds a required `reason`:

```json
{
  "identity": { "metadata_id": "...", "content_hash": "..." },
  "bundle":   { "title": "...", "...": "..." },
  "reason":   "Author bio claimed they were French; Asimov was Russian-American."
}
```

The reason is included in the prompt sent to the model so it knows what to fix.

### `POST /ai/v1/insights/get`

Cache-only read. 404 on miss. Does NOT require opt-in (cached results are
shared across users and remain readable by users who later opt out).

### `POST /ai/v1/insights/invalidate`

Drops the cached row for the current `(model_id, prompt_version)`. Use this
for admin-style cache busting; users hit `regenerate` instead, which is
budgeted. Requires opt-in. Returns `{"deleted": <int>}`.
```

- [ ] **Step 3: Update `fastlane/metadata/android/en-US/full_description.txt`**

Append at the end (preserving everything above):

```
Optional AI features (off by default): when your opds-sync administrator
configures an AI endpoint (such as a self-hosted Ollama or a third-party
provider) and you opt in from Settings, Quire shows generated book
insights — author, summary, series, themes — grounded with citations
from Wikipedia and OpenLibrary. No AI request leaves your network until
you explicitly opt in.
```

- [ ] **Step 4: Commit**

```bash
git add README.md docs/sync-api.md fastlane/metadata/android/en-US/full_description.txt
git commit -m ":memo: docs: AI features (optional) — README, sync-api, fastlane"
```

---

## Task 19: End-to-end smoke against a real Ollama (optional but recommended)

This task is not required to ship but is the cheapest path to high
confidence the substrate works in real conditions. Skip if no Ollama is
available.

- [ ] **Step 1: Bring up local Ollama**

```bash
docker run -d --name ollama -p 11434:11434 ollama/ollama
docker exec -it ollama ollama pull llama3.1:8b
```

- [ ] **Step 2: Configure opds-sync env**

In whatever `.env` / launch config opds-sync uses locally:

```
OPDS_SYNC_AI_ENABLED=true
OPDS_SYNC_AI_BASE_URL=http://host.docker.internal:11434/v1
OPDS_SYNC_AI_MODEL=llama3.1:8b
OPDS_SYNC_AI_SOURCES=wikipedia,openlibrary
```

(Replace `host.docker.internal` with the Ollama host from the server's
perspective.)

- [ ] **Step 3: Restart opds-sync, run a curl smoke**

```bash
USER=alice; PASS=...
AUTH=$(echo -n "$USER:$PASS" | base64)

curl -s -H "Authorization: Basic $AUTH" http://opds-sync.local/ai/v1/config | jq
curl -s -X PUT -H "Authorization: Basic $AUTH" -H "Content-Type: application/json" \
  -d '{"ai_enabled": true}' http://opds-sync.local/ai/v1/preferences | jq

curl -s -X POST -H "Authorization: Basic $AUTH" -H "Content-Type: application/json" \
  -d '{
    "identity": {"metadata_id":"9780553293357","content_hash":"smoketest"},
    "bundle": {"title":"Foundation","author":"Isaac Asimov","publisher":"Bantam Spectra"}
  }' http://opds-sync.local/ai/v1/insights/lookup | jq
```

Expect a populated `payload.summary`, a non-empty `sources` array including
at least one Wikipedia or OpenLibrary citation, and `payload.confidence`
of `medium` or `high`.

- [ ] **Step 4: Document the smoke command**

Add a short "AI smoke" section to `server/README.md` so the next person
doesn't have to reinvent it.

- [ ] **Step 5: Commit (only if you added the README section)**

```bash
git add server/README.md
git commit -m ":memo: docs(server): AI smoke-test command"
```

---

## Task 20: Final verification before merging

- [ ] **Step 1: Full server test pass**

```bash
cd server && pytest -v
```

Expected: all green.

- [ ] **Step 2: Full Android test pass**

```bash
scripts/dgradle test
```

Expected: all green.

- [ ] **Step 3: Build release APK to confirm reproducibility-friendly state**

```bash
scripts/dgradle :app:assembleRelease
```

Expected: SUCCESS.

- [ ] **Step 4: Verify only the planned files changed**

```bash
git diff --stat main...HEAD
```

Skim the list. There should be no surprises (no random gradle.properties or
keystore changes). If anything looks off, investigate before merging.

---

## Appendix A: Deployment notes

The AI substrate is **off by default**. Enabling it requires three things in
whatever environment you deploy opds-sync to:

1. **AI env vars** on the opds-sync container:

   ```
   OPDS_SYNC_AI_ENABLED=true
   OPDS_SYNC_AI_BASE_URL=http://<openai-compatible-endpoint>/v1
   OPDS_SYNC_AI_MODEL=<model-id>
   OPDS_SYNC_AI_API_KEY=<secret>          # may be empty for local Ollama
   OPDS_SYNC_AI_SOURCES=wikipedia,openlibrary
   OPDS_SYNC_AI_RATE_PER_MIN=10
   OPDS_SYNC_AI_DAILY_BUDGET=200
   OPDS_SYNC_AI_REGEN_DAILY_LIMIT=3
   ```

   Store the API key in your secrets system (Kubernetes Secret / Docker Compose
   env-file / systemd EnvironmentFile). Never commit it.

2. **Network reachability** from opds-sync to:

   - The AI endpoint (host:port of whatever `AI_BASE_URL` points to).
   - `en.wikipedia.org:443` and `openlibrary.org:443` for grounding citations.
     Set `OPDS_SYNC_AI_SOURCES=""` to disable retrieval and rely on the model
     alone if outbound egress is restricted.

   On Kubernetes with NetworkPolicy enforcement (Calico, Cilium, etc.), add
   the corresponding `egress:` rules to the opds-sync namespace and, if your
   AI provider is in-cluster, an `ingress:` rule on its side.

3. **Model choice.** Any OpenAI-compatible chat-completions endpoint works:
   Ollama, llama.cpp, vLLM, OpenAI, Anthropic via its OpenAI shim, OpenRouter.
   For self-hosted Ollama, models with `tools` capability handle the structured
   JSON output more reliably (e.g. recent `llama3.1`, `qwen2.5`, `gpt-oss`
   variants). Verify with:

   ```bash
   curl -s http://<your-ai-host>/v1/models | jq
   ```

### Tuning guidance

| Var | Default | Tune up if … | Tune down if … |
|---|---|---|---|
| `AI_RATE_PER_MIN` | 10 | Provider is happy under load | Latency spikes / 429s from provider |
| `AI_DAILY_BUDGET` | 200 | Active users hit the limit | Quota burns faster than expected |
| `AI_REGEN_DAILY_LIMIT` | 3 | Trusted users / curated library | Users fishing for better answers |
| `AI_MAX_CONCURRENCY` | 4 | CPU/RAM headroom on opds-sync | Memory pressure on opds-sync pod |
| `AI_TIMEOUT_S` | 120 | Large/reasoning models under load | Want faster failure for retries |

### Smoke test

After deployment, from a host that can reach the service:

```bash
USER=<your-user>
read -s PASS
curl -s -u "$USER:$PASS" http://<opds-sync-host>/ai/v1/config | jq
```

Expected: `configured: true`, `model_id` matches your config,
`daily_budget` and `regen_daily_limit` echo the env vars.

---

## Out of scope reminder

The following are deliberately deferred to subsequent plans / phases:

- **Phase 2 — Library intelligence:** reader profile, recommendations
  endpoints + UI. New plan: `docs/superpowers/plans/<later>-quire-ai-phase-2.md`.
- **Phase 3:** paragraph Q&A, AI notes, general web search via tool-calling,
  per-user provider keys.
- A Calibre plugin that reads insights from opds-sync (parallel to the
  planned read-only progress plugin).

If you discover that a deferred item is in fact needed for Phase 1 to be
useful, stop and update the spec before adding to this plan.
