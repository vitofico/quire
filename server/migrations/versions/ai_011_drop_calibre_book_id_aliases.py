"""Delete calibre_book_id identity aliases.

A Calibre-Web book number is unique only within one server, yet its alias was
scoped by user alone, so book 5 on two servers shared one row and some rows now
point at the wrong book. The server no longer reads or writes these aliases.

`alias_scheme` has no CHECK constraint (only `canonical_scheme` does), so there
is nothing to tighten: the code skips schemes it does not know.

Downgrade cannot bring the deleted rows back, so it changes no data.

Revision ID: ai_011
Revises: ai_010
"""

from alembic import op

revision = "ai_011"
down_revision = "ai_010"
branch_labels = None
depends_on = None


def upgrade():
    op.execute("DELETE FROM insight_identity_aliases WHERE alias_scheme = 'calibre_book_id'")


def downgrade():
    pass
