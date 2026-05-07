"""initial schema: documents, progress

Revision ID: 0001
Revises:
Create Date: 2026-05-05 00:00:00.000000
"""

import sqlalchemy as sa
from alembic import op

revision = "0001"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "documents",
        sa.Column("pk", sa.BigInteger(), primary_key=True, autoincrement=True),
        sa.Column("user_id", sa.String(), nullable=False),
        sa.Column("metadata_id", sa.String(), nullable=True),
        sa.Column("content_hash", sa.String(), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.UniqueConstraint("user_id", "metadata_id", name="uq_documents_user_metadata"),
        sa.UniqueConstraint("user_id", "content_hash", name="uq_documents_user_content_hash"),
    )
    op.create_index("ix_documents_user", "documents", ["user_id"])

    op.create_table(
        "progress",
        sa.Column(
            "document_pk",
            sa.BigInteger(),
            sa.ForeignKey("documents.pk", ondelete="CASCADE"),
            primary_key=True,
        ),
        sa.Column("locator", sa.String(), nullable=False),
        sa.Column("percent", sa.Float(), nullable=False),
        sa.Column("client_updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "received_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint("percent >= 0 AND percent <= 1", name="ck_progress_percent_range"),
    )
    op.create_index(
        "ix_progress_document_client_updated_at", "progress", ["document_pk", "client_updated_at"]
    )


def downgrade() -> None:
    op.drop_index("ix_progress_document_client_updated_at", table_name="progress")
    op.drop_table("progress")
    op.drop_index("ix_documents_user", table_name="documents")
    op.drop_table("documents")
