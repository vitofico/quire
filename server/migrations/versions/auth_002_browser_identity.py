"""Generic external identities, browser sessions, pairing and closure fence.

Revision ID: auth_002
Revises: auth_001
"""

import sqlalchemy as sa
from alembic import op

revision = "auth_002"
down_revision = "auth_001"
branch_labels = None
depends_on = None


def upgrade():
    op.add_column("native_users", sa.Column("closed_at", sa.DateTime(timezone=True)))
    op.add_column("native_users", sa.Column("purged_at", sa.DateTime(timezone=True)))
    op.add_column(
        "native_sessions", sa.Column("kind", sa.String(), nullable=False, server_default="api")
    )
    op.add_column("native_sessions", sa.Column("device_name", sa.String()))
    op.create_table(
        "external_identities",
        sa.Column("issuer", sa.String(), primary_key=True),
        sa.Column("subject", sa.String(), primary_key=True),
        sa.Column(
            "user_id",
            sa.BigInteger(),
            sa.ForeignKey("native_users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("email", sa.String()),
        sa.Column("email_verified", sa.Boolean(), nullable=False),
    )
    op.create_table(
        "oidc_transactions",
        sa.Column("state_hash", sa.String(), primary_key=True),
        sa.Column("payload", sa.JSON(), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_table(
        "pairing_tickets",
        sa.Column("ticket_hash", sa.String(), primary_key=True),
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
    for table in ("pairing_tickets", "oidc_transactions", "external_identities"):
        op.drop_table(table)
    op.drop_column("native_sessions", "device_name")
    op.drop_column("native_sessions", "kind")
    op.drop_column("native_users", "purged_at")
    op.drop_column("native_users", "closed_at")
