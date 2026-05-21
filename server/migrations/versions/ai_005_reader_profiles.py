"""ai_005_reader_profiles: per-user reader profile cache.

Fifth migration on the `ai` branch. Chains off `ai_004` (book_themes).
`branch_labels = None` because the label is carried by `ai_001`.

Cache namespace is **separate** from `book_insights` — different
`prompt_version` constant (`READER_PROFILE_PROMPT_VERSION`, introduced
in pr-β). The table is user-scoped (NOT shared cache) by design —
`(tenant_id, subject)` PK is correct; the shared-cache invariant in
`db/models.py` (above `BookInsight`) does NOT apply here.

Schema highlights:
- `(tenant_id, subject)` composite PK so multi-tenant deploys never
  collide; single-tenant deploys collapse to `("local", <user_id>)`.
- `payload JSONB`: the structured `ReaderProfilePayload` (see ai_schemas).
- `schema_version` is mirrored top-level for cheap LIKE/= filtering
  and so callers don't have to crack the JSON to gate compatibility.
- `input_fingerprint VARCHAR(16)`: 16-hex-char SHA-256 prefix
  (Lock #12, coordinator §3.6). Nullable here because pr-α never
  writes it; pr-β populates it on every refresh.
- `generated_at` has `DEFAULT now()` so hand-inserted rows in tests
  (and admin one-shots) don't need to fabricate the timestamp.

Revision ID: ai_005
Revises: ai_004
Create Date: 2026-05-20 00:00:00.000000
"""

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision = "ai_005"
down_revision = "ai_004"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "reader_profiles",
        sa.Column("tenant_id", sa.String(), nullable=False),
        sa.Column("subject", sa.String(), nullable=False),
        sa.Column("payload", postgresql.JSONB(), nullable=False),
        sa.Column("schema_version", sa.Integer(), nullable=False),
        sa.Column("model_id", sa.String(), nullable=False),
        sa.Column("prompt_version", sa.String(), nullable=False),
        # Lock #12 + coordinator §3.6: 16-hex-char SHA-256 prefix.
        # VARCHAR(16) pins the width; nullable because pr-α never writes
        # it (only pr-β does, on every refresh).
        sa.Column("input_fingerprint", sa.String(length=16), nullable=True),
        sa.Column(
            "generated_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.PrimaryKeyConstraint("tenant_id", "subject", name="pk_reader_profiles"),
    )


def downgrade() -> None:
    op.drop_table("reader_profiles")
