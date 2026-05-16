"""ai_002_insight_language: add language to book_insights cache key.

Second migration on the `ai` branch. PR-C's ai_001 already owns the
`branch_labels=("ai",)` claim, so this one sets `branch_labels = None`
and chains off `ai_001`.

Schema:
- New `language` column on `book_insights`. NOT NULL, server_default
  'auto'. Existing rows backfill to 'auto' via the default.
- Both partial unique indexes (`metadata_id` variant and `content_hash`
  variant) drop their PR #9 tone-keyed name and recreate with `language`
  appended. The partial WHERE clauses are preserved verbatim.

Reference: docs/superpowers/specs/2026-05-16-ai-language-preference-design.md

Revision ID: ai_002
Revises: ai_001
Create Date: 2026-05-16 00:00:00.000000
"""

import sqlalchemy as sa
from alembic import op

revision = "ai_002"
down_revision = "ai_001"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "book_insights",
        sa.Column(
            "language",
            sa.String(),
            nullable=False,
            server_default=sa.text("'auto'"),
        ),
    )

    op.drop_index(
        "uq_book_insights_content_hash_model_prompt_tone",
        table_name="book_insights",
    )
    op.drop_index(
        "uq_book_insights_metadata_id_model_prompt_tone",
        table_name="book_insights",
    )

    op.create_index(
        "uq_book_insights_content_hash_model_prompt_tone_language",
        "book_insights",
        ["content_hash", "model_id", "prompt_version", "tone", "language"],
        unique=True,
        postgresql_where=sa.text("superseded_at IS NULL"),
    )
    op.create_index(
        "uq_book_insights_metadata_id_model_prompt_tone_language",
        "book_insights",
        ["metadata_id", "model_id", "prompt_version", "tone", "language"],
        unique=True,
        postgresql_where=sa.text("metadata_id IS NOT NULL AND superseded_at IS NULL"),
    )


def downgrade() -> None:
    op.drop_index(
        "uq_book_insights_metadata_id_model_prompt_tone_language",
        table_name="book_insights",
    )
    op.drop_index(
        "uq_book_insights_content_hash_model_prompt_tone_language",
        table_name="book_insights",
    )
    op.create_index(
        "uq_book_insights_metadata_id_model_prompt_tone",
        "book_insights",
        ["metadata_id", "model_id", "prompt_version", "tone"],
        unique=True,
        postgresql_where=sa.text("metadata_id IS NOT NULL AND superseded_at IS NULL"),
    )
    op.create_index(
        "uq_book_insights_content_hash_model_prompt_tone",
        "book_insights",
        ["content_hash", "model_id", "prompt_version", "tone"],
        unique=True,
        postgresql_where=sa.text("superseded_at IS NULL"),
    )
    op.drop_column("book_insights", "language")
