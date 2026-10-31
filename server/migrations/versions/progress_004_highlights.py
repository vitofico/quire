"""Private highlights and per-user, transaction-ordered sync revisions.

Revision ID: progress_004
Revises: progress_003
"""

import sqlalchemy as sa
from alembic import op

revision = "progress_004"
down_revision = "progress_003"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "highlight_sync_state",
        sa.Column("user_id", sa.String(), primary_key=True),
        sa.Column("revision", sa.BigInteger(), nullable=False),
    )
    op.create_table(
        "highlights",
        sa.Column("user_id", sa.String(), primary_key=True),
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column("metadata_id", sa.String(), nullable=True),
        sa.Column("content_hash", sa.String(), nullable=False),
        sa.Column("identity_hash_version", sa.Integer(), nullable=False),
        sa.Column("locator", sa.String(), nullable=False),
        sa.Column("text", sa.String(), nullable=False),
        sa.Column("colour", sa.String(), nullable=False),
        sa.Column("book_title", sa.String(), nullable=False),
        sa.Column("book_author", sa.String(), nullable=True),
        sa.Column("created_at", sa.BigInteger(), nullable=False),
        sa.Column("updated_at", sa.BigInteger(), nullable=False),
        sa.Column("deleted_at", sa.BigInteger(), nullable=True),
        sa.Column("revision", sa.BigInteger(), nullable=False),
    )
    op.create_index(
        "ix_highlights_user_revision", "highlights", ["user_id", "revision"], unique=True
    )


def downgrade() -> None:
    op.drop_table("highlights")
    op.drop_table("highlight_sync_state")
