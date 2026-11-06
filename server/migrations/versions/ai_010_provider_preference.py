"""Per-account selection among operator-approved AI providers.

Revision ID: ai_010
Revises: ai_009
"""

import sqlalchemy as sa
from alembic import op

revision = "ai_010"
down_revision = "ai_009"
branch_labels = None
depends_on = None


def upgrade():
    op.add_column("user_ai_preferences", sa.Column("provider_id", sa.String(32), nullable=True))


def downgrade():
    op.drop_column("user_ai_preferences", "provider_id")
