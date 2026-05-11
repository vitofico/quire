from datetime import datetime

from sqlalchemy import (
    BigInteger,
    CheckConstraint,
    DateTime,
    Float,
    ForeignKey,
    Index,
    String,
    UniqueConstraint,
    func,
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
