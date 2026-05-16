from datetime import date, datetime

from sqlalchemy import (
    ARRAY,
    JSON,
    BigInteger,
    Boolean,
    CheckConstraint,
    Date,
    DateTime,
    Float,
    ForeignKey,
    Index,
    Integer,
    String,
    UniqueConstraint,
    func,
    text,
)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship


class Base(DeclarativeBase):
    pass


class Document(Base):
    __tablename__ = "documents"
    __table_args__ = (
        UniqueConstraint("user_id", "metadata_id", name="uq_documents_user_metadata"),
        UniqueConstraint("user_id", "content_hash", name="uq_documents_user_content_hash"),
        Index("ix_documents_user", "user_id"),
    )

    pk: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[str] = mapped_column(String, nullable=False)
    metadata_id: Mapped[str | None] = mapped_column(String, nullable=True)
    content_hash: Mapped[str] = mapped_column(String, nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )

    progress: Mapped["Progress | None"] = relationship(
        back_populates="document", uselist=False, cascade="all, delete-orphan"
    )


class Progress(Base):
    __tablename__ = "progress"
    __table_args__ = (
        CheckConstraint("percent >= 0 AND percent <= 1", name="ck_progress_percent_range"),
        Index("ix_progress_document_client_updated_at", "document_pk", "client_updated_at"),
    )

    document_pk: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("documents.pk", ondelete="CASCADE"), primary_key=True
    )
    locator: Mapped[str] = mapped_column(String, nullable=False)
    percent: Mapped[float] = mapped_column(Float, nullable=False)
    finished_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    client_updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    received_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )

    document: Mapped[Document] = relationship(back_populates="progress")


# ============================================================================
# Cache-integrity invariant (PR-C, 2026-05-16)
# ----------------------------------------------------------------------------
# `book_insights` is a SHARED CACHE: one row serves every tenant who requests
# the same identity+model+prompt+tone. The cross-tenant cache-hit property is
# load-bearing for hosted Quire Cloud AI economics.
#
# Therefore this table MUST NOT carry `user_id`, `tenant_id`, `subject`, or
# any other principal column read for cache decisions. Per-call audit and
# billing attribution live in `ai_generation_log` (FK to book_insights.id).
#
# `generated_by` is grandfathered: a NOT NULL column from before this
# invariant existed. PR-C stops reading it; a follow-up will null it; a
# later migration will drop it. Until then it is write-only legacy.
# ============================================================================
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
    # Part of the cache key so users with different AiStyle.tone get their own
    # generations (`neutral` is the universal default).
    tone: Mapped[str] = mapped_column(
        String, nullable=False, server_default=text("'neutral'"), default="neutral"
    )
    # Part of the cache key so users with different AiStyle.language get their
    # own generations. `'auto'` (the universal default) emits no language clause
    # in the prompt and preserves the pre-PR4 behavior byte-for-byte.
    language: Mapped[str] = mapped_column(
        String, nullable=False, server_default=text("'auto'"), default="auto"
    )
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
    ai_enabled: Mapped[bool] = mapped_column(
        Boolean, nullable=False, server_default=text("false"), default=False
    )
    # Persisted as JSON; defaults live in api/ai_schemas.AiStyle so the migration
    # never needs to change when we add a new knob.
    style: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )


# Cache-integrity invariant: shared cache, MUST NOT carry tenant columns.
# See the comment above BookInsight for the full rule.
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
    count: Mapped[int] = mapped_column(Integer, nullable=False, server_default=text("0"), default=0)
    regen_count: Mapped[int] = mapped_column(
        Integer, nullable=False, server_default=text("0"), default=0
    )


class AIGenerationLog(Base):
    """Per-call audit row anchored to `book_insights.id`.

    One row per `get()`-hit / `generate()` / `regenerate()` call, regardless of
    cache state. Future billing rollups query `(tenant_id, created_at)`; the
    audit UI queries `(book_insight_id)`.

    `status` is permissive ('hit' | 'miss' | 'error') so a future PR can
    introduce error rows without a schema bump. PR-C only emits 'hit' and
    'miss'; errors go to structured logs because they have no FK target.
    """

    __tablename__ = "ai_generation_log"
    __table_args__ = (
        CheckConstraint(
            "status IN ('hit', 'miss', 'error')",
            name="ck_ai_generation_log_status",
        ),
        Index("ix_ai_generation_log_tenant_created", "tenant_id", "created_at"),
        Index("ix_ai_generation_log_book_insight", "book_insight_id"),
    )

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    book_insight_id: Mapped[int] = mapped_column(
        BigInteger,
        ForeignKey("book_insights.id", ondelete="CASCADE"),
        nullable=False,
    )
    tenant_id: Mapped[str] = mapped_column(
        String, nullable=False, server_default=text("'local'"), default="local"
    )
    subject: Mapped[str] = mapped_column(String, nullable=False)
    request_id: Mapped[str | None] = mapped_column(String, nullable=True)
    model_id: Mapped[str] = mapped_column(String, nullable=False)
    prompt_version: Mapped[str] = mapped_column(String, nullable=False)
    latency_ms: Mapped[int | None] = mapped_column(Integer, nullable=True)
    status: Mapped[str] = mapped_column(String, nullable=False)
    error_class: Mapped[str | None] = mapped_column(String, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )


# ============================================================================
# Scoped alias table (PR2, 2026-05-16)
# ----------------------------------------------------------------------------
# `insight_identity_aliases` maps non-canonical identity hints (`opds_href`,
# `opds_dc_id`, `calibre_book_id`, `isbn`) to canonical schemes
# (`metadata_id`, `content_hash`). The resolver runs BEFORE every cache
# lookup, generation-lock acquisition, regenerate, and invalidate.
#
# `user_id` is INTENTIONAL cache-key scoping, NOT a tenant-leak:
#   - Global aliases (user_id IS NULL): `metadata_id`, `content_hash`,
#     `isbn`. These are universally stable across instances.
#   - User-scoped aliases (user_id = <calibre-web user>): `opds_href`,
#     `opds_dc_id`, `calibre_book_id`. The same OPDS string can mean
#     different books on different calibre-web instances, so they must
#     not cross-contaminate.
#
# Cache-key audit test split (PR2 §4): this table appears in
# `SCOPED_ALIAS_TABLES`, NOT `SHARED_CACHE_TABLES`. The `user_id` column
# is required (the inverse-property test catches a refactor that removes
# it); tenant columns (`tenant_id`, `subject`, `principal_id`) are still
# forbidden.
# ============================================================================
class InsightIdentityAlias(Base):
    __tablename__ = "insight_identity_aliases"
    __table_args__ = (
        CheckConstraint(
            "canonical_scheme IN ('metadata_id', 'content_hash')",
            name="ck_insight_identity_aliases_canonical_scheme",
        ),
        CheckConstraint(
            "alias_scheme <> canonical_scheme OR alias_value <> canonical_value",
            name="ck_insight_identity_aliases_alias_not_canonical",
        ),
        Index(
            "uq_insight_identity_aliases_scoped",
            "alias_scheme",
            "alias_value",
            "user_id",
            unique=True,
            postgresql_where=text("user_id IS NOT NULL"),
        ),
        Index(
            "uq_insight_identity_aliases_global",
            "alias_scheme",
            "alias_value",
            unique=True,
            postgresql_where=text("user_id IS NULL"),
        ),
        Index(
            "ix_insight_identity_aliases_canonical",
            "canonical_scheme",
            "canonical_value",
        ),
    )

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    alias_scheme: Mapped[str] = mapped_column(String, nullable=False)
    alias_value: Mapped[str] = mapped_column(String, nullable=False)
    canonical_scheme: Mapped[str] = mapped_column(String, nullable=False)
    canonical_value: Mapped[str] = mapped_column(String, nullable=False)
    source: Mapped[str] = mapped_column(String, nullable=False)
    user_id: Mapped[str | None] = mapped_column(String, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
