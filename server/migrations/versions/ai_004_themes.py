"""ai_004_themes: book_themes side table (PR3).

Fourth migration on the `ai` branch. PR-C's `ai_001` owns the
`branch_labels=("ai",)` claim; this revision sets `branch_labels = None`
and chains off `ai_003` (the identity-aliases migration).

Schema:
- `book_themes(book_insight_id, theme, confidence)` with composite PK
  `(book_insight_id, theme)` and a single-column `theme` index for PR9-style
  GROUP BY queries. FK to `book_insights.id` with `ON DELETE CASCADE` so
  invalidating an insight automatically drops its theme rows (regenerate
  is a supersede-not-delete, so superseded rows KEEP their theme history
  for audit; PR9 must filter `superseded_at IS NULL` on the join).

The composite PK gives us free index coverage for parent-delete cascades
(its leftmost column is `book_insight_id`), so no extra FK-side index is
needed. The `theme` index covers the PR9 `WHERE theme = ?` / `GROUP BY
theme` query paths.

Cache-integrity invariant (see comment on BookInsight model): SHARED CACHE,
no `user_id` / `tenant_id`. Per-tenant audit lives in `ai_generation_log`.

Reference: docs/superpowers/specs/2026-05-17-themes-v3-design.md

Revision ID: ai_004
Revises: ai_003
Create Date: 2026-05-17 00:00:00.000000
"""

import sqlalchemy as sa
from alembic import op

revision = "ai_004"
down_revision = "ai_003"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "book_themes",
        sa.Column("book_insight_id", sa.BigInteger(), nullable=False),
        sa.Column("theme", sa.String(), nullable=False),
        sa.Column(
            "confidence",
            sa.Float(),
            nullable=False,
            server_default=sa.text("1.0"),
        ),
        sa.PrimaryKeyConstraint("book_insight_id", "theme", name="pk_book_themes"),
        sa.ForeignKeyConstraint(
            ["book_insight_id"],
            ["book_insights.id"],
            ondelete="CASCADE",
            name="fk_book_themes_insight",
        ),
    )
    op.create_index("ix_book_themes_theme", "book_themes", ["theme"])


def downgrade() -> None:
    op.drop_index("ix_book_themes_theme", table_name="book_themes")
    op.drop_table("book_themes")
