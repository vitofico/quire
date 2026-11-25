"""Cover artwork: a painted background for a designed cover, and its log rows.

`book_cover_art` holds at most one artwork per shared details record, keyed
by the record's key and removed with it. Like `book_details` it has no user
column: the artwork is painted from the record, never from who asked. A row
is the single attempt for its key: 'pending' while a server paints it,
'painted' with the image, or 'failed' with the reason, so a failure is not
tried again in a loop. Each attempt is logged in `ai_generation_log` as kind
'cover_art', which has no `book_insight_id`.

Downgrade deletes the 'cover_art' log rows, which the old constraints refuse.

Revision ID: ai_013
Revises: ai_012
"""

import sqlalchemy as sa
from alembic import op

revision = "ai_013"
down_revision = "ai_012"
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
        "book_cover_art",
        sa.Column(
            "key",
            sa.String(64),
            sa.ForeignKey("book_details.key", ondelete="CASCADE"),
            primary_key=True,
        ),
        sa.Column("status", sa.String(16), nullable=False),
        sa.Column("image", sa.LargeBinary(), nullable=True),
        sa.Column("digest", sa.String(64), nullable=True),
        sa.Column("model_id", sa.String(), nullable=False),
        sa.Column("error_class", sa.String(64), nullable=True),
        sa.Column(
            "attempted_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.CheckConstraint(
            "status IN ('pending', 'painted', 'failed')", name="ck_book_cover_art_status"
        ),
        sa.CheckConstraint(
            "(status = 'painted') = (image IS NOT NULL AND digest IS NOT NULL)",
            name="ck_book_cover_art_image",
        ),
        sa.CheckConstraint(
            "image IS NULL OR octet_length(image) <= 524288", name="ck_book_cover_art_size"
        ),
    )
    _kind_constraints(
        "'insight', 'profile', 'promote', 'details', 'cover_art'",
        "'profile', 'details', 'cover_art'",
    )


def downgrade() -> None:
    op.execute("DELETE FROM ai_generation_log WHERE kind = 'cover_art'")
    _kind_constraints("'insight', 'profile', 'promote', 'details'", "'profile', 'details'")
    op.drop_table("book_cover_art")
