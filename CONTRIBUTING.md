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
:sparkles: feat: add highlights sync endpoint
:bug: fix: handle 401 from calibre-web during auth probe
:memo: docs: clarify identity precedence
:white_check_mark: test: cover the alias-merge transaction
:construction_worker: ci: pin actions to commit SHAs
```

Common gitmoji used here: `:sparkles:` (feat), `:bug:` (fix),
`:memo:` (docs), `:white_check_mark:` (tests), `:construction_worker:`
(CI), `:art:` (refactor/style), `:fire:` (removals),
`:lock:` (security), `:page_facing_up:` (legal/license).

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

## Code of Conduct

By participating you agree to the [Code of Conduct](CODE_OF_CONDUCT.md).

## License

By submitting a contribution you agree it is licensed under the
project's [LICENSE](LICENSE) (Apache-2.0).
