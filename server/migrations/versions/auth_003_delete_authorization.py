"""One-use, session-bound fresh account deletion authorization.

Revision ID: auth_003
Revises: auth_002
"""

import sqlalchemy as sa
from alembic import op

revision = "auth_003"
down_revision = "auth_002"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table(
        "account_delete_authorizations",
        sa.Column("proof_hash", sa.String(), primary_key=True),
        sa.Column(
            "user_id",
            sa.BigInteger(),
            sa.ForeignKey("native_users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "session_hash",
            sa.String(),
            sa.ForeignKey("native_sessions.token_hash", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
    )


def downgrade():
    op.drop_table("account_delete_authorizations")
