"""Atomic optional monthly AI job allowance.

Revision ID: ai_009
Revises: ai_008
"""

import sqlalchemy as sa
from alembic import op

revision = "ai_009"
down_revision = "ai_008"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table(
        "ai_usage_monthly",
        sa.Column("user_id", sa.String(), primary_key=True),
        sa.Column("month", sa.Date(), primary_key=True),
        sa.Column("count", sa.Integer(), nullable=False),
        sa.CheckConstraint("count >= 0", name="ck_ai_usage_monthly_nonnegative"),
    )


def downgrade():
    op.drop_table("ai_usage_monthly")
