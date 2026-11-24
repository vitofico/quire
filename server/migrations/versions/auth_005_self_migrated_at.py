"""Record reader-initiated Calibre migrations, which open a device claim window.

Revision ID: auth_005
Revises: auth_004
"""

import sqlalchemy as sa
from alembic import op

revision = "auth_005"
down_revision = "auth_004"
branch_labels = None
depends_on = None


def upgrade():
    # Existing reservations stay NULL: operator migrations never open a claim window.
    op.add_column(
        "calibre_account_bindings",
        sa.Column("self_migrated_at", sa.DateTime(timezone=True), nullable=True),
    )


def downgrade():
    op.drop_column("calibre_account_bindings", "self_migrated_at")
