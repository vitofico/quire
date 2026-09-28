"""ai_008_server_settings: AI settings saved from the status page.

Eighth migration on the `ai` branch. Chains off `ai_007`.

Issue #102: an admin can change a few AI settings on the `/quire-admin`
page (the timeout, the rate limit, the two daily quotas and the retrieval
sources) without editing `.env` and restarting. Each saved value is one row,
keyed by the setting's field name (`ai_timeout_s`), holding the value as
text. A setting given in the environment still wins over a row here; a
setting with neither falls back to its code default. See
`quire_server/core/runtime_settings.py`.

Schema additions:
- `server_settings(key TEXT PRIMARY KEY, value TEXT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_by TEXT NOT NULL)`.

Revision ID: ai_008
Revises: ai_007
Create Date: 2026-09-28 00:00:00.000000
"""

import sqlalchemy as sa
from alembic import op

revision = "ai_008"
down_revision = "ai_007"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "server_settings",
        sa.Column("key", sa.String(), primary_key=True),
        sa.Column("value", sa.String(), nullable=False),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.func.now(),
        ),
        sa.Column("updated_by", sa.String(), nullable=False),
    )


def downgrade() -> None:
    op.drop_table("server_settings")
