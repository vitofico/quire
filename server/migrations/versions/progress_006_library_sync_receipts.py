"""Store idempotency receipts for web library writes."""

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects.postgresql import JSONB

revision = "progress_006"
down_revision = "progress_005"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "library_sync_receipts",
        sa.Column("user_id", sa.String(), nullable=False),
        sa.Column("operation_id", sa.Uuid(), nullable=False),
        sa.Column("payload_hash", sa.String(64), nullable=False),
        sa.Column("response", JSONB(), nullable=False),
        sa.PrimaryKeyConstraint("user_id", "operation_id"),
    )


def downgrade() -> None:
    op.drop_table("library_sync_receipts")
