"""ai_007_identity_hash_version: add `identity_hash_version` to book_insights.

Seventh migration on the `ai` branch. Chains off `ai_006`.

Phase 0, task F-1 (per `docs/superpowers/specs/2026-05-22-quire-monetization-design.md`,
sections "Architectural changes → Identity hash schema versioning" and
"One-way doors → Identity hash schema versioning"):

Lock in identity-hash versioning before the Cloud private beta. Without an
explicit version field on every record carrying an identity hash, any
future change to the hash function breaks sync, caches, recs, export, and
deletion across millions of rows. Trivial now, expensive to retrofit.

This migration adds `identity_hash_version` to `book_insights`, the
SHARED-CACHE table on the `ai` branch. Companion migration `progress_003`
adds the same column to `documents` and `library_items`.

Cache-key impact: NONE. `identity_hash_version` is intentionally NOT added
to any of the unique/cache-key constraints on `book_insights`. The
architect-reviewed design treats it as row-freshness metadata only: a
higher-version client hitting an existing cache row upgrades the persisted
value via `max(existing, incoming)` in the orchestrator. Partitioning the
cache by version would fragment the cross-tenant cache-hit property
(see the cache-integrity comment above `BookInsight` in
`quire_server/db/models.py`) without paying for itself in Phase 0.

Schema additions:
- `identity_hash_version INTEGER NOT NULL DEFAULT 1`. Pre-existing rows
  backfill to `1` atomically via the column-level default.
- `CHECK (identity_hash_version >= 1)`.

Revision ID: ai_007
Revises: ai_006
Create Date: 2026-05-22 00:00:00.000000
"""

import sqlalchemy as sa
from alembic import op

revision = "ai_007"
down_revision = "ai_006"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "book_insights",
        sa.Column(
            "identity_hash_version",
            sa.Integer(),
            nullable=False,
            server_default=sa.text("1"),
        ),
    )
    op.create_check_constraint(
        "ck_book_insights_identity_hash_version_ge_1",
        "book_insights",
        "identity_hash_version >= 1",
    )


def downgrade() -> None:
    op.drop_constraint(
        "ck_book_insights_identity_hash_version_ge_1",
        "book_insights",
        type_="check",
    )
    op.drop_column("book_insights", "identity_hash_version")
