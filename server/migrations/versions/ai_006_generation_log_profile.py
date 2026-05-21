"""ai_006_generation_log_profile: generalize audit log + add profile counter.

Folds four coupled changes (per coordinator §3.11, §3.12 and Lock #11
amendment):

1. Audit-log generalization — ``ai_generation_log.book_insight_id`` becomes
   nullable, new ``kind TEXT NOT NULL`` discriminator with a CHECK constraint
   admitting ``'insight'``, ``'profile'`` AND ``'promote'`` (Lock #11
   amendment so PR-ζ's promote events can flow from stdout into the DB once
   this ships).
2. Cross-field constraint — ``book_insight_id IS NOT NULL when kind IN
   ('insight', 'promote')``; ``book_insight_id IS NULL when kind = 'profile'``.
3. Profile daily-cap storage — ``ai_usage_daily.profile_count`` (drives the
   per-user-per-UTC-day cap on POST /ai/v1/profile/refresh).
4. ``kind`` server default is dropped post-backfill so every future insert
   MUST specify ``kind`` explicitly (prevents silent regressions where a code
   path forgets to set it).

The migration is reversible IF ``'profile'`` rows are cleared first; ``downgrade()``
documents this. ``'promote'`` rows are safe to keep — their ``book_insight_id``
is already non-null and they degrade into indistinguishable ``'insight'``
rows after the ``kind`` column is dropped.

Revision ID: ai_006
Revises: ai_005
Create Date: 2026-05-20 00:00:00.000000
"""

import sqlalchemy as sa
from alembic import op

revision = "ai_006"
down_revision = "ai_005"
branch_labels = None
depends_on = None


def upgrade() -> None:
    # 1. Audit-log: relax FK nullability, add discriminator.
    op.alter_column("ai_generation_log", "book_insight_id", nullable=True)

    op.add_column(
        "ai_generation_log",
        sa.Column(
            "kind",
            sa.String(),
            nullable=False,
            server_default=sa.text("'insight'"),
        ),
    )
    # Backfill is the server_default — existing rows pick up 'insight'.
    # Drop the default so future inserts MUST specify kind explicitly.
    op.alter_column("ai_generation_log", "kind", server_default=None)

    # Lock #11 amendment: admit 'promote' so PR-ζ's promote handler can
    # transition from stdout-only logging to DB-backed logging in this
    # PR's service.py edit.
    op.create_check_constraint(
        "ck_ai_generation_log_kind",
        "ai_generation_log",
        "kind IN ('insight', 'profile', 'promote')",
    )

    # Cross-field constraint:
    #   insight rows MUST carry the FK (the source book_insight row)
    #   promote rows MUST carry the FK (the *copied* book_insight row at `to` identity)
    #   profile rows MUST NOT carry the FK (per-user, not per-book)
    op.create_check_constraint(
        "ck_ai_generation_log_kind_fk",
        "ai_generation_log",
        "(kind = 'profile' AND book_insight_id IS NULL) "
        "OR (kind IN ('insight', 'promote') AND book_insight_id IS NOT NULL)",
    )

    # Partial index for profile-row lookups by (tenant_id, subject, created_at).
    op.create_index(
        "ix_ai_generation_log_profile",
        "ai_generation_log",
        ["tenant_id", "subject", "created_at"],
        postgresql_where=sa.text("kind = 'profile'"),
    )

    # 2. Daily profile counter on ai_usage_daily.
    op.add_column(
        "ai_usage_daily",
        sa.Column(
            "profile_count",
            sa.Integer(),
            nullable=False,
            server_default=sa.text("0"),
        ),
    )


def downgrade() -> None:
    # WARNING: any rows with book_insight_id IS NULL (profile rows) will
    # violate the restored NOT NULL constraint. The downgrade path expects
    # the caller to have cleared 'profile' rows first
    # (`DELETE FROM ai_generation_log WHERE kind = 'profile'`).
    # 'promote' rows are safe to keep — their book_insight_id is already
    # non-null; they degrade to indistinguishable 'insight' rows once the
    # `kind` column is dropped.
    op.drop_column("ai_usage_daily", "profile_count")
    op.drop_index("ix_ai_generation_log_profile", table_name="ai_generation_log")
    op.drop_constraint("ck_ai_generation_log_kind_fk", "ai_generation_log", type_="check")
    op.drop_constraint("ck_ai_generation_log_kind", "ai_generation_log", type_="check")
    op.drop_column("ai_generation_log", "kind")
    op.alter_column("ai_generation_log", "book_insight_id", nullable=False)
