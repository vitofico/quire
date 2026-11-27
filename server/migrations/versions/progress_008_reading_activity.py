"""Store private, opt-in reading activity for progress-enabled accounts."""

import sqlalchemy as sa
from alembic import op

revision = "progress_008"
down_revision = "progress_007"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "reading_activity",
        sa.Column("user_id", sa.String(), primary_key=True),
        sa.Column("id", sa.String(64), primary_key=True),
        sa.Column("book_key", sa.String(256), nullable=False),
        sa.Column("title", sa.String(1024), nullable=False),
        sa.Column("started_at", sa.BigInteger(), nullable=False),
        sa.Column("utc_offset_seconds", sa.Integer(), nullable=False),
        sa.Column("active_seconds", sa.Integer(), nullable=False),
        sa.Column("words", sa.BigInteger(), nullable=True),
    )
    op.create_table(
        "reading_activity_resets",
        sa.Column("user_id", sa.String(), primary_key=True),
        sa.Column("cleared_before", sa.BigInteger(), nullable=False),
    )


def downgrade() -> None:
    op.drop_table("reading_activity_resets")
    op.drop_table("reading_activity")
