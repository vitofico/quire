"""Record which saved catalog each library item came from."""

import sqlalchemy as sa
from alembic import op

revision = "progress_007"
down_revision = "progress_006"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("library_items", sa.Column("catalog_id", sa.String(64), nullable=True))


def downgrade() -> None:
    op.drop_column("library_items", "catalog_id")
