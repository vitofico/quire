"""Reserve legacy Calibre storage scopes for explicitly migrated accounts.

Revision ID: auth_004
Revises: auth_003
"""

import sqlalchemy as sa
from alembic import op

revision = "auth_004"
down_revision = "auth_003"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table(
        "calibre_account_bindings",
        sa.Column("calibre_user_id", sa.String(), primary_key=True),
        sa.Column(
            "native_user_id",
            sa.BigInteger(),
            sa.ForeignKey("native_users.id", ondelete="SET NULL"),
            unique=True,
        ),
    )


def downgrade():
    if op.get_bind().execute(sa.text("SELECT 1 FROM calibre_account_bindings LIMIT 1")).first():
        raise RuntimeError("Calibre storage reservations exist; restore a pre-migration backup")
    op.drop_table("calibre_account_bindings")
