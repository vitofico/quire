"""progress_002_abandoned_at: add `abandoned_at` to progress + terminal-state invariant.

Second migration on the `progress` branch. Chains off `progress_001` (the
library_items mirror). `branch_labels = None` because the label is carried
by `progress_001`.

Schema highlights:
- Adds `progress.abandoned_at TIMESTAMPTZ NULL`.
- Adds the terminal-state invariant as a DB-level check constraint:
  `finished_at IS NULL OR abandoned_at IS NULL`. A row can be neither
  (in-progress), or exactly one of the two — never both. The "defensive
  read" rule in `quire_server/api/progress.py` (and the symmetric Android
  SyncOrchestrator path) drops `abandoned_at` when `finished_at` is set,
  so legacy rows that pre-date this constraint still degrade gracefully.
- Adds a partial index on `(document_pk) WHERE abandoned_at IS NOT NULL`
  for the eventual "abandoned shelf" query path (pr-δ surfaces it).

Coordinator §3.3 / §3.10 / Lock #6. Pairs with the Android Room
`MIGRATION_7_8` (data/local/...EReaderDatabase.kt).

Revision ID: progress_002
Revises: progress_001
Create Date: 2026-05-20 00:00:00.000000
"""

import sqlalchemy as sa
from alembic import op

revision = "progress_002"
down_revision = "progress_001"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "progress",
        sa.Column("abandoned_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.create_check_constraint(
        "ck_progress_abandoned_xor_finished",
        "progress",
        "finished_at IS NULL OR abandoned_at IS NULL",
    )
    op.create_index(
        "ix_progress_abandoned_at",
        "progress",
        ["document_pk"],
        postgresql_where=sa.text("abandoned_at IS NOT NULL"),
    )


def downgrade() -> None:
    op.drop_index("ix_progress_abandoned_at", table_name="progress")
    op.drop_constraint(
        "ck_progress_abandoned_xor_finished",
        "progress",
        type_="check",
    )
    op.drop_column("progress", "abandoned_at")
