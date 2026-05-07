# Development

## Prerequisites

- **Docker** — Android builds run inside a pinned image via `scripts/dgradle`,
  and the server's tests use testcontainers for Postgres.
- **uv** — for the Python sync server (`server/`).
- A calibre-web instance reachable from your dev device (for end-to-end OPDS
  testing). Optional for unit tests.

The host JDK and Android SDK are not required — `scripts/dgradle` provides
both.

## Android

### `scripts/dgradle`

`scripts/dgradle` runs Gradle inside `quire-build:latest`, an image
built from the project's `Dockerfile` (Eclipse Temurin 17 + Android cmdline
tools, pinned platform 34, build-tools 34.0.0).

```sh
scripts/dgradle :app:assembleDebug
scripts/dgradle test
scripts/dgradle :core:identity:test
scripts/dgradle lint
```

First invocation builds the image (~5 minutes on Apple Silicon under Rosetta).
The Gradle and Android caches live in named Docker volumes
(`quire-gradle-cache`, `quire-android-cache`) so rebuilds are
fast.

Use `scripts/dgradle` rather than the host `./gradlew` — host JDK/SDK drift
will produce confusing failures.

### Configuring the app

The app needs no compile-time configuration. On first launch it asks
the user for their calibre-web base URL, username, and password; those
go into the Android Keystore and drive both OPDS browsing and sync.

For host (non-Docker) builds you may need a `local.properties`
pointing Gradle at your Android SDK:

```sh
cp local.properties.template local.properties
# Edit sdk.dir if Gradle can't find your SDK.
```

`scripts/dgradle` provides its own SDK and ignores `local.properties`.

### Module layout

```
:app           Compose UI, navigation, DI wiring
:auth          Keystore-backed calibre-web Basic credential store
:core:identity Document identity (hash + dc:identifier normalization)
:core:model    Domain types
:data:local    Room DB, DAOs, sync outbox
:data:opds     calibre-web OPDS client
:data:sync     opds-sync REST client + WorkManager job
:reader        Readium navigator integration
```

`:core:identity` is intentionally tiny and dependency-light — its rules are
shared spec with the server, and both have unit tests against an identical
fixture set.

### Tests

```sh
scripts/dgradle test                         # all unit tests
scripts/dgradle :core:identity:test          # one module
```

Identity fixtures live in `core/identity/src/test/resources` and mirror the
Python fixtures in `server/tests/`. If you change one, change the other.

### Lint

```sh
scripts/dgradle lint
```

### Building an APK

```sh
scripts/dgradle :app:assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

Debug builds are versioned `YYYY.MM.DD.<run>` (CalVer + CI run number) on the
CI path. Local builds use a placeholder version.

## Sync server (`server/`)

### Setup

```sh
cd server
uv venv && source .venv/bin/activate
uv pip install -e ".[dev]"
```

### Run

```sh
uv run uvicorn opds_sync.main:app --reload
```

Listens on `http://localhost:8000`. Configuration is via environment variables;
see `opds_sync/config.py` for the full list. At minimum:

```
OPDS_SYNC_DATABASE_URL=postgresql+asyncpg://postgres:postgres@localhost:5432/opds_sync
OPDS_SYNC_CWA_BASE_URL=https://library.example.com
# Optional, defaults shown:
OPDS_SYNC_CWA_PROBE_PATH=/opds
OPDS_SYNC_CWA_PROBE_TIMEOUT_S=3.0
OPDS_SYNC_AUTH_CACHE_POSITIVE_TTL_S=60
OPDS_SYNC_AUTH_CACHE_NEGATIVE_TTL_S=10
```

### Tests

```sh
uv run pytest                    # unit + integration
uv run pytest tests/unit         # unit only
uv run pytest -k progress        # filter
```

Integration tests spin up Postgres via testcontainers — Docker must be
running.

### Migrations

```sh
uv run alembic revision --autogenerate -m "add foo"
uv run alembic upgrade head
```

### Lint / format

```sh
uv run ruff check
uv run ruff format
```

### Module layout

```
opds_sync/
  api/
    health.py        /healthz, /readyz
    progress.py      /sync/v1/progress
    # bookmarks.py — planned
    # documents.py   — /documents/alias, alias reconciliation (planned)
  core/
    auth.py          Basic-header validator (probes calibre-web /opds, TTL-cached)
    identity.py      Identity normalization (mirrors :core:identity)
    # merge.py       — record-level LWW for bookmark alias merge (planned)
  db/
    models.py        SQLAlchemy models
    session.py
  main.py            FastAPI app factory
migrations/          Alembic
tests/
  unit/
  integration/       Real Postgres via testcontainers
```

## CI

Two workflows in `.github/workflows/`:

- `android-ci.yaml` — fires on Android-relevant paths. Assembles debug APK,
  runs `test` and `lint`, uploads the APK and tags `vYYYY.MM.DD.<run>` on
  pushes to `main`.
- `server-ci.yaml` — fires on `server/**`. Runs `pytest` and `ruff`.

Both workflows use path filters so an Android-only change doesn't run server
CI and vice versa.

## Releases

Pushes to `main` that touch Android paths produce:

- An uploaded debug APK artifact named `app-debug-<version>`.
- A `vYYYY.MM.DD.<run>` git tag.

Server is deployed via the cluster's Kustomize app
(`applications/opds-sync/`); see the cluster repo for the rollout flow.

## Conventions

- **Commit messages**: gitmoji + conventional commits, e.g.
  `:sparkles: feat: progress sync server (FastAPI + deploy)`.
- **Identity rules** (Kotlin and Python) must stay in lockstep — fixture
  parity is the only enforcement, so update both sides together.
- **Migrations are append-only.** Once a migration ships to any environment it
  is immutable; create a new one to change anything.
