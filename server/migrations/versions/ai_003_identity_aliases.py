"""ai_003_identity_aliases: generic alias table for the identity-resolution seam.

Third migration on the `ai` branch. PR-C's `ai_001` owns the
`branch_labels=("ai",)` claim; this revision sets `branch_labels = None`
and chains off `ai_002` (the language migration).

Schema:
- `insight_identity_aliases` maps alias schemes (`opds_href`, `opds_dc_id`,
  `calibre_book_id`, `isbn`) to canonical schemes (`metadata_id`,
  `content_hash`). The resolver reads this table BEFORE every cache
  lookup, generation-lock acquisition, regenerate, and invalidate.
- `user_id` is intentional cache-key scoping, not a tenant-leak: per-user
  OPDS aliases must not cross-contaminate (the same OPDS string can mean
  different books on different calibre-web instances). See the comment
  on the `InsightIdentityAlias` model for the full rule.

NULL-in-PK pitfall:
- A composite `(alias_scheme, alias_value, user_id)` PRIMARY KEY would
  allow duplicate rows where `user_id IS NULL` because Postgres treats
  NULL values as distinct in unique constraints. Instead we use a
  surrogate `id bigserial PRIMARY KEY` plus two PARTIAL unique indexes:
    * `uq_insight_identity_aliases_scoped` WHERE user_id IS NOT NULL
    * `uq_insight_identity_aliases_global` WHERE user_id IS NULL

Reference: docs/superpowers/specs/2026-05-16-identity-aliases-design.md

Revision ID: ai_003
Revises: ai_002
Create Date: 2026-05-16 00:00:00.000000
"""

import sqlalchemy as sa
from alembic import op

revision = "ai_003"
down_revision = "ai_002"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "insight_identity_aliases",
        sa.Column("id", sa.BigInteger(), primary_key=True, autoincrement=True),
        sa.Column("alias_scheme", sa.String(), nullable=False),
        sa.Column("alias_value", sa.String(), nullable=False),
        sa.Column("canonical_scheme", sa.String(), nullable=False),
        sa.Column("canonical_value", sa.String(), nullable=False),
        sa.Column("source", sa.String(), nullable=False),
        sa.Column("user_id", sa.String(), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.CheckConstraint(
            "canonical_scheme IN ('metadata_id', 'content_hash')",
            name="ck_insight_identity_aliases_canonical_scheme",
        ),
        sa.CheckConstraint(
            "alias_scheme <> canonical_scheme OR alias_value <> canonical_value",
            name="ck_insight_identity_aliases_alias_not_canonical",
        ),
    )
    op.create_index(
        "uq_insight_identity_aliases_scoped",
        "insight_identity_aliases",
        ["alias_scheme", "alias_value", "user_id"],
        unique=True,
        postgresql_where=sa.text("user_id IS NOT NULL"),
    )
    op.create_index(
        "uq_insight_identity_aliases_global",
        "insight_identity_aliases",
        ["alias_scheme", "alias_value"],
        unique=True,
        postgresql_where=sa.text("user_id IS NULL"),
    )
    op.create_index(
        "ix_insight_identity_aliases_canonical",
        "insight_identity_aliases",
        ["canonical_scheme", "canonical_value"],
    )


def downgrade() -> None:
    op.drop_index(
        "ix_insight_identity_aliases_canonical",
        table_name="insight_identity_aliases",
    )
    op.drop_index(
        "uq_insight_identity_aliases_global",
        table_name="insight_identity_aliases",
    )
    op.drop_index(
        "uq_insight_identity_aliases_scoped",
        table_name="insight_identity_aliases",
    )
    op.drop_table("insight_identity_aliases")
