"""Admit the 'discuss' kind in `ai_generation_log`.

One row per question a reader asks about their highlights
(`POST /ai/v1/highlights/discuss`), with no `book_insight_id`. The row holds
the usual counts and timings only: no passage, note, question or answer is
stored anywhere.

Downgrade deletes the 'discuss' log rows, which the old constraints refuse.

Revision ID: ai_014
Revises: ai_013
"""

from alembic import op

revision = "ai_014"
down_revision = "ai_013"
branch_labels = None
depends_on = None


def _kind_constraints(kinds: str, without_fk: str) -> None:
    op.drop_constraint("ck_ai_generation_log_kind_fk", "ai_generation_log", type_="check")
    op.drop_constraint("ck_ai_generation_log_kind", "ai_generation_log", type_="check")
    op.create_check_constraint(
        "ck_ai_generation_log_kind", "ai_generation_log", f"kind IN ({kinds})"
    )
    op.create_check_constraint(
        "ck_ai_generation_log_kind_fk",
        "ai_generation_log",
        f"(kind IN ({without_fk}) AND book_insight_id IS NULL) "
        "OR (kind IN ('insight', 'promote') AND book_insight_id IS NOT NULL)",
    )


def upgrade() -> None:
    _kind_constraints(
        "'insight', 'profile', 'promote', 'details', 'cover_art', 'discuss'",
        "'profile', 'details', 'cover_art', 'discuss'",
    )


def downgrade() -> None:
    op.execute("DELETE FROM ai_generation_log WHERE kind = 'discuss'")
    _kind_constraints(
        "'insight', 'profile', 'promote', 'details', 'cover_art'",
        "'profile', 'details', 'cover_art'",
    )
