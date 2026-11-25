"""Book details: the shared table of tidied records, and their log rows.

`book_details` holds one tidied record per book and set of fields, shared by
every reader who sends the same ones, like `book_insights`. It has no user
column. A tidy is logged in `ai_generation_log` as kind 'details', which has
no `book_insight_id`.

`book_details_reader` points each reader at the record they were last given
for a book, so their other devices, which may send other fields, are served
the same one. It is private to the reader and deleted with the account.

Downgrade deletes the 'details' log rows, which the old constraints refuse.

Revision ID: ai_012
Revises: ai_011
"""

import sqlalchemy as sa
from alembic import op

revision = "ai_012"
down_revision = "ai_011"
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
    op.create_table(
        "book_details",
        sa.Column("key", sa.String(64), primary_key=True),
        sa.Column("identity", sa.String(), nullable=False),
        sa.Column("record", sa.JSON(), nullable=False),
        sa.Column("design", sa.JSON(), nullable=False),
        sa.Column("model_id", sa.String(), nullable=False),
        sa.Column("prompt_version", sa.String(), nullable=False),
        sa.Column(
            "generated_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
    )
    op.create_table(
        "book_details_reader",
        sa.Column("user_id", sa.String(), primary_key=True),
        sa.Column("identity_hash", sa.String(64), primary_key=True),
        sa.Column("key", sa.String(64), nullable=False),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
    )
    _kind_constraints("'insight', 'profile', 'promote', 'details'", "'profile', 'details'")


def downgrade() -> None:
    op.execute("DELETE FROM ai_generation_log WHERE kind = 'details'")
    _kind_constraints("'insight', 'profile', 'promote'", "'profile'")
    op.drop_table("book_details_reader")
    op.drop_table("book_details")
