"""progress_001_library_items: per-user library mirror.

First migration on the `progress` branch (per PR-A's branching convention).
Created via:
    alembic revision --head=0004 --splice --branch-label=progress -m "library_items"

`library_items` is the server-side mirror of what's in the user's Android
library. Unlike `book_insights` it is USER-SCOPED — `user_id` is in the row
and in every uniqueness/index constraint. It is NOT shared cache; the
cache-key audit test does not cover this table.

Schema highlights:
- Surrogate `pk` (matches the `documents` table convention).
- `(user_id, content_hash)` is the hard uniqueness. `content_hash` is the
  identity-hierarchy floor (per `.claude/local/quire-ai/2026-05-16-next-
  deliverables.md` §"Identity hierarchy").
- `(user_id, metadata_id) WHERE metadata_id IS NOT NULL` is a partial
  unique index — when the client knows the metadata_id, it must be unique
  per user.
- `(user_id, series_name) WHERE deleted_at IS NULL` is the index PR8's
  series-continuity shelf will eventually consume server-side (today it
  reads from Room; this is the matching server-side index for parity).
- `(user_id, updated_at)` powers the `GET ?since=` delta endpoint.
- `authors` and `subjects` are `jsonb` (not `text[]`) so a future schema
  can add per-entry metadata without a column-type migration.
- `series_index` is `numeric` (not `int`) because EPUB 3 `group-position`
  allows fractional positions (`1.5` for novellas).
- Soft-delete only: `deleted_at` carries the tombstone. PR1 never hard-
  deletes from this table.

Reference: docs/superpowers/specs/2026-05-16-library-items-design.md

Revision ID: progress_001
Revises: 0004
Create Date: 2026-05-16 00:00:00.000000
"""

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision = "progress_001"
down_revision = "0004"
branch_labels = ("progress",)
depends_on = None


def upgrade() -> None:
    op.create_table(
        "library_items",
        sa.Column("pk", sa.BigInteger(), primary_key=True, autoincrement=True),
        sa.Column("user_id", sa.String(), nullable=False),
        sa.Column("metadata_id", sa.String(), nullable=True),
        sa.Column("content_hash", sa.String(), nullable=False),
        sa.Column("title", sa.String(), nullable=False),
        sa.Column(
            "authors",
            postgresql.JSONB(),
            nullable=False,
            server_default=sa.text("'[]'::jsonb"),
        ),
        sa.Column("series_name", sa.String(), nullable=True),
        sa.Column("series_index", sa.Numeric(), nullable=True),
        sa.Column("isbn", sa.String(), nullable=True),
        sa.Column("language", sa.String(), nullable=True),
        sa.Column(
            "subjects",
            postgresql.JSONB(),
            nullable=False,
            server_default=sa.text("'[]'::jsonb"),
        ),
        sa.Column("opds_href", sa.String(), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.Column("deleted_at", sa.DateTime(timezone=True), nullable=True),
    )

    # Hard uniqueness: one row per (user, content_hash). Reactivation flips
    # `deleted_at` rather than inserting a new row.
    op.create_index(
        "uq_library_items_user_content",
        "library_items",
        ["user_id", "content_hash"],
        unique=True,
    )

    # Partial unique: when metadata_id is known it must be unique per user.
    # Postgres partial-unique-index is the right tool; sqlite would need a
    # different trick but we target postgres in production.
    op.create_index(
        "uq_library_items_user_metadata",
        "library_items",
        ["user_id", "metadata_id"],
        unique=True,
        postgresql_where=sa.text("metadata_id IS NOT NULL"),
    )

    # Series shelf index (server-side parity with PR8's Room query). Partial
    # on `deleted_at IS NULL` because shelves never want tombstones.
    op.create_index(
        "ix_library_items_user_series_alive",
        "library_items",
        ["user_id", "series_name"],
        postgresql_where=sa.text("deleted_at IS NULL"),
    )

    # Powers `GET /library/v1/items?since=...`.
    op.create_index(
        "ix_library_items_user_updated",
        "library_items",
        ["user_id", "updated_at"],
    )


def downgrade() -> None:
    op.drop_index("ix_library_items_user_updated", table_name="library_items")
    op.drop_index("ix_library_items_user_series_alive", table_name="library_items")
    op.drop_index("uq_library_items_user_metadata", table_name="library_items")
    op.drop_index("uq_library_items_user_content", table_name="library_items")
    op.drop_table("library_items")
