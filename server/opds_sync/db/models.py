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
    client_updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    received_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )

    document: Mapped[Document] = relationship(back_populates="progress")


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
    ai_enabled: Mapped[bool] = mapped_column(
        Boolean, nullable=False, server_default=text("false"), default=False
    )
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
    count: Mapped[int] = mapped_column(Integer, nullable=False, server_default=text("0"), default=0)
    regen_count: Mapped[int] = mapped_column(
        Integer, nullable=False, server_default=text("0"), default=0
    )
