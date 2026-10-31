# Migrations — branch label convention

This directory holds Alembic migrations for the Quire Server. As of PR-A
(2026-05-16), feature migrations split into forward-only branches off the linear
backbone `0001 → 0002 → 0003 → 0004`.

## Backbone (do NOT rewrite)

```
0001 → 0002 → 0003 → 0004
                       ↑
                  split point
```

The four numeric revisions `0001..0004` carry the schema as deployed in
production. They MUST stay byte-for-byte unchanged:

- `branch_labels = None`
- linear `down_revision` chain
- no relabeling, no merging, no stamping operations

Any change to these files risks an Alembic-version divergence between the
checkout and a running production DB.

## Branches (forward-only from 0004)

The current AI head is `ai_009`, adding `ai_usage_monthly` for an optional
generic authenticated HTTP policy. It follows `ai_008` without rewriting any
deployed revision. Quotas are inert unless the operator enables that policy.

The current progress head is `progress_004`, following `progress_003` and
adding caller-scoped `highlights` plus `highlight_sync_state`. Highlights
have no document foreign key, so removing a downloaded book preserves
annotations. A unique `(user_id, revision)` index supports safe delta pages;
writers and readers serialize on the same per-user transaction lock before
allocating or observing revisions. Both tables are included in native
account export and purge. This migration is skipped in AI-only deployments.

The foundational `auth` branch also starts at `0004`; its current head is
`auth_003` (account-deletion authorization), following native users/sessions and
browser identity. It is upgraded regardless of feature-mode flags.

From the split point onward, every new migration belongs to exactly one branch:

| Branch     | Label        | Owners | Notes                                                                                            |
| ---------- | ------------ | ------ | ------------------------------------------------------------------------------------------------ |
| `auth`     | `"auth"`     | shared | `auth_001` → `auth_002` → `auth_003`; always upgraded. |
| `core`     | `"core"`     | shared | Reserved; no migrations yet.                                                                     |
| `progress` | `"progress"` | sync   | `progress_001` → `progress_002` → `progress_003` → `progress_004` (private highlights). Spliced from `0004`. |
| `ai`       | `"ai"`       | AI     | `ai_001` through `ai_009`; the latest adds monthly job admission. |

### Adding the FIRST migration on a branch (splice)

When a branch doesn't exist yet, the next migration that introduces it must:

1. Use `down_revision = "0004"` (the backbone tip; not `head`, because `head`
   is ambiguous in multi-head graphs).
2. Set `branch_labels = ("<branch>",)`.
3. Be created via `alembic revision --head=0004 --splice --branch-label=<branch> -m "..."`.

The `--splice` flag is required by Alembic once any other branch already exists,
because Alembic refuses to introduce a new branch off a non-head revision
without it. Forgetting `--splice` silently creates a fourth head and breaks
the wrapper's branch detection.

Template:

```python
"""<short description>.

Revision ID: <branch>_001
Revises: 0004
Create Date: YYYY-MM-DD HH:MM:SS.000000
"""

import sqlalchemy as sa
from alembic import op

revision = "<branch>_001"
down_revision = "0004"
branch_labels = ("<branch>",)
depends_on = None


def upgrade() -> None: ...


def downgrade() -> None: ...
```

### Adding SUBSEQUENT migrations on an existing branch

After the first migration on a branch exists, subsequent migrations on the
same branch use:

```bash
alembic revision --head=<branch>@head -m "..."
```

And the file looks like:

```python
revision = "<branch>_NNN"
down_revision = "<branch>_NN"  # parent on the same branch
branch_labels = None  # only the first migration of a branch labels it
depends_on = None
```

## Deploy-time migration

The container entrypoint runs `python /app/scripts/migrate.py`, not
`alembic upgrade head`. The wrapper:

1. Always upgrades the unlabeled backbone (today: `0004`).
2. Upgrades the foundational `auth` branch when present.
3. For each enabled+materialized feature branch (per `QUIRE_SERVER_PROGRESS_ENABLED` and
   `QUIRE_SERVER_AI_ENABLED`), runs `alembic upgrade <branch>@head`.

So a sync-only deploy with `QUIRE_SERVER_AI_ENABLED=false` never advances the
DB past `0004` on the AI side, regardless of what ai migrations exist in the
script directory.

## Downgrades

Downgrades are not part of the wrapper. Run per-branch:

```bash
alembic downgrade <branch>@-1
```

## Verifying branch state

Inspect the current script-directory structure:

```bash
alembic heads --verbose       # human-readable list of branch heads
alembic history --verbose     # full revision graph
```

In code:

```python
from alembic.config import Config
from alembic.script import ScriptDirectory

cfg = Config("alembic.ini")
script = ScriptDirectory.from_config(cfg)
print(script.get_heads())  # current branch heads; not a single global head
print(script.get_revision("ai@head"))  # raises if ai branch doesn't exist
```

## Android database boundary

These Alembic revisions belong only to the server. Android uses Room: shipped
schemas 10 (highlights) and 11 (scoped highlights) remain canonical; schema 12
adds device-local memory bookmarks. No server migration or sync API stores those
bookmarks. See [Pause and return](../../docs/reader-pause.md) and the
[development guide](../../docs/development.md) for Android migration coverage.
