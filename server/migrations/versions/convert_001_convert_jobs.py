"""convert_001_convert_jobs: PDF to EPUB conversions started from the apps.

First migration on the ``convert`` branch. Like ``catalogs`` it is always
upgraded by ``scripts/migrate.py``, because account export and deletion read
it in every deployment, whether conversion is switched on or not.

Revision ID: convert_001
Revises: 0004
Create Date: 2026-11-25 00:00:00.000000
"""

import sqlalchemy as sa
from alembic import op

revision = "convert_001"
down_revision = "0004"
branch_labels = ("convert",)
depends_on = None


def upgrade() -> None:
    op.create_table(
        "convert_jobs",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column("user_id", sa.String(), nullable=False),
        sa.Column("idempotency_key", sa.String(128), nullable=False),
        sa.Column("mode", sa.String(), nullable=False),
        sa.Column("source_sha256", sa.String(64), nullable=False),
        sa.Column("source_bytes", sa.BigInteger(), nullable=False),
        sa.Column("engine_revision", sa.String(), nullable=True),
        sa.Column("state", sa.String(), nullable=False),
        sa.Column("attempt", sa.Integer(), nullable=False),
        sa.Column("failure_code", sa.String(), nullable=True),
        sa.Column("result_sha256", sa.String(64), nullable=True),
        sa.Column("result_bytes", sa.BigInteger(), nullable=True),
        sa.Column("summary", sa.JSON(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("started_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint("user_id", "idempotency_key", name="uq_convert_jobs_user_key"),
        sa.CheckConstraint(
            "state IN ('created', 'queued', 'running', 'ready', 'review', 'failed', "
            "'cancelled', 'acknowledged', 'expired')",
            name="ck_convert_jobs_state",
        ),
    )
    op.create_index("ix_convert_jobs_user_created", "convert_jobs", ["user_id", "created_at"])
    op.create_index("ix_convert_jobs_state", "convert_jobs", ["state"])


def downgrade() -> None:
    op.drop_table("convert_jobs")
