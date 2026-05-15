"""book_insights: add tone to cache key

Tone is the only AiStyle field that affects model output (per Phase 1.5 design).
Adding it to `book_insights` plus the partial unique indexes makes per-tone
generations cacheable separately without one tone leaking into another.

Existing rows are backfilled to 'neutral' (the default).

Revision ID: 0004
Revises: 0003
Create Date: 2026-05-15 00:00:00.000000
"""

import sqlalchemy as sa
from alembic import op

revision = "0004"
down_revision = "0003"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "book_insights",
        sa.Column(
            "tone",
            sa.String(),
            nullable=False,
            server_default=sa.text("'neutral'"),
        ),
    )

    op.drop_index("uq_book_insights_content_hash_model_prompt", table_name="book_insights")
    op.drop_index("uq_book_insights_metadata_id_model_prompt", table_name="book_insights")

    op.create_index(
        "uq_book_insights_content_hash_model_prompt_tone",
        "book_insights",
        ["content_hash", "model_id", "prompt_version", "tone"],
        unique=True,
        postgresql_where=sa.text("superseded_at IS NULL"),
    )
    op.create_index(
        "uq_book_insights_metadata_id_model_prompt_tone",
        "book_insights",
        ["metadata_id", "model_id", "prompt_version", "tone"],
        unique=True,
        postgresql_where=sa.text("metadata_id IS NOT NULL AND superseded_at IS NULL"),
    )


def downgrade() -> None:
    op.drop_index("uq_book_insights_metadata_id_model_prompt_tone", table_name="book_insights")
    op.drop_index("uq_book_insights_content_hash_model_prompt_tone", table_name="book_insights")
    op.create_index(
        "uq_book_insights_metadata_id_model_prompt",
        "book_insights",
        ["metadata_id", "model_id", "prompt_version"],
        unique=True,
        postgresql_where=sa.text("metadata_id IS NOT NULL AND superseded_at IS NULL"),
    )
    op.create_index(
        "uq_book_insights_content_hash_model_prompt",
        "book_insights",
        ["content_hash", "model_id", "prompt_version"],
        unique=True,
        postgresql_where=sa.text("superseded_at IS NULL"),
    )
    op.drop_column("book_insights", "tone")
