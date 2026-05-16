# Spec — Alembic branch split + deploy mode flags (PR-A)

> Shipped in 37c9845 on 2026-05-16 as PR #10.

**Date:** 2026-05-16
**Branch:** `feat/alembic-mode-split`
**Roadmap reference:** `.claude/local/quire-ai/2026-05-16-next-deliverables.md` §PR-A
**Status:** Draft for architect review

## 1. Motivation

The repo today ships as a single FastAPI app where every router (sync, progress, AI) mounts unconditionally, and a single Alembic chain `0001 → 0002 → 0003 → 0004` migrates the DB. To support the three published deploy modes (full-stack, sync-only, AI-only) without forking the codebase, we need:

1. Alembic migrations that can grow per-domain without coupling: future progress-side schema must not block AI-only deployments, and vice versa.
2. Settings flags that gate router mounts and provider imports at startup.
3. An entrypoint wrapper that upgrades only the branches the deploy enables.
4. Cross-cutting middleware (request-ID, request-size) that hosted Quire Cloud AI will rely on once it lights up.

This PR is the substrate. It must not change app behavior in production (the today-default mode is "full stack with both flags `true`"), and it must leave the live `0001 → 0004` chain byte-for-byte unchanged.

## 2. Alembic branching strategy

### 2.1 Linear backbone (untouched)

```
0001 -- 0002 -- 0003 -- 0004
                         ^
                         |
                    split point
```

- `0001..0004` keep `branch_labels = None` and a strict linear `down_revision` chain.
- We do **not** rewrite any of these files. Renaming, relabeling, or merging would force every deployed DB to perform Alembic's `stamp`/`merge` dance, which is a footgun on production.

### 2.2 Forward-only branching from `0004`

From PR-A forward, every new migration MUST belong to exactly one of three branches:

| Branch     | Label        | First revision id (set by future PRs) | Owners  |
| ---------- | ------------ | ------------------------------------- | ------- |
| `core`     | `"core"`     | _no migrations yet; reserved_         | shared  |
| `progress` | `"progress"` | `progress_001_*` (set by PR1)         | sync    |
| `ai`       | `"ai"`       | `ai_001_*` (set by PR-C)              | AI      |

**Convention for new migrations (documented in `migrations/README.md`):**

```python
# migrations/versions/<branch>_<NNN>_<slug>.py
revision = "<branch>_<NNN>"          # e.g. "progress_001", "ai_001"
down_revision = "0004"               # first child of the split point
branch_labels = ("<branch>",)        # only on the FIRST migration of the branch
depends_on = None
```

Subsequent migrations on the same branch:

```python
revision = "<branch>_<NNN>"
down_revision = "<branch>_<NNN-1>"
branch_labels = None                 # only the first migration of the branch labels it
```

### 2.3 Heads on a freshly-deployed DB

At PR-A merge time, no `progress_*` or `ai_*` migrations exist yet. `ScriptDirectory.get_heads()` returns `["0004"]` and no branch labels exist anywhere in the script directory. **`alembic upgrade ai@head` would fail** ("no such branch label") in this state — this is correct Alembic behavior, not a bug. Therefore the wrapper MUST detect whether each branch label exists in the script directory before attempting to upgrade to it.

**Mental model:** `0004` is a *common backbone tip*, not a "head of all branches simultaneously". Branches materialize only when their first labeled migration is added. Before that, the backbone is the only thing to upgrade to, and a plain `alembic upgrade head` does the job.

After PR-C lands `ai_001` (with `branch_labels = ("ai",), down_revision = "0004"`):

- `ScriptDirectory.get_heads()` returns `["ai_001"]`. `0004` is no longer a head — it has a child.
- Branch label `"ai"` now resolves to `ai_001`. `alembic upgrade ai@head` advances to `ai_001`.
- There is no `"progress"` label yet, so `alembic upgrade progress@head` still fails. The wrapper detects this and skips.

After PR1 lands `progress_001` (with `branch_labels = ("progress",), down_revision = "0004"`):

- `ScriptDirectory.get_heads()` returns `["ai_001", "progress_001"]`.
- Both `ai` and `progress` labels resolve. Wrapper upgrades the enabled subset.

### 2.4 Reserved `core` branch

We label nothing as `core` in this PR — `0001..0004` stay `branch_labels = None` because relabeling old revisions would create a checkout-vs-DB divergence. The wrapper invokes `alembic upgrade core@head` only **if** the `"core"` label resolves in the script directory (i.e., once some future `core_001` migration adds the label). Otherwise it skips. This means the `core` branch is a no-op today and lights up only when PR-A's successor wants cross-cutting schema.

### 2.5 Adding the first migration on a branch (Alembic splice rule)

Once `ai_001` exists and is the head of the `ai` branch, `0004` is no longer a head. Creating `progress_001` *also* off of `0004` via Alembic's CLI requires the `--splice` flag (Alembic refuses to insert a new branch off a non-head revision without it) plus `--branch-label`:

```bash
alembic revision --head=0004 --splice --branch-label=progress -m "library_items"
```

Subsequent migrations on the `progress` branch use:

```bash
alembic revision --head=progress@head -m "..."
```

And `ai`-branch migrations after `ai_001`:

```bash
alembic revision --head=ai@head -m "..."
```

This rule **must** be in `migrations/README.md` — getting it wrong silently creates a fourth head.

## 3. Settings (env vars)

`opds_sync/config.py`:

```python
ai_enabled: bool = True              # was False; defaults flip to roadmap modes table
progress_enabled: bool = True        # new
max_request_bytes: int = 1_048_576   # new (1 MiB)
```

| Env var                          | Type | Default     | Behavior                                                                  |
| -------------------------------- | ---- | ----------- | ------------------------------------------------------------------------- |
| `OPDS_SYNC_AI_ENABLED`           | bool | `true`      | Gates `/ai/v1` router + provider imports + ai migration branch upgrade.   |
| `OPDS_SYNC_PROGRESS_ENABLED`     | bool | `true`      | Gates `/sync/v1` router + progress migration branch upgrade.              |
| `OPDS_SYNC_MAX_REQUEST_BYTES`    | int  | `1_048_576` | Request body size cap; oversized requests get 413.                        |

**Backwards compatibility:** the old default for `ai_enabled` was `False`. The new default is `True`. Existing prod deployment sets `OPDS_SYNC_AI_ENABLED=true` explicitly per `quire-ai-deployment-notes.md`, so the default flip is invisible to prod. Sync-only deployers (currently zero, but documented future case) must now set `OPDS_SYNC_AI_ENABLED=false` explicitly.

If someone deploys with both flags `false`, `/health` and `/readyz` still mount; nothing else does. We surface `modes: []` from `/health` so monitoring can alert.

## 4. Provider lazy-import boundary

Today: `main.py` does `from opds_sync.core.ai.client import AIClient` at top-of-module. That import chain pulls `opds_sync.core.ai.{client,retrieval,service,prompts}` and indirectly `opds_sync.api.ai`. None of those modules import the `openai` SDK (our `AIClient` is an httpx wrapper over the OpenAI-compatible chat API), so the *literal* trigger from the roadmap ("`openai` SDK") does not fire here. But the spirit of the constraint is broader: in sync-only mode we must not import `opds_sync.core.ai.*` or `opds_sync.api.ai`, because (a) they pull retrieval/prompt code that adds runtime cost for no benefit, and (b) future PRs may add an `openai` dependency, and the lazy-import scaffold must already exist when that happens.

**Rule:** `opds_sync.main` must not have a top-level import from `opds_sync.core.ai` or `opds_sync.api.ai`. Both imports happen inside the `create_app()` body, gated by `if settings.ai_enabled`. Symmetric rule for progress: `opds_sync.api.progress` imports only when `settings.progress_enabled`.

**About `opds_sync.db.models`:** the models module is shared and houses both progress + AI ORM mappings. We do **not** split it — SQLAlchemy mappers register against shared metadata at module import time, so partial loading would require fragile re-mapper plumbing for no real win. `db.models` will always be imported in every mode. The gate is on the *router* and *service* modules, which is where the actual sync-only-vs-AI-only logic divergence lives, and which is where the `openai` SDK (when it ships) and Wikipedia/OpenLibrary HTTP clients are referenced.

Forbidden-in-mode module lists (used by the lazy-import test):

| Mode      | Must NOT be in `sys.modules`                                                                                                          |
| --------- | ------------------------------------------------------------------------------------------------------------------------------------- |
| sync-only | `opds_sync.api.ai`, `opds_sync.core.ai.client`, `opds_sync.core.ai.retrieval`, `opds_sync.core.ai.service`, `opds_sync.core.ai.prompts` |
| ai-only   | `opds_sync.api.progress`                                                                                                              |

**Test mechanism:** spawn a subprocess via `sys.executable -c "..."` (NOT `uv run python` — nesting layers and env quirks defeat the isolation), with the mode's env vars set, that:
1. Imports `opds_sync.main`.
2. Calls `opds_sync.main.create_app()`.
3. Asserts each forbidden module is not in `sys.modules`.

Calling `create_app()` (not just importing) is essential because mounting is the trigger for any lazy imports — an "import-only" test could pass while `create_app()` still does the wrong thing. Subprocess isolation is the only honest way to test import gates: `importlib.reload` and friends leak transitive imports.

## 5. Health endpoints

Two new endpoints mounted on the root app (no prefix), always-on:

### 5.1 `GET /health`

```json
{
  "ready": true,
  "modes": ["progress", "ai"]
}
```

- `ready` is always `true` when this endpoint responds — it's the k8s liveness probe; it does not touch the DB.
- `modes` is the list of enabled modes derived from `settings.progress_enabled` and `settings.ai_enabled`. Order is stable: `progress` first, `ai` second.
- Empty list when both flags are `false` (legal, monitored).

### 5.2 `GET /readyz`

- 200 with `{"ready": true, "modes": [...], "heads_applied": ["<rev>", ...]}` when:
  1. DB connectivity check (`SELECT 1`) succeeds.
  2. All required heads (per the rules below) are present in `alembic_version`.
- 503 with `{"ready": false, "detail": "<reason>"}` otherwise.

**Required-heads computation:**

```
required = set()
script_labels = _existing_branch_labels(script)
backbone = _backbone_head(script)  # e.g. "0004" today

# Enabled branches with materialized labels each contribute their head.
for (flag, label) in [(True, "core"), (progress_enabled, "progress"), (ai_enabled, "ai")]:
    if flag and label in script_labels:
        required.add(script.get_revision(f"{label}@head").revision)

# If nothing materialized to require, fall back to requiring the backbone.
# This covers: no labels at all, both flags false, an enabled flag without
# a corresponding branch label yet.
if not required:
    required.add(backbone)
```

Then read `alembic_version` rows via `SELECT version_num FROM alembic_version` and compare:

- If `set(rows) ⊇ required`: 200, `heads_applied = sorted(rows)`.
- Otherwise: 503, `detail = {"missing": sorted(required - set(rows)), "current": sorted(rows)}`.

**Ancestry shortcut:** when a branch head is required (say `ai_NNN`), we don't separately require its ancestors — `alembic_version` only ever stores leaf heads, and Alembic's invariant is that all ancestors are applied whenever a head is in the table. So requiring the leaf is sufficient.

**Backbone always implied:** when *any* branch head is required, the backbone is a transitive ancestor, so it's covered. The explicit fallback fires only when nothing else is required (the "no labels yet OR enabled-but-not-materialized" cases). A DB stamped below `0004` therefore returns 503 in both pre-and-post-materialization regimes because the required set always contains *something* whose ancestry includes `0004`.

The old `/sync/v1/healthz` and `/sync/v1/readyz` are **removed**. They were ad-hoc; their probes live on the root paths now per the roadmap. The k8s deployment manifest in `theficos-cluster` will need a follow-up bump (out of scope for this PR; deployment notes update is part of the PR body).

### 5.3 Wrapper script

A Python script — not shell — because we need Alembic's stable `ScriptDirectory` API to detect which branch labels exist, and shelling out and parsing `alembic heads` is brittle (the architect call-out: output format is not a stable API, and label substrings collide with revision-id substrings).

`server/scripts/migrate.py`:

```python
#!/usr/bin/env python3
"""Forward-only deploy migrator.

Reads OPDS_SYNC_PROGRESS_ENABLED and OPDS_SYNC_AI_ENABLED, then runs
`alembic upgrade <branch>@head` for each enabled branch that exists in
the script directory. Always upgrades the common backbone first by
targeting the explicit backbone-tip revision (e.g. "0004").

Idempotent. Forward-only. Per-branch downgrades remain a manual op.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

from alembic import command
from alembic.config import Config
from alembic.script import ScriptDirectory


def _is_truthy(val: str | None, default: bool = True) -> bool:
    if val is None:
        return default
    return val.strip().lower() in {"1", "true", "yes", "on"}


def _existing_branch_labels(script: ScriptDirectory) -> set[str]:
    """Return the set of branch labels that appear anywhere in the script directory.

    Alembic stores branch labels on individual revisions. We walk every revision
    once and collect labels. Cheap (O(n) where n is migration count).
    """
    labels: set[str] = set()
    for rev in script.walk_revisions():
        if rev.branch_labels:
            labels.update(rev.branch_labels)
    return labels


def _upgrade_branch(cfg: Config, branch: str) -> None:
    print(f"[migrate] upgrading {branch}@head", flush=True)
    command.upgrade(cfg, f"{branch}@head")


def _backbone_head(script: ScriptDirectory) -> str:
    """Return the last revision on the unlabeled linear backbone.

    The backbone is the prefix of the migration history where every revision
    has `branch_labels = None`. The first labeled revision is a child of the
    backbone tip. We always walk forward from base and stop at the first
    labeled revision — taking a `get_heads()` shortcut would be wrong once
    any branch exists (e.g. after `ai_001` lands, `get_heads()` returns
    `["ai_001"]` but the backbone tip is still `0004`).
    """
    # walk_revisions() goes newest→oldest, so reverse to walk oldest→newest.
    backbone_tip = None
    for rev in reversed(list(script.walk_revisions())):
        if rev.branch_labels:
            break
        backbone_tip = rev.revision
    if backbone_tip is None:
        # Shouldn't happen — would mean the very first revision is labeled.
        raise RuntimeError("no unlabeled backbone found in script directory")
    return backbone_tip


def main() -> int:
    cfg_path = Path(os.environ.get("ALEMBIC_INI", "alembic.ini"))
    cfg = Config(str(cfg_path))
    script = ScriptDirectory.from_config(cfg)

    labels = _existing_branch_labels(script)
    progress_enabled = _is_truthy(os.environ.get("OPDS_SYNC_PROGRESS_ENABLED"))
    ai_enabled = _is_truthy(os.environ.get("OPDS_SYNC_AI_ENABLED"))

    # Step 1: always ensure the unlabeled backbone is applied. This is the
    # foundation every branch depends on. On a fresh DB this is the first
    # `alembic upgrade` call ever made; on existing prod DBs at 0004 it's
    # a no-op. We use the explicit backbone-tip revision rather than
    # `upgrade head` because `head` is ambiguous in multi-head graphs.
    backbone = _backbone_head(script)
    print(f"[migrate] upgrading backbone to {backbone}", flush=True)
    command.upgrade(cfg, backbone)

    # Step 2: per-branch upgrades. Order is core → progress → ai for
    # determinism; branches are independent, so order doesn't matter
    # for correctness.
    if "core" in labels:
        _upgrade_branch(cfg, "core")

    if progress_enabled and "progress" in labels:
        _upgrade_branch(cfg, "progress")
    elif progress_enabled:
        print("[migrate] progress enabled but no progress branch; skipping", flush=True)

    if ai_enabled and "ai" in labels:
        _upgrade_branch(cfg, "ai")
    elif ai_enabled:
        print("[migrate] ai enabled but no ai branch; skipping", flush=True)

    return 0


if __name__ == "__main__":
    sys.exit(main())
```

Dockerfile changes:

```dockerfile
# ... existing layers ...
COPY scripts ./scripts          # NEW: ship the wrapper into the image
COPY migrations ./migrations
COPY alembic.ini ./
EXPOSE 8000
CMD ["sh", "-c", "python /app/scripts/migrate.py && exec uvicorn opds_sync.main:app --host 0.0.0.0 --port 8000"]
```

`server/docker-compose.yml` currently overrides the image command with `alembic upgrade head`. Update the override to invoke the wrapper too, so local compose deploys go through the same migrate path as k8s:

```yaml
command: >
  sh -c "python /app/scripts/migrate.py &&
         uvicorn opds_sync.main:app --host 0.0.0.0 --port 8000"
```

> **Why Python instead of shell?** Per architect review: shell parsing of `alembic heads` is brittle (substring matches on revision IDs, output format isn't stable API). `ScriptDirectory.get_heads()` / `walk_revisions()` are Alembic's documented stable APIs. The Python script is ~50 lines and the only added "complexity" is that the deploy image already has Alembic installed — there's no Python bootstrap overhead.

## 6. Migration backwards-compat

A deployed DB at `alembic_version = '0004'` after this PR lands:

- `ScriptDirectory.walk_revisions()` finds no `branch_labels`.
- Wrapper's `labels` set is empty.
- Wrapper computes backbone tip = `0004` (via `_backbone_head`), runs `command.upgrade(cfg, "0004")` → no-op (DB already at `0004`). No branches materialized → no further upgrades.

When PR-C lands `ai_001` (with `branch_labels = ("ai",), down_revision = "0004"`):

- `walk_revisions()` finds `branch_labels = ("ai",)` on `ai_001`. `labels = {"ai"}`.
- Full-stack deploy: `ai_enabled=True`, `"ai" in labels` → `command.upgrade(cfg, "ai@head")` advances DB to `ai_001`.
- Sync-only deploy (`ai_enabled=False`): ai branch skipped. DB stays at `0004`.
- `0004` is no longer a "head" per Alembic — it has `ai_001` as a child on the `ai` branch — but it is still a tip of the (currently unlabeled) progress side. `alembic_version` table on a full-stack DB now contains `ai_001` (and conceptually still represents the backbone up to `0004` via ancestry).

When PR1 later lands `progress_001` (with `branch_labels = ("progress",), down_revision = "0004"`, created via `alembic revision --head=0004 --splice --branch-label=progress`):

- `labels = {"ai", "progress"}`.
- Full-stack: both `command.upgrade(cfg, "ai@head")` and `command.upgrade(cfg, "progress@head")` run. After both, `alembic_version` contains two rows: `ai_NNN` and `progress_NNN`.
- Sync-only: only `progress` runs. `alembic_version` contains `progress_NNN` only; the progress side knows `0004` is an ancestor via the linear chain.

### Edge cases handled

1. **`alembic_version` row count.** Alembic stores one row per branch head currently applied. When upgrading from a single-head state into a multi-branch state for the first time, Alembic inserts the second row transparently (because both heads share `0004` as a common ancestor).
2. **Sequential safety.** `command.upgrade()` acquires the same locks Alembic CLI would; the script invokes them sequentially.
3. **Operator changes flags mid-life.** Pod restart re-runs the migrate script. If the operator enables AI on a previously sync-only deploy, the ai branch upgrades from `0004` (the common ancestor) up to `ai@head`. Alembic handles this because the ai chain's `down_revision = "0004"` is already in the DB's history (transitively, via the backbone).
4. **Both flags `false`.** Wrapper still upgrades the backbone (Step 1 is unconditional). No branch upgrades run. The DB advances to the backbone tip (today: `0004`) and no further. `/readyz` requires only the backbone in this state → returns 200.

## 7. Middleware

### 7.1 Request-ID

`opds_sync/api/middleware/request_id.py`:

- Read `X-Request-ID` header. If absent or longer than 128 chars or non-printable, generate `uuid.uuid4().hex`.
- Bind to `contextvars.ContextVar` named `request_id` (module-level in `opds_sync.core.logging_ctx` so logs everywhere can read it).
- Set response header `X-Request-ID` to the bound value (always echoed back, even for 4xx/5xx).
- Logging formatter (existing `python-json-logger`) updated via a `filter` that injects `request_id` from the contextvar into every record.

### 7.2 Request-size limit

`opds_sync/api/middleware/request_size.py`:

- Inspect `Content-Length` header pre-body. If > `settings.max_request_bytes`, return `413 Payload Too Large` with JSON body `{"detail": "request body exceeds <N> bytes"}` and short-circuit.
- If `Content-Length` is absent (chunked transfer), wrap the body stream and count bytes, raising 413 mid-stream if the cap is exceeded.
- GET/HEAD/OPTIONS/DELETE requests skip the check (no body).

### 7.3 Order

Middleware order matters. ASGI middleware executes outer→inner on request, inner→outer on response. We want:

```
Outer (response: last to run)
└─ RequestIDMiddleware       (binds contextvar; logs request)
    └─ RequestSizeMiddleware (rejects oversized BEFORE app sees it)
        └─ FastAPI routers
Inner (response: first to run)
```

So: register `RequestSize` first (innermost), then `RequestID` (outermost). `app.add_middleware()` adds to the outside, so we call:

```python
app.add_middleware(RequestSizeMiddleware, max_bytes=settings.max_request_bytes)
app.add_middleware(RequestIDMiddleware)
```

(That's reverse of registration order: the last `add_middleware` is outermost.)

## 8. Router mounting

```python
def create_app() -> FastAPI:
    settings = get_settings()
    ...
    app = FastAPI(...)

    # Always-on root endpoints.
    from opds_sync.api.health import router as health_router
    app.include_router(health_router)  # no prefix; /health + /readyz

    if settings.progress_enabled:
        from opds_sync.api.progress import router as progress_router
        app.include_router(progress_router, prefix="/sync/v1")

    if settings.ai_enabled:
        from opds_sync.api.ai import router as ai_router
        from opds_sync.core.ai.client import AIClient
        from opds_sync.core.ai.retrieval import Retriever
        from opds_sync.core.ai.service import InsightOrchestrator
        ...  # existing orchestrator setup
        app.include_router(ai_router, prefix="/ai/v1")

    # Middleware (registered last; runs first per ASGI semantics).
    app.add_middleware(RequestSizeMiddleware, max_bytes=settings.max_request_bytes)
    app.add_middleware(RequestIDMiddleware)

    return app
```

## 9. Test matrix

### 9.1 Unit tests (`tests/unit/`)

- `test_middleware_request_id.py`: generated when header absent; preserved when present; rejected when > 128 chars; response always echoes back; contextvar bound for downstream code.
- `test_middleware_request_size.py`: 413 when Content-Length exceeds cap; passes through when under cap; works for chunked transfer (no Content-Length); skipped for GET/DELETE.
- `test_health_payload.py`: `/health` returns correct modes for each (progress, ai) flag combo.

### 9.2 Integration tests (`tests/integration/`)

- `test_modes.py`: parametrized over `("full", "sync_only", "ai_only", "neither")`:
  - App boots cleanly.
  - `/health` returns the right `modes` (including empty list for `neither`).
  - In sync-only: `/sync/v1/progress` reachable; `/ai/v1/*` returns 404.
  - In ai-only: `/ai/v1/config` reachable (with auth stub); `/sync/v1/*` returns 404.
  - In full: both reachable.
  - In neither: only `/health` and `/readyz` mounted; everything else 404.
- `test_lazy_imports.py`: spawns subprocess via `sys.executable -c "..."` per mode. The script imports `opds_sync.main`, calls `create_app()`, and asserts forbidden modules are absent from `sys.modules` (see §4 table). Subprocess is the only honest way; in-process `importlib.reload` leaks transitive caches.
- `test_readyz_migration_state.py`:
  - Backbone-only state (today): `/readyz` returns 200; `heads_applied = ["0004"]`.
  - Stamp the DB to `0003`; `/readyz` returns 503 listing `0004` as missing.
  - With a synthetic ai branch fixture (see §9.4): DB at `0004` + ai branch enabled + ai head not applied → 503; ai head applied → 200.
- `test_migrate_script.py`: invokes `python scripts/migrate.py` against the testcontainer DB.
  - Run against a DB stamped to `0004` (no branch labels yet) → exit 0, DB still at `0004`.
  - Run twice in a row → second run is a no-op.
  - With a synthetic branched script directory (a tmp copy of `migrations/` plus a `ai_test_001` revision with `branch_labels=("ai",)`): ai-enabled run upgrades to `ai_test_001`; ai-disabled run skips it.

### 9.3 Synthetic branch graph tests (`tests/unit/test_migrate_logic.py`)

Pure unit tests for the migrate script's branch detection and selection logic, without touching a DB:

- No branch labels in script dir → fallback path.
- Only `ai` label → ai upgrade attempted, progress skipped silently.
- Only `progress` label → vice versa.
- All three labels → all three attempts.
- Flag combinations: `ai_enabled=False` with `ai` label present → ai is skipped even though label exists.

Implemented via a fake `ScriptDirectory` (or by pointing `_existing_branch_labels` at a stub directory).

### 9.4 CI

Extend `.github/workflows/server-ci.yaml` `test` job: parametrize the pytest invocation across the three modes via env-var matrix, so a regression in any mode fails CI. Concretely: add a matrix on the existing `test` job:

```yaml
strategy:
  fail-fast: false
  matrix:
    mode: [full, sync_only, ai_only]
env:
  OPDS_SYNC_PROGRESS_ENABLED: ${{ matrix.mode != 'ai_only' }}
  OPDS_SYNC_AI_ENABLED: ${{ matrix.mode != 'sync_only' }}
```

Tests that exercise routers from the *other* mode skip themselves when the relevant flag is off (via a `requires_progress` / `requires_ai` pytest marker). The lazy-import test is mode-agnostic — it spawns subprocesses with the env set per-case. The migrate script test runs in every matrix cell (it exercises the wrapper's flag logic and synthetic branch graph).

## 10. Documentation

- Update `docs/architecture.md` with a "Deploy modes" section.
- Update `docs/sync-api.md` to note `/health` and `/readyz` are root-mounted and always available.
- Add `server/scripts/README.md` (or a section in `server/README.md`) describing the migrate wrapper and the per-branch upgrade flow.
- Note in `migrations/README.md` (new) the branch-labeling convention for future migrations.

## 11. Out of scope

- Actually creating `progress_001` or `ai_001` migrations — that's PR1 / PR-C.
- AI-only mode plumbing in Android (PR9/PR8 will worry about it).
- Hosted-mode HMAC auth (PR-B).
- Reference compose with Caddy (PR10).
- Quota / rate-limit changes (separate concern; the existing daily budget / regen limit stays as-is).

## 12. Risks & mitigations

| Risk                                                                                | Mitigation                                                                                                                |
| ----------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------- |
| Operator misreads default flip and accidentally turns off AI on existing prod       | Deployment note in PR body; cluster's manifest already pins `OPDS_SYNC_AI_ENABLED=true` explicitly per deployment doc.    |
| Lazy-import test passes locally but a transitive top-level import slips through CI  | Subprocess test runs in CI matrix across all three modes; subprocess calls `create_app()` not just module-import.         |
| Future PR adds a migration with `branch_labels` but forgets `--splice` and silently creates a third head | `migrations/README.md` documents the exact recipe; CI test asserts `set(ScriptDirectory.get_heads())` matches the explicit-allowlist of expected heads. The "allowlist" for this PR is `{"0004"}`. Each subsequent branch-introducing PR is expected to update the allowlist. |
| Middleware order mistake (size limit runs AFTER request-id is unbound)              | Explicit ordering in `create_app`, asserted by a unit test that sends an oversized POST and asserts response has request-id header (both Content-Length and chunked variants). |
| Removing `/sync/v1/healthz` breaks the cluster's k8s liveness probe                 | Document in PR body; the k8s manifest change is a follow-up commit in `theficos-cluster`. We deliberately drop the old paths to force the cluster bump rather than carry a forever-alias. |
| Migrate script silently skips an enabled branch that has no label yet               | Logged at INFO with explicit "skipping" message; absence of branch label means there's nothing to apply anyway.           |

## 13. Deliverables checklist

- [ ] `migrations/README.md` documenting branch label convention + splice rule for first-on-branch migrations.
- [ ] `server/scripts/migrate.py` (Python, not shell) + `Dockerfile` CMD update.
- [ ] `opds_sync/config.py` new settings: `ai_enabled` default flip, `progress_enabled`, `max_request_bytes`.
- [ ] `opds_sync/api/middleware/request_id.py` + `request_size.py`.
- [ ] `opds_sync/core/logging_ctx.py` (contextvar + log filter).
- [ ] `opds_sync/api/health.py` rewritten to root-mount with `/health` + `/readyz` (heads-check logic per §5.2).
- [ ] `opds_sync/main.py` rewritten with conditional mounts + lazy imports per §4 forbidden-module table.
- [ ] Unit + integration tests per §9, including: `test_modes.py` (4 modes), `test_lazy_imports.py` (subprocess + `create_app()`), `test_readyz_migration_state.py`, `test_migrate_script.py`, `test_migrate_logic.py`.
- [ ] `.github/workflows/server-ci.yaml` mode matrix.
- [ ] `docs/architecture.md` deploy modes section.
- [ ] Existing tests updated (path changes for health, etc.).
- [ ] PR body calls out the `OPDS_SYNC_AI_ENABLED` default flip (False → True) and the prod-DB-at-0004 backwards-compat statement.
