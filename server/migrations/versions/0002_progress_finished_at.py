"""progress: add nullable finished_at

Revision ID: 0002
Revises: 0001
Create Date: 2026-05-09 00:00:00.000000
"""

import sqlalchemy as sa
from alembic import op

revision = "0002"
down_revision = "0001"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "progress",
        sa.Column("finished_at", sa.DateTime(timezone=True), nullable=True),
    )


def downgrade() -> None:
    op.drop_column("progress", "finished_at")
