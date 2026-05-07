# Quire

[![android-ci](https://github.com/vitofico/opds-ereader-android-app/actions/workflows/android-ci.yaml/badge.svg)](https://github.com/vitofico/opds-ereader-android-app/actions/workflows/android-ci.yaml)
[![server-ci](https://github.com/vitofico/opds-ereader-android-app/actions/workflows/server-ci.yaml/badge.svg)](https://github.com/vitofico/opds-ereader-android-app/actions/workflows/server-ci.yaml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

A self-hosted reading stack for people who already run [calibre-web]:

- **Quire** — native Android EPUB reader (Kotlin / Compose / Readium).
- **opds-sync** — small FastAPI service that stores reading progress and
  (later) bookmarks in Postgres.

calibre-web stays the source of truth for books. opds-sync is the source
of truth for reading state. Quire reconciles both on the device.

```
[calibre-web]  ──OPDS + HTTP Basic──>  [Android: Quire]
                                              │
                                              │  HTTPS + same Basic creds
                                              ▼
                                        [opds-sync]
                                              │
                                              ▼
                                         [Postgres]
```

## Why this exists

- KOReader on Android is great but isn't a Compose-native experience and
  doesn't sync to a self-hosted backend out of the box.
- Stock OPDS readers (Librera, Moon+) sync to the vendor's cloud, not yours.
- Calibre Companion is paid and Android-only.
- This stack is for people who want their reading state on **their own
  Postgres**, behind their own auth, with no telemetry and no third-party
  network calls.

## Privacy

- No analytics, no crash reporting, no third-party SDKs.
- Network calls go to exactly two places: your calibre-web instance and
  your opds-sync server. Both are configured by you, on first launch.
- Credentials are stored in Android Keystore (hardware-backed where the
  device supports it).

## Status

| Capability | Status |
|---|---|
| Local reader: OPDS browse + download, EPUB rendering, local progress | shipped |
| Progress sync server + Android sync client | shipped |
| Sync server uses calibre-web Basic auth (no separate IdP) | shipped |
| Bookmarks sync | not started |
| PDF support | deferred |
| Calibre plugin (read-only consumer) | not started |

This is pre-1.0 software built for the author's personal eink device. It
works, it's tested, but the API and DB schema may still change. Pin a
commit if you depend on it.

## Repo layout

```
app/                  Android entry point — Compose UI, navigation, DI wiring
auth/                 Keystore-backed calibre-web Basic credential store
core/identity/        Document identity: hash + dc:identifier normalization
core/model/           Domain types (Document, Progress, Bookmark)
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
# Build the debug APK using the project's Docker-based Gradle wrapper.
# Host JDK and Android SDK are not required.
scripts/dgradle :app:assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

On first launch the app asks for your calibre-web base URL, username,
and password. Nothing is hard-coded.

### Sync server

```sh
cd server
uv venv && source .venv/bin/activate
uv pip install -e ".[dev]"
uv run pytest                                  # spins up Postgres in Docker
OPDS_SYNC_CWA_BASE_URL=https://library.example.com \
  uv run uvicorn opds_sync.main:app --reload   # http://localhost:8000
```

See [`server/README.md`](server/README.md) for more.

## Documentation

- [`docs/architecture.md`](docs/architecture.md) — components, document
  identity, sync model, conflict resolution, auth.
- [`docs/development.md`](docs/development.md) — module layout, build
  commands, testing, CI, releases.
- [`docs/sync-api.md`](docs/sync-api.md) — REST surface of `opds-sync`.

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md). TL;DR: gitmoji + conventional
commits, `scripts/dgradle test` and `cd server && uv run pytest` must
pass, no telemetry / analytics PRs.

## Security

If you find a vulnerability, please follow [`SECURITY.md`](SECURITY.md)
rather than opening a public issue.

## License

Apache-2.0. See [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).

[calibre-web]: https://github.com/janeczku/calibre-web
