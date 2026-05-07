# Quire

A self-hosted reading stack: a native Android EPUB reader (**Quire**) backed by
a small FastAPI sync service (**opds-sync**), pulling books from an existing
[calibre-web] instance over OPDS.

The sync server is the source of truth for reading state. calibre-web stays
stateless from the reader's perspective — it serves books, nothing more.

```
[calibre-web]  ──OPDS + HTTP Basic──>  [Android: Quire / Readium]
                                              │
                                              │  HTTPS + JWT (Authentik)
                                              ▼
                                        [opds-sync]
                                              │
                                              ▼
                                         [Postgres]
```

## Status

| Phase | Scope | Status |
|---|---|---|
| 1 | Local reader: OPDS browse + download, EPUB rendering, local progress | shipped |
| 2 | Progress sync server + Android sync client | shipped |
| 2.1 | calibre-web auth proxy | shipped |
| 3 | Highlights sync | not started |
| 4 | Notes & bookmarks | not started |
| 5 | PDF support | deferred |
| 6 | Calibre plugin (read-only consumer) | not started |

## Repo layout

```
app/                  Android entry point — Compose UI, navigation, DI wiring
auth/                 AppAuth (OIDC + PKCE) wrapper, Keystore credential store
core/identity/        Document identity: hash + dc:identifier normalization
core/model/           Domain types (Document, Annotation, Progress)
data/local/           Room database, DAOs
data/opds/            calibre-web OPDS client
data/sync/            opds-sync REST client + WorkManager job
reader/               Readium navigator integration
server/               opds-sync (Python / FastAPI)
docs/                 Architecture, development, sync API reference
scripts/dgradle       Gradle wrapper that runs inside the project's Docker image
Dockerfile            Reproducible Android build environment (linux/amd64)
```

## Quick start

### Android app

```sh
cp local.properties.template local.properties
# Edit local.properties: sdk.dir + calibreweb.{baseUrl,username,password}

scripts/dgradle :app:assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

`scripts/dgradle` builds and uses a pinned Docker image so host JDK/Android-SDK
state doesn't matter. Always prefer it over the host `./gradlew`.

### Sync server

```sh
cd server
uv venv && source .venv/bin/activate
uv pip install -e ".[dev]"
uv run pytest                                  # spins up Postgres in Docker
uv run uvicorn opds_sync.main:app --reload     # http://localhost:8000
```

See [`server/README.md`](server/README.md) for more.

## Documentation

- [`docs/architecture.md`](docs/architecture.md) — components, document
  identity, sync model, conflict resolution.
- [`docs/development.md`](docs/development.md) — module layout, build commands,
  testing, CI, releases.
- [`docs/sync-api.md`](docs/sync-api.md) — REST surface of `opds-sync`: auth,
  endpoints, wire formats.

[calibre-web]: https://github.com/janeczku/calibre-web
