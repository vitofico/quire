"""progress_003_identity_hash_version: add `identity_hash_version` to documents + library_items.

Third migration on the `progress` branch. Chains off `progress_002`.
`branch_labels = None` because the label is carried by `progress_001`.

Phase 0, task F-1 (per `docs/superpowers/specs/2026-05-22-quire-monetization-design.md`,
sections "Architectural changes → Identity hash schema versioning" and
"One-way doors → Identity hash schema versioning"):

Lock in identity-hash versioning before the Cloud private beta. Without an
explicit version field on every record carrying an identity hash, any
future change to the hash function (edition merging, normalization fixes,
etc.) breaks sync, caches, recs, export, and deletion across millions of
rows. Trivial to add now, very expensive to retrofit.

This migration covers the `progress`-branch tables that carry an identity
hash directly: `documents` and `library_items`. The companion migration
`ai_007` adds the same column to `book_insights` on the `ai` branch.
`progress.progress` deliberately has no own version column: identity travels
via the parent `Document`, so `Progress.document.identity_hash_version` is
the answer for any progress row.

Schema additions per table:
- `identity_hash_version INTEGER NOT NULL DEFAULT 1`. All pre-existing rows
  backfill to `1` via the column-level default (atomic in Postgres for the
  ADD COLUMN ... DEFAULT path; no separate UPDATE pass needed).
- `CHECK (identity_hash_version >= 1)`. Prevents accidental zero-or-negative
  versions from sneaking in through a future buggy writer.

Downgrade-safety: dropping the column is reversible; the data loss is
inherent and downgrade callers accept it. No indexes are added — the column
is row-metadata, not a query predicate.

Revision ID: progress_003
Revises: progress_002
Create Date: 2026-05-22 00:00:00.000000
"""

import sqlalchemy as sa
from alembic import op

revision = "progress_003"
down_revision = "progress_002"
branch_labels = None
depends_on = None


def upgrade() -> None:
    for table in ("documents", "library_items"):
        op.add_column(
            table,
            sa.Column(
                "identity_hash_version",
                sa.Integer(),
                nullable=False,
                server_default=sa.text("1"),
            ),
        )
        op.create_check_constraint(
            f"ck_{table}_identity_hash_version_ge_1",
            table,
            "identity_hash_version >= 1",
        )


def downgrade() -> None:
    for table in ("documents", "library_items"):
        op.drop_constraint(
            f"ck_{table}_identity_hash_version_ge_1",
            table,
            type_="check",
        )
        op.drop_column(table, "identity_hash_version")
