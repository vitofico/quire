"""ai tables: book_insights, user_ai_preferences, external_source_cache, ai_usage_daily

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
    )
    # Partial unique index on (content_hash, model_id, prompt_version): only one LIVE
    # row may exist per identity. Superseded rows (regeneration history) are exempt so
    # the lineage chain can accumulate without collisions.
    op.create_index(
        "uq_book_insights_content_hash_model_prompt",
        "book_insights",
        ["content_hash", "model_id", "prompt_version"],
        unique=True,
        postgresql_where=sa.text("superseded_at IS NULL"),
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
    op.drop_index("uq_book_insights_content_hash_model_prompt", table_name="book_insights")
    op.drop_table("book_insights")
