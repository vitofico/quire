# Contributing to Quire / opds-sync

Thanks for considering a contribution. This is a small, opinionated
project; please read this before opening a PR.

## Ground rules

- **Scope is deliberately narrow.** Quire is an EPUB reader for people
  with self-hosted calibre-web. PRs that drag in cloud SDKs, analytics,
  ads, or third-party sync backends will be closed.
- **No telemetry, ever.** Crash reporting, usage analytics, performance
  pingbacks — all out of scope.
- **One credential.** The architecture relies on calibre-web Basic auth
  end-to-end. PRs that add a second IdP for the sync server should open
  a discussion first.

## Development setup

See [`docs/development.md`](docs/development.md). Short version:

### Pre-commit hooks (recommended)

```sh
# One-time install (uses the .pre-commit-config.yaml in the repo).
pip install pre-commit       # or: uv tool install pre-commit
pre-commit install
```

The hooks run on every commit and mirror the cheap parts of CI: ruff
on the server, file hygiene, secret detection. Gradle is intentionally
not in pre-commit (too slow for a hook); CI catches Android build
issues. To run all hooks against the whole tree once:

```sh
pre-commit run --all-files
```

### Build / test


```sh
# Android
scripts/dgradle :app:assembleDebug
scripts/dgradle test
scripts/dgradle lint

# Server
cd server
uv venv && source .venv/bin/activate
uv pip install -e ".[dev]"
uv run pytest
uv run ruff check . && uv run ruff format --check .
```

Both halves must build and test green before a PR is merged. CI will
tell you if they don't.

## Pull requests

- Branch from `main`. One topic per PR.
- Keep diffs reviewable: under ~400 lines is ideal, under ~800 lines is
  acceptable, beyond that needs a reason.
- Update docs in the same PR as code. Stale docs are worse than missing
  docs.
- For Android UI changes, attach before/after screenshots.
- For server schema changes, include the Alembic migration. Migrations
  are append-only — once shipped, immutable.

## Commit messages

Gitmoji + conventional commits. Examples:

```
:sparkles: feat: add bookmarks sync endpoint
:bug: fix: handle 401 from calibre-web during auth probe
:memo: docs: clarify identity precedence
:white_check_mark: test: cover the alias-merge transaction
:construction_worker: ci: pin actions to commit SHAs
:wrench: chore: bump renovate config
```

Common gitmoji used here: `:sparkles:` (feat), `:bug:` (fix),
`:memo:` (docs), `:white_check_mark:` (tests), `:construction_worker:`
(CI), `:wrench:` (chore), `:construction:` (scaffolding / WIP),
`:art:` (refactor/style), `:fire:` (removals), `:lock:` (security),
`:page_facing_up:` (legal/license).

## Identity rules — Kotlin / Python parity

`core/identity` (Kotlin) and `server/opds_sync/core/identity.py`
implement the same normalization. They share fixtures (`core/identity/
src/test/resources/identity/`). **If you change one, change the other
in the same PR**, and update the fixtures.

## Tests

- New behaviour needs a test. Bug fixes need a regression test.
- For the server, prefer integration tests against the real Postgres
  testcontainer over mock-heavy unit tests.
- For Android, Robolectric tests are fine for module-level work; the
  app module currently has no instrumented tests.

### Load-bearing regressions (don't break these)

- **`tests/integration/test_cache_key_audit.py`** splits the audit into
  two parametrize lists (PR2, 2026-05-16):
  - **`SHARED_CACHE_TABLES`** (`book_insights`, `external_source_cache`,
    `book_themes` (PR3, 2026-05-17), plus future shared-cache tables):
    rows reused across every tenant requesting the same identity + model +
    prompt + tone + language. Carry **no** principal columns (`user_id`,
    `tenant_id`, `subject`, `principal_id`). The cross-tenant cache-hit
    property is load-bearing for hosted Quire Cloud AI economics. Any PR
    that adds a tenant column to a shared cache, or adds a new shared
    cache without registering it in this list, will break it on purpose.
    Per-call audit data goes on `ai_generation_log` instead.
  - **`SCOPED_ALIAS_TABLES`** (`insight_identity_aliases`): rows whose
    `user_id` is INTENTIONAL cache-key scoping, NOT a tenant-leak. The
    inverse-property test asserts `user_id` IS present on these tables,
    so removing it (which would let user A's OPDS aliases bleed into
    user B's catalog) fails loudly. `tenant_id` / `subject` /
    `principal_id` remain forbidden — only `user_id` is allow-listed.
- **`tests/integration/test_modes.py`** boots the app in each of the
  three deploy modes (full / sync-only / AI-only) against a fresh DB
  and verifies the router set and migration heads. New routers must
  declare their mode gate.
- **`tests/unit/test_ai_identity_resolution.py`** locks in the PR2
  identity-resolution semantics: canonical short-circuit, user-scoped vs
  global alias scope (`SCOPE_BY_SCHEME`), and `AliasConflict` on
  disagreeing canonicals. Any change to the alias scope rules or the
  resolution order must update this test deliberately.

### Cache-version checklist (server + Android together)

When bumping any cache-key dimension, move all three forward in the same
PR (or document why one was skipped):

- **`opds_sync/core/ai/prompts.py::PROMPT_VERSION`** — string. Currently
  `"4"` (PR3, 2026-05-17). Bump when prompt bytes change.
- **`opds_sync/api/ai_schemas.py::BookInsightPayload.schema_version`** —
  int. Currently `3` (PR3). Bump when payload shape changes.
- **Android Room schema version** in
  `data/local/src/main/java/io/theficos/ereader/data/local/db/EReaderDatabase.kt`
  — currently `5` (PR8, 2026-05-17 adds `seriesName`/`seriesIndex`). Every
  bump needs a `MIGRATION_n_n+1` entry and an exported schema JSON under
  `data/local/schemas/`.

`book_insights` unique indexes already cover `(metadata_id, model_id,
prompt_version, tone, language)` plus the `content_hash` variant; the
`service.py` orchestrator lock key must include every dimension that
participates in the cache key.

## Code of Conduct

By participating you agree to the [Code of Conduct](CODE_OF_CONDUCT.md).

## License

By submitting a contribution you agree it is licensed under the
project's [LICENSE](LICENSE) (Apache-2.0).
