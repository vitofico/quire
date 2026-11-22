"""Allow a private comment on each highlight."""

import sqlalchemy as sa
from alembic import op

revision = "progress_005"
down_revision = "progress_004"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("highlights", sa.Column("comment", sa.String(), nullable=True))


def downgrade() -> None:
    op.drop_column("highlights", "comment")
