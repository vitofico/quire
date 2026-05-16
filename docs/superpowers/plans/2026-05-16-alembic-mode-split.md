# Plan — Alembic branch split + deploy mode flags (PR-A)

> Shipped in 37c9845 on 2026-05-16 as PR #10.

**Spec:** `docs/superpowers/specs/2026-05-16-alembic-mode-split-design.md`
**Approach:** TDD where the seam allows (middleware, health, config), exec-test for the migrate script and lazy imports.

Each task is a checkbox. Order is enforced by the dependency graph — earlier tasks unblock later ones. Verification commands are listed inline.

## Phase 0 — Baseline

- [ ] **0.1 Confirm green baseline.** `cd server && uv run pytest -v` passes on the freshly-checked-out branch. Capture pre-change test count for the "no regression" claim.

## Phase 1 — Config + always-on health (no behavior change for AI/progress)

- [ ] **1.1 RED: `tests/unit/test_settings.py::test_defaults`.** New test asserting `ai_enabled=True, progress_enabled=True, max_request_bytes=1_048_576` after the change.
- [ ] **1.2 GREEN: edit `opds_sync/config.py`** — flip `ai_enabled` default to `True`, add `progress_enabled: bool = True`, add `max_request_bytes: int = 1_048_576`.
- [ ] **1.3 RED: `tests/unit/test_health_payload.py`.** Hits `/health` with both flags `True`, asserts payload shape `{ready: true, modes: ["progress","ai"]}`. Mock the settings via monkeypatch + `get_settings.cache_clear()`.
- [ ] **1.4 GREEN: rewrite `opds_sync/api/health.py`** to mount on root (no `/sync/v1` prefix), return new payload shape. Add `/readyz` heads-check logic.
- [ ] **1.5 Move health mount in `main.py`.** Remove `/sync/v1` prefix from the health router include; mount it always (outside mode-gated branches).
- [ ] **1.6 Update `tests/integration/test_health.py`** to hit `/health` and `/readyz` at root, with new payload assertions. Keep the legacy `app_under_test` fixture wired.

## Phase 2 — Middleware

- [ ] **2.1 RED: `tests/unit/test_logging_ctx.py`.** Asserts `request_id_var` exists in `opds_sync.core.logging_ctx`, is a `ContextVar[str]`, defaults to empty string.
- [ ] **2.2 GREEN: create `opds_sync/core/logging_ctx.py`** with `request_id_var: ContextVar[str] = ContextVar("request_id", default="")` and a logging filter `RequestIdLogFilter`.
- [ ] **2.3 RED: `tests/unit/test_middleware_request_id.py`.** Cases: header preserved when valid; generated when absent; replaced when > 128 chars; replaced when non-printable; response always echoes `X-Request-ID`; contextvar is set during request lifetime.
- [ ] **2.4 GREEN: create `opds_sync/api/middleware/__init__.py`** and `request_id.py` (Starlette `BaseHTTPMiddleware` subclass).
- [ ] **2.5 RED: `tests/unit/test_middleware_request_size.py`.** Cases: 413 when Content-Length > cap; pass-through when under cap; 413 for chunked body that exceeds cap; skipped for GET/DELETE/HEAD/OPTIONS.
- [ ] **2.6 GREEN: create `opds_sync/api/middleware/request_size.py`.**
- [ ] **2.7 Wire middleware into `create_app`.** Order: `add_middleware(RequestSize, …)` first, then `add_middleware(RequestID)` (so RequestID is outermost).
- [ ] **2.8 Integration assertion.** New `tests/integration/test_middleware_integration.py` confirms request-id echoes on a real ASGI roundtrip and survives a 413 response, for both Content-Length-declared and chunked oversized bodies. Also assert the contextvar resets to its default after the response (so a subsequent request without a header doesn't see the previous request's id).

## Phase 3 — Conditional router mounting + lazy imports

- [ ] **3.1 RED: `tests/integration/test_modes.py::test_full_mode`.** With both flags `True`, `/health` returns `modes=["progress","ai"]`, `/sync/v1/healthz` is 404 (moved), `/sync/v1/progress` is reachable (returns 401 without auth), `/ai/v1/config` reachable.
- [ ] **3.2 RED: `test_modes.py::test_sync_only_mode`.** With `OPDS_SYNC_AI_ENABLED=false`: `/ai/v1/config` returns 404; `/sync/v1/*` works; `/health` returns `modes=["progress"]`.
- [ ] **3.3 RED: `test_modes.py::test_ai_only_mode`.** With `OPDS_SYNC_PROGRESS_ENABLED=false`: `/sync/v1/*` returns 404; `/ai/v1/config` works; `/health` returns `modes=["ai"]`.
- [ ] **3.4 RED: `test_modes.py::test_neither_mode`.** Both flags false; only `/health` (`modes=[]`) and `/readyz` mounted; everything else 404.
- [ ] **3.5 GREEN: rewrite `opds_sync/main.py::create_app`.** Move `from opds_sync.core.ai.*` and `from opds_sync.api.ai` imports inside the `if settings.ai_enabled` block. Move `from opds_sync.api.progress` inside the `if settings.progress_enabled` block. Keep `CalibreAuthValidator` outside both (sync uses it; if progress is off but for example future routers also need basic auth, the validator is shared).
- [ ] **3.6 Lazy-import subprocess test.** `tests/integration/test_lazy_imports.py`: for each mode, spawn `sys.executable -c "..."` (NOT `uv run python`) with the mode env vars set. Subprocess: `import opds_sync.main; opds_sync.main.create_app(); assert "opds_sync.api.ai" not in sys.modules` (and the rest of the forbidden table from spec §4). Test passes only when create_app() has run AND no forbidden modules were imported.

## Phase 4 — Alembic branching convention + readyz heads check

- [ ] **4.1 Write `migrations/README.md`** documenting:
  - The `0001..0004` linear backbone (do not rewrite).
  - The branch label convention: first migration on a branch sets `branch_labels=("ai"|"progress"|"core",), down_revision="0004"` (or the latest pre-branch revision); subsequent migrations on the branch leave `branch_labels=None`.
  - The Alembic splice rule: once one branch exists, adding the first of another branch needs `alembic revision --head=0004 --splice --branch-label=<name>` (otherwise Alembic refuses to insert a new branch off a non-head revision).
  - Copy-paste templates for: first-on-branch, subsequent-on-branch.
- [ ] **4.2 Implement `/readyz` heads check per spec §5.2.** Compute the required-heads set: empty-labels fallback returns `{backbone_head}`; otherwise the enabled-and-materialized branch heads. Read `alembic_version` rows via `text("SELECT version_num FROM alembic_version")`. Compare; 503 if subset mismatch.
- [ ] **4.3 RED: `tests/integration/test_readyz_migration_state.py`.** Cases:
  - DB at `0004`, no branch labels → 200, `heads_applied=["0004"]`.
  - DB stamped to `0003`, no branch labels → 503, detail names `0004` as missing.
  - DB at `0004`, ai-enabled, no ai label yet → 200 (no materialized head required; fallback requires backbone `0004`, which is present).
  - DB at `0004`, both flags `false` → 200 (fallback requires backbone only).
  - DB stamped to `0003`, both flags `false` → 503 (backbone fallback still missing).
  - Synthetic case: ai label exists in script dir but AI disabled, DB at `0004` → 200 (fallback requires backbone, not ai head; this locks in that the disabled branch's head doesn't sneak into the required set).
- [ ] **4.4 GREEN: implement heads check in `health.readyz`.** Use Alembic's `Config("alembic.ini")` + `ScriptDirectory.from_config()` (resolve `alembic.ini` relative to cwd, which is `/app` in container and `server/` in tests).

## Phase 5 — Migrate wrapper (Python, not shell)

- [ ] **5.1 RED: `tests/unit/test_migrate_logic.py`.** Synthetic-graph tests for the migrate module's pure logic: `_existing_branch_labels` with a stub ScriptDirectory; flag-to-action mapping for each (progress, ai, labels-present) combination. No DB.
- [ ] **5.2 GREEN: write `server/scripts/migrate.py`** per spec §5.3. Use `alembic.script.ScriptDirectory.walk_revisions()` for label detection and `alembic.command.upgrade()` for the actual run. Make it a real module (so unit tests can `from scripts.migrate import _existing_branch_labels`).
- [ ] **5.3 Update `Dockerfile`** so it `COPY scripts ./scripts` and `CMD` runs `python /app/scripts/migrate.py` before uvicorn. Also update `server/docker-compose.yml` so its `command` override calls the wrapper instead of `alembic upgrade head` directly.
- [ ] **5.4 Smoke test the script** against the testcontainer Postgres. New `tests/integration/test_migrate_script.py`:
  - Run against DB stamped to `0004` (today's prod state) → exit 0, no labels detected → fallback `upgrade head` → DB at `0004`.
  - Run twice in a row → second run is a no-op.
  - Synthetic branched-script test: copy `migrations/` to a tmp dir, add a `ai_test_001` revision with `branch_labels=("ai",), down_revision="0004"`; run wrapper with `OPDS_SYNC_AI_ENABLED=true` → upgrades to `ai_test_001`; with `OPDS_SYNC_AI_ENABLED=false` → stays at `0004`.

## Phase 6 — CI matrix

- [ ] **6.1 Extend `.github/workflows/server-ci.yaml`** with a `strategy.matrix.mode` on the `test` job, exporting env vars per matrix entry.
- [ ] **6.2 Tag mode-specific tests** with `pytest.mark.requires_ai` / `pytest.mark.requires_progress` so they self-skip when the flag is off. Add markers in `pyproject.toml`.
- [ ] **6.3 Add a `migrate_script` matrix step or include a `tests/integration/test_migrate_script.py`** that always runs (since the script is mode-agnostic, only one matrix cell needs to assert it works; the test itself doesn't care about flags).

## Phase 7 — Verification & cleanup

- [ ] **7.1 Run `uv run ruff check . && uv run ruff format --check .`** — pre-commit will run these too; pre-emptively clean.
- [ ] **7.2 Run full test suite in each mode locally:**
  - `OPDS_SYNC_AI_ENABLED=true OPDS_SYNC_PROGRESS_ENABLED=true uv run pytest -v`
  - `OPDS_SYNC_AI_ENABLED=false OPDS_SYNC_PROGRESS_ENABLED=true uv run pytest -v`
  - `OPDS_SYNC_AI_ENABLED=true OPDS_SYNC_PROGRESS_ENABLED=false uv run pytest -v`
- [ ] **7.3 Verify lazy-import test PASSES** in subprocess form (not just via mocks).
- [ ] **7.4 Update `docs/architecture.md`** with deploy modes section.
- [ ] **7.5 Update `server/README.md`** with mode flags + migrate script.
- [ ] **7.6 Commit using gitmoji conventional commit.** Push branch. Open PR.

## Definition of done (audit before claiming done)

- All checkboxes above ticked.
- `cd server && uv run pytest -v` green in all three modes.
- No `:robot:`/Co-Authored-By/"Generated with Claude Code" trailers anywhere.
- `git diff main -- server/migrations/versions/0001_initial.py server/migrations/versions/0002_progress_finished_at.py server/migrations/versions/0003_ai_tables.py server/migrations/versions/0004_ai_insight_tone.py` returns empty.
- PR body includes architect verdict.
