"""catalogs_001_account_catalogs: book catalogs saved to a Quire account.

First migration on the ``catalogs`` branch. Like ``auth`` it is always
upgraded by ``scripts/migrate.py``, whatever the mode flags say, because
account export and deletion read it in every deployment.

``secret`` holds the address and login as AES-256-GCM ciphertext (see
``quire_server/core/catalog_secrets.py``); the key is never stored here.

Revision ID: catalogs_001
Revises: 0004
Create Date: 2026-11-25 00:00:00.000000
"""

import sqlalchemy as sa
from alembic import op

revision = "catalogs_001"
down_revision = "0004"
branch_labels = ("catalogs",)
depends_on = None


def upgrade() -> None:
    op.create_table(
        "account_catalogs",
        sa.Column("user_id", sa.String(), primary_key=True),
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column("name", sa.String(60), nullable=False),
        sa.Column("kind", sa.String(), nullable=False),
        sa.Column("host", sa.String(), nullable=False),
        sa.Column("secret", sa.String(), nullable=False),
        sa.Column("position", sa.Integer(), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.func.now(),
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.func.now(),
        ),
        sa.CheckConstraint("kind IN ('calibre', 'opds')", name="ck_account_catalogs_kind"),
    )


def downgrade() -> None:
    op.drop_table("account_catalogs")
