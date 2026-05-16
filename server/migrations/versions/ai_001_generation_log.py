"""ai_001_generation_log: per-call tenant audit log keyed to book_insights.

First migration on the `ai` branch (per PR-A's branching convention).

Schema:
- ai_generation_log records one row per AI insight call (hit, miss; errors
  go to structured logs because they have no FK target). It is the future
  billing/audit substrate; the shared cache table `book_insights` stays
  tenant-blind.
- FK on book_insight_id is ON DELETE CASCADE: invalidating an insight cleans
  up its audit children.
- The check constraint accepts 'hit' | 'miss' | 'error' to remain forward-
  compatible with a future PR that introduces error rows (via nullable FK or
  sentinel rows). PR-C only emits 'hit' and 'miss'.

Revision ID: ai_001
Revises: 0004
Create Date: 2026-05-16 00:00:00.000000
"""

import sqlalchemy as sa
from alembic import op

revision = "ai_001"
down_revision = "0004"
branch_labels = ("ai",)
depends_on = None


def upgrade() -> None:
    op.create_table(
        "ai_generation_log",
        sa.Column("id", sa.BigInteger(), primary_key=True, autoincrement=True),
        sa.Column(
            "book_insight_id",
            sa.BigInteger(),
            sa.ForeignKey("book_insights.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "tenant_id",
            sa.String(),
            nullable=False,
            server_default=sa.text("'local'"),
        ),
        sa.Column("subject", sa.String(), nullable=False),
        sa.Column("request_id", sa.String(), nullable=True),
        sa.Column("model_id", sa.String(), nullable=False),
        sa.Column("prompt_version", sa.String(), nullable=False),
        sa.Column("latency_ms", sa.Integer(), nullable=True),
        sa.Column("status", sa.String(), nullable=False),
        sa.Column("error_class", sa.String(), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.CheckConstraint(
            "status IN ('hit', 'miss', 'error')",
            name="ck_ai_generation_log_status",
        ),
    )
    op.create_index(
        "ix_ai_generation_log_tenant_created",
        "ai_generation_log",
        ["tenant_id", "created_at"],
    )
    op.create_index(
        "ix_ai_generation_log_book_insight",
        "ai_generation_log",
        ["book_insight_id"],
    )


def downgrade() -> None:
    op.drop_index("ix_ai_generation_log_book_insight", table_name="ai_generation_log")
    op.drop_index("ix_ai_generation_log_tenant_created", table_name="ai_generation_log")
    op.drop_table("ai_generation_log")
