"""auth_001_native_users_and_sessions: create native_users + native_sessions.

First migration on the new ``auth`` branch. Chains off the backbone tip
``0004`` so it sits alongside the existing ``progress`` and ``ai`` branches.

Phase 0, task S-1 (per
``docs/superpowers/specs/2026-05-22-quire-monetization-design.md``,
section "Architectural changes → Thin auth abstraction in ``quire_server``"
and "Build sequence → Phase 0" item 3):

The OSS server defaults to CalibreWeb Basic auth. The Cloud product needs
its own identity layer (email/password + magic-link). This migration adds
the two tables NativeAuth needs:

* ``native_users``  — email (canonical lowercase, UNIQUE), argon2id PHC hash.
* ``native_sessions`` — opaque session token store. Primary key is
  ``sha256(token_plain)`` hex; the raw token never reaches the DB.

Both tables are ALWAYS materialized (the ``auth`` branch is unconditionally
upgraded by ``scripts/migrate.py``) so that switching between backends is a
config-only flip, not a schema migration.

Scope discipline: this migration ships only what minimum-viable NativeAuth
needs for Cloud. Password-reset tokens, magic-link tokens, refresh tokens,
audit-log tables, rate-limit ledger, lockout state, MFA factors, and account
recovery state are all DEFERRED per spec.

Revision ID: auth_001
Revises: 0004
Create Date: 2026-05-22 00:00:00.000000
"""

import sqlalchemy as sa
from alembic import op

revision = "auth_001"
down_revision = "0004"
# Claim the ``auth`` branch label here. Subsequent ``auth_NNN`` revisions
# leave this None (label is propagated forward by Alembic), matching the
# convention used by ``ai_001`` / ``progress_001``.
branch_labels = ("auth",)
depends_on = None


def upgrade() -> None:
    op.create_table(
        "native_users",
        sa.Column("id", sa.BigInteger(), primary_key=True, autoincrement=True),
        sa.Column("email", sa.String(), nullable=False),
        sa.Column("password_hash", sa.String(), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.func.now(),
        ),
        sa.UniqueConstraint("email", name="uq_native_users_email"),
        sa.CheckConstraint("email = lower(email)", name="ck_native_users_email_lower"),
    )

    op.create_table(
        "native_sessions",
        # Primary key is the sha256(token) hex digest. The raw token lives
        # only in the client. Compromise of the DB does not leak active
        # session secrets.
        sa.Column("token_hash", sa.String(), primary_key=True),
        sa.Column(
            "user_id",
            sa.BigInteger(),
            sa.ForeignKey("native_users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.func.now(),
        ),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("revoked_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.create_index(
        "ix_native_sessions_user",
        "native_sessions",
        ["user_id"],
    )
    op.create_index(
        "ix_native_sessions_expires",
        "native_sessions",
        ["expires_at"],
    )


def downgrade() -> None:
    op.drop_index("ix_native_sessions_expires", table_name="native_sessions")
    op.drop_index("ix_native_sessions_user", table_name="native_sessions")
    op.drop_table("native_sessions")
    op.drop_table("native_users")
