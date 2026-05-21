# Migrations — branch label convention

This directory holds Alembic migrations for the Quire Server. As of PR-A
(2026-05-16), migrations split into three forward-only branches off the linear
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

From the split point onward, every new migration belongs to exactly one branch:

| Branch     | Label        | Owners | Notes                                                                                            |
| ---------- | ------------ | ------ | ------------------------------------------------------------------------------------------------ |
| `core`     | `"core"`     | shared | Reserved; no migrations yet.                                                                     |
| `progress` | `"progress"` | sync   | First and current head: `progress_001_library_items` (PR1, 2026-05-16). Spliced from `0004`.     |
| `ai`       | `"ai"`       | AI     | Chain: `ai_001_generation_log` (PR-C) → `ai_002_insight_language` (PR4) → `ai_003_identity_aliases` (PR2, 2026-05-16) → `ai_004_themes` (PR3, 2026-05-17). |

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


def upgrade() -> None:
    ...


def downgrade() -> None:
    ...
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
down_revision = "<branch>_NN"        # parent on the same branch
branch_labels = None                  # only the first migration of a branch labels it
depends_on = None
```

## Deploy-time migration

The container entrypoint runs `python /app/scripts/migrate.py`, not
`alembic upgrade head`. The wrapper:

1. Always upgrades the unlabeled backbone (today: `0004`).
2. For each enabled+materialized branch (per `QUIRE_SERVER_PROGRESS_ENABLED` and
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
print(script.get_heads())                  # ['0004'] today; multi-head once branches materialize
print(script.get_revision("ai@head"))      # raises if ai branch doesn't exist
```
