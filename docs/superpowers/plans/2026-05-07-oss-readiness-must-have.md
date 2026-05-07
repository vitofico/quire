# OSS Readiness — Must Have

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make this repository safe and useful to flip to public visibility on GitHub: legally licensed, accurately documented, with the standard community files an OSS contributor expects.

**Architecture:** Pure documentation, license, and community-files work. No production code changes. The codebase already implements the CWA-Basic-proxy auth model; the docs claim a removed Authentik/AppAuth/OIDC model — the bulk of this plan is bringing docs in sync with code.

**Tech Stack:** Markdown, plain text, GitHub community-file conventions. No build changes.

**License decision (default):** Apache-2.0 — matches the nowinandroid reference, includes a patent grant, and is compatible with Readium Kotlin Toolkit (BSD-3-Clause). To use GPLv3 or MIT instead, replace the LICENSE file content and the copyright header references before merging.

**Out of scope (covered by `2026-05-07-oss-readiness-nice-to-have.md`):** release signing, third-party-license screen, Renovate, docker-compose for self-hosting, GitHub Release automation, screenshots, Spotless/ktlint, F-Droid metadata.

---

## Task 1: Add LICENSE and NOTICE

**Files:**
- Create: `LICENSE`
- Create: `NOTICE`

- [ ] **Step 1: Fetch the Apache-2.0 license text**

```bash
curl -fsSL -o /Users/vito/repos/opds-ereader-android-app/LICENSE \
  https://www.apache.org/licenses/LICENSE-2.0.txt
```

Expected: file is exactly the Apache 2.0 license, ~11 KB, starts with `Apache License`.

- [ ] **Step 2: Verify the LICENSE file**

```bash
head -3 /Users/vito/repos/opds-ereader-android-app/LICENSE
wc -l /Users/vito/repos/opds-ereader-android-app/LICENSE
```

Expected: first line `Apache License`, ~202 lines.

- [ ] **Step 3: Write NOTICE**

Create `NOTICE` with this exact content:

```
Quire / opds-sync
Copyright 2026 Vito Fico and contributors

This product includes software developed by:
  - The Readium Foundation (Readium Kotlin Toolkit, BSD-3-Clause)
  - The OkHttp project (Apache-2.0)
  - The AndroidX project, Google LLC (Apache-2.0)
  - The Jetpack Compose project, Google LLC (Apache-2.0)
  - The FastAPI project (MIT)
  - The SQLAlchemy project (MIT)
  - The Pydantic project (MIT)
  - calibre-web (GPL-3.0) — used as an external service, not redistributed.

A complete attribution list is generated at build time and shown in
the application under Settings → About → Open-source licenses
(see the nice-to-have plan for the in-app screen).
```

- [ ] **Step 4: Commit**

```bash
cd /Users/vito/repos/opds-ereader-android-app
git add LICENSE NOTICE
git commit -m ":page_facing_up: chore: add Apache-2.0 LICENSE and NOTICE"
```

---

## Task 2: Rewrite the Authentication section in architecture.md

**Files:**
- Modify: `docs/architecture.md` (Authentication section, lines ~170–210; decision log row #6; user_id comment line ~133)

- [ ] **Step 1: Replace the Authentication section**

Open `docs/architecture.md` and replace the entire `## Authentication` section (currently describing OPDS Basic + Authentik OIDC + PKCE) with:

```markdown
## Authentication

One credential, one mental model. The user gives the Android app their
calibre-web username and password; everything else flows from that.

### calibre-web (OPDS) — HTTP Basic

- A dedicated `android-reader` user, not the admin account.
- Username + password stored in **Android Keystore** (hardware-backed where
  available) by `:auth` (`CalibreCredentialStore`).
- Every OPDS request sends `Authorization: Basic ...`.

### opds-sync — Basic auth proxied to calibre-web

- The Android app sends the **same** Basic header it uses for OPDS.
- The server has no user database. On each request it forwards the header
  to calibre-web's `/opds` endpoint and treats `200` as authenticated,
  `401` as not.
- Results are TTL-cached: 60 s positive, 10 s negative, LRU-bounded to
  1024 entries (configurable via `OPDS_SYNC_AUTH_CACHE_*`).
- `user_id` on every persisted row is the lowercased calibre-web username
  (extracted from the decoded Basic header). **Multi-user from day one.**
- Reference: `server/opds_sync/core/auth.py` (`CalibreAuthValidator`).

### Why this shape

- No external IdP to deploy, configure, or maintain.
- The user already has calibre-web credentials; nothing new to manage.
- The sync server is stateless w.r.t. identity — no password storage,
  no session state, no token rotation.
- Failure mode: if calibre-web is unreachable, the sync server returns
  `503` on auth-required endpoints. Documented and accepted.

### Token / credential handling on Android

- Basic credentials live in Keystore; never on disk in plaintext.
- On `401` the app prompts re-auth (no refresh token to rotate).
- Logout clears the Keystore entry.
```

- [ ] **Step 2: Update the user_id comment in the annotations table**

Find this line in the `Annotations (Phase 3+...)` section:

```
user_id         text          -- Authentik 'sub' claim
```

Replace with:

```
user_id         text          -- Lowercased calibre-web username
```

- [ ] **Step 3: Update Decision log row #6**

Find this row in the `## Decision log` table:

```
| 6 | Split auth (OPDS Basic, sync OIDC + PKCE) | Each protocol gets the auth that fits its nature. |
```

Replace with:

```
| 6 | One credential, sync server proxies Basic auth to calibre-web | No second IdP to deploy; no token state on the server; the user already has the credential. Replaced an earlier OIDC/Authentik design (Phase 2.1). |
```

- [ ] **Step 4: Commit**

```bash
cd /Users/vito/repos/opds-ereader-android-app
git add docs/architecture.md
git commit -m ":memo: docs: rewrite auth section to match CWA-proxy model"
```

---

## Task 3: Rewrite the Authentication section in sync-api.md

**Files:**
- Modify: `docs/sync-api.md` (Authentication section, lines 1–25)

- [ ] **Step 1: Replace the auth header description**

Replace the `## Authentication` section in `docs/sync-api.md` with:

```markdown
## Authentication

```
Authorization: Basic <base64(username:password)>
```

The same calibre-web Basic credentials the Android app uses for OPDS
browsing. The server validates each header by probing
`{OPDS_SYNC_CWA_BASE_URL}{OPDS_SYNC_CWA_PROBE_PATH}` (default `/opds`)
with the incoming `Authorization` header and treats `200` as
authenticated, `401` as not. Results are TTL-cached (60 s positive,
10 s negative).

The `user_id` recorded on every persisted row is the lowercased
calibre-web username extracted from the Basic header. The system is
multi-user from day one.

A failed lookup returns `401`. If calibre-web is unreachable the server
returns `503`.
```

(Keep everything from `## Endpoints` onwards unchanged.)

- [ ] **Step 2: Update the bearer-token references**

Search the file for `Bearer ...` examples and replace each with `Basic ...`:

```bash
cd /Users/vito/repos/opds-ereader-android-app
grep -n "Bearer" docs/sync-api.md
```

For each line that looks like `Authorization: Bearer ...`, change it to `Authorization: Basic ...` (matching the surrounding indentation). There should be three: under `POST /sync/v1/progress`, `GET /sync/v1/progress`, and `POST /sync/v1/documents/alias`.

- [ ] **Step 3: Update the 401 error description**

In the `## Errors` table, change:

```
| 401 | Missing or invalid JWT. |
```

to:

```
| 401 | Missing or invalid Basic credentials, or calibre-web rejected them. |
```

Add a row for `503`:

```
| 503 | Database unavailable (`/readyz`) or calibre-web unreachable for auth probes. |
```

(Replace any existing 503 row that only mentions `/readyz`.)

- [ ] **Step 4: Verify no Authentik or JWT references remain**

```bash
cd /Users/vito/repos/opds-ereader-android-app
grep -in "authentik\|JWT\|JWKS\|bearer\|OIDC\|PKCE" docs/sync-api.md || echo "clean"
```

Expected: `clean` (no matches).

- [ ] **Step 5: Commit**

```bash
cd /Users/vito/repos/opds-ereader-android-app
git add docs/sync-api.md
git commit -m ":memo: docs: sync-api auth is calibre-web Basic proxy, not JWT"
```

---

## Task 4: Fix development.md (env vars, module description, local.properties)

**Files:**
- Modify: `docs/development.md` (env-vars block lines ~117–121; module layout for `:auth`; local.properties block ~38–53)

- [ ] **Step 1: Replace the server env-vars example**

Find this block in `docs/development.md` (around line 114–121):

```
Listens on `http://localhost:8000`. Configuration is via environment variables;
see `opds_sync/config.py` for the full list. At minimum:

```
OPDS_SYNC_DATABASE_URL=postgresql+asyncpg://localhost/opds_sync
OPDS_SYNC_AUTHENTIK_ISSUER=https://auth.example.com/application/o/opds-sync/
OPDS_SYNC_AUTHENTIK_AUDIENCE=opds-sync
```

Replace with:

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

- [ ] **Step 2: Update the `:auth` module description**

Find this line in the `### Module layout` block:

```
:auth          AppAuth + Keystore credential store
```

Replace with:

```
:auth          Keystore-backed calibre-web Basic credential store
```

- [ ] **Step 3: Verify local.properties calibreweb fields are not consumed by Gradle**

```bash
cd /Users/vito/repos/opds-ereader-android-app
grep -rn "calibreweb" --include="*.kts" --include="*.kt" || echo "no consumers"
```

Expected: `no consumers`.

- [ ] **Step 4: Strip the unused calibreweb.* fields from local.properties.template**

Replace the contents of `local.properties.template` with:

```
# Copy to local.properties and fill in. Not committed.
# Only needed when building via the host ./gradlew (not scripts/dgradle).
sdk.dir=/Users/<you>/Library/Android/sdk
```

- [ ] **Step 5: Update the "Configuring the app" section in development.md**

Replace the `### Configuring the app` block (around lines 38–53) with:

```markdown
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
```

- [ ] **Step 6: Commit**

```bash
cd /Users/vito/repos/opds-ereader-android-app
git add docs/development.md local.properties.template
git commit -m ":memo: docs: drop stale Authentik env vars and BuildConfig story"
```

---

## Task 5: Rewrite README.md

**Files:**
- Modify: `README.md` (entire file)

- [ ] **Step 1: Replace README.md with the rewritten version**

Replace the entire contents of `README.md` with:

````markdown
# Quire

[![android-ci](https://github.com/vitofico/opds-ereader-android-app/actions/workflows/android-ci.yaml/badge.svg)](https://github.com/vitofico/opds-ereader-android-app/actions/workflows/android-ci.yaml)
[![server-ci](https://github.com/vitofico/opds-ereader-android-app/actions/workflows/server-ci.yaml/badge.svg)](https://github.com/vitofico/opds-ereader-android-app/actions/workflows/server-ci.yaml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

A self-hosted reading stack for people who already run [calibre-web]:

- **Quire** — native Android EPUB reader (Kotlin / Compose / Readium).
- **opds-sync** — small FastAPI service that stores reading progress and
  (later) annotations in Postgres.

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

| Phase | Scope | Status |
|---|---|---|
| 1 | Local reader: OPDS browse + download, EPUB rendering, local progress | shipped |
| 2 | Progress sync server + Android sync client | shipped |
| 2.1 | Sync server uses calibre-web Basic auth (no separate IdP) | shipped |
| 3 | Highlights sync | not started |
| 4 | Notes & bookmarks | not started |
| 5 | PDF support | deferred |
| 6 | Calibre plugin (read-only consumer) | not started |

This is pre-1.0 software built for the author's personal eink device. It
works, it's tested, but the API and DB schema may still change. Pin a
commit if you depend on it.

## Repo layout

```
app/                  Android entry point — Compose UI, navigation, DI wiring
auth/                 Keystore-backed calibre-web Basic credential store
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
````

- [ ] **Step 2: Verify no Authentik / AppAuth / OIDC references remain**

```bash
cd /Users/vito/repos/opds-ereader-android-app
grep -in "authentik\|appauth\|OIDC\|PKCE\|JWT" README.md || echo "clean"
```

Expected: `clean`.

- [ ] **Step 3: Commit**

```bash
cd /Users/vito/repos/opds-ereader-android-app
git add README.md
git commit -m ":memo: docs: rewrite README for OSS audience"
```

---

## Task 6: Add CONTRIBUTING.md

**Files:**
- Create: `CONTRIBUTING.md`

- [ ] **Step 1: Write CONTRIBUTING.md**

Create with:

````markdown
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
````

- [ ] **Step 2: Commit**

```bash
cd /Users/vito/repos/opds-ereader-android-app
git add CONTRIBUTING.md
git commit -m ":memo: docs: add CONTRIBUTING guide"
```

---

## Task 7: Add SECURITY.md

**Files:**
- Create: `SECURITY.md`

- [ ] **Step 1: Write SECURITY.md**

Create with:

````markdown
# Security Policy

## Supported versions

This project is pre-1.0. Only the latest commit on `main` is supported.
Older releases get no backports.

## Reporting a vulnerability

Please **do not** open a public GitHub issue for security problems.

Email **vito.fico@hivepower.tech** with:

- A description of the issue.
- Steps to reproduce, including a minimal proof of concept if possible.
- Affected components (Android app, sync server, both).
- Your assessment of impact (data exposure, auth bypass, RCE, etc.).

You'll get an acknowledgement within **5 business days**. If the report
is accepted, expect a fix or mitigation within **30 days** for high-
severity issues, longer for lower-severity ones.

## Scope

In scope:

- Android app (`app/`, `auth/`, `core/`, `data/`, `reader/`).
- Sync server (`server/`).
- The HTTP surface between them and calibre-web.

Out of scope:

- Vulnerabilities in calibre-web itself — report those upstream at
  https://github.com/janeczku/calibre-web.
- Vulnerabilities in third-party dependencies — report those to the
  upstream project. We track them via Renovate / GitHub security
  advisories.
- Misconfigurations of self-hosted deployments.

## Disclosure

Coordinated disclosure: we'll credit reporters in the release notes
unless they prefer to stay anonymous. We do not currently offer a
bounty.
````

- [ ] **Step 2: Commit**

```bash
cd /Users/vito/repos/opds-ereader-android-app
git add SECURITY.md
git commit -m ":lock: docs: add SECURITY policy"
```

---

## Task 8: Add CODE_OF_CONDUCT.md

**Files:**
- Create: `CODE_OF_CONDUCT.md`

- [ ] **Step 1: Write CODE_OF_CONDUCT.md**

Use the Contributor Covenant 2.1 verbatim, with the contact line filled in:

```bash
cd /Users/vito/repos/opds-ereader-android-app
curl -fsSL -o CODE_OF_CONDUCT.md \
  https://raw.githubusercontent.com/EthicalSource/contributor_covenant/release/content/version/2/1/code_of_conduct.md
```

- [ ] **Step 2: Replace the contact placeholder**

Open `CODE_OF_CONDUCT.md` and replace `[INSERT CONTACT METHOD]` (or the analogous placeholder used by the upstream template) with:

```
vito.fico@hivepower.tech
```

Verify:

```bash
grep -n "vito.fico@hivepower.tech" /Users/vito/repos/opds-ereader-android-app/CODE_OF_CONDUCT.md
grep -ni "INSERT" /Users/vito/repos/opds-ereader-android-app/CODE_OF_CONDUCT.md || echo "no placeholders"
```

Expected: contact line found; "no placeholders" output.

- [ ] **Step 3: Commit**

```bash
cd /Users/vito/repos/opds-ereader-android-app
git add CODE_OF_CONDUCT.md
git commit -m ":memo: docs: adopt Contributor Covenant 2.1"
```

---

## Task 9: Add issue templates

**Files:**
- Create: `.github/ISSUE_TEMPLATE/bug_report.yml`
- Create: `.github/ISSUE_TEMPLATE/feature_request.yml`
- Create: `.github/ISSUE_TEMPLATE/config.yml`

- [ ] **Step 1: Create the bug report template**

Create `.github/ISSUE_TEMPLATE/bug_report.yml`:

```yaml
name: Bug report
description: Something is broken in the Android app or the sync server.
labels: ["bug"]
body:
  - type: markdown
    attributes:
      value: |
        Thanks for taking the time to report a bug. Please fill in as
        much as you can — vague reports often can't be acted on.
  - type: dropdown
    id: component
    attributes:
      label: Component
      options:
        - Android app
        - Sync server
        - Both / unsure
        - Documentation
    validations:
      required: true
  - type: input
    id: version
    attributes:
      label: Version / commit
      description: App version (Settings → About) or server git SHA.
      placeholder: 2026.05.07.42 or git sha abc1234
    validations:
      required: true
  - type: textarea
    id: what-happened
    attributes:
      label: What happened?
      description: What did you do, what did you expect, what did you see instead?
    validations:
      required: true
  - type: textarea
    id: reproduce
    attributes:
      label: Reproduction steps
      description: Numbered steps. The shorter the better.
      placeholder: |
        1. Open the catalog
        2. Tap a book
        3. ...
    validations:
      required: true
  - type: textarea
    id: logs
    attributes:
      label: Logs
      description: |
        Android: `adb logcat -d -s Quire:* AndroidRuntime:E`.
        Server: `kubectl logs` or `docker logs` output around the failure.
        Strip anything sensitive.
      render: shell
  - type: input
    id: device
    attributes:
      label: Device / OS (Android only)
      placeholder: Boox Note Air 3, Android 12
  - type: input
    id: calibre-web
    attributes:
      label: calibre-web version
      placeholder: 0.6.21
```

- [ ] **Step 2: Create the feature request template**

Create `.github/ISSUE_TEMPLATE/feature_request.yml`:

```yaml
name: Feature request
description: Suggest something this project should do.
labels: ["enhancement"]
body:
  - type: markdown
    attributes:
      value: |
        Quire's scope is deliberately narrow (see CONTRIBUTING.md).
        Requests that don't fit will be closed politely; please don't
        take it personally.
  - type: textarea
    id: problem
    attributes:
      label: What problem does this solve?
      description: Describe the user-facing problem, not the proposed solution.
    validations:
      required: true
  - type: textarea
    id: proposal
    attributes:
      label: Proposed solution
    validations:
      required: true
  - type: textarea
    id: alternatives
    attributes:
      label: Alternatives considered
  - type: dropdown
    id: scope
    attributes:
      label: Does this fit Quire's scope?
      description: |
        See CONTRIBUTING.md for what's in scope. If unsure, pick "Unsure".
      options:
        - Yes — self-hosted, no telemetry, calibre-web-centric
        - Unsure
        - Maybe a fork is better
    validations:
      required: true
```

- [ ] **Step 3: Create the chooser config**

Create `.github/ISSUE_TEMPLATE/config.yml`:

```yaml
blank_issues_enabled: false
contact_links:
  - name: Security vulnerability
    url: https://github.com/vitofico/opds-ereader-android-app/security/advisories/new
    about: Use a private security advisory, not a public issue.
  - name: calibre-web bug
    url: https://github.com/janeczku/calibre-web/issues
    about: Report bugs in calibre-web itself upstream.
```

- [ ] **Step 4: Commit**

```bash
cd /Users/vito/repos/opds-ereader-android-app
git add .github/ISSUE_TEMPLATE/
git commit -m ":memo: ci: add GitHub issue templates"
```

---

## Task 10: Add PR template

**Files:**
- Create: `.github/PULL_REQUEST_TEMPLATE.md`

- [ ] **Step 1: Write the PR template**

Create with:

```markdown
## Summary

<!-- 1–3 sentences on what this PR changes and why. -->

## Component(s)

- [ ] Android app
- [ ] Sync server
- [ ] Documentation
- [ ] CI / build

## Checklist

- [ ] Tests added or updated (or N/A — explain)
- [ ] Docs updated in the same PR (or N/A)
- [ ] If Android UI changed: screenshots attached
- [ ] If server schema changed: Alembic migration included
- [ ] If `core/identity` changed: matching change on the other side + fixtures updated
- [ ] No new third-party SDKs / analytics / telemetry
- [ ] Commit messages follow gitmoji + conventional commits

## Related issues

<!-- Closes #N, refs #M, etc. -->
```

- [ ] **Step 2: Commit**

```bash
cd /Users/vito/repos/opds-ereader-android-app
git add .github/PULL_REQUEST_TEMPLATE.md
git commit -m ":memo: ci: add pull request template"
```

---

## Task 11: Verify a fresh-clone build still works

**Files:** none (verification only).

- [ ] **Step 1: Confirm Gradle reads no calibreweb fields**

```bash
cd /Users/vito/repos/opds-ereader-android-app
grep -rn "calibreweb" --include="*.kts" --include="*.kt" || echo "no consumers"
```

Expected: `no consumers`.

- [ ] **Step 2: Confirm there's no leftover Authentik / AppAuth in code**

```bash
cd /Users/vito/repos/opds-ereader-android-app
grep -rni "authentik\|appauth\|net\.openid" --include="*.kt" --include="*.kts" --include="*.toml" --include="*.py" 2>/dev/null \
  | grep -v ".venv/" | grep -v ".git/" \
  || echo "clean"
```

Expected: `clean`.

- [ ] **Step 3: Sanity-check docs**

```bash
cd /Users/vito/repos/opds-ereader-android-app
grep -rni "authentik\|OIDC\|PKCE\|JWT\|JWKS" docs/ README.md || echo "clean"
```

Expected: `clean`.

- [ ] **Step 4: Build once via dgradle to confirm nothing regressed**

```bash
cd /Users/vito/repos/opds-ereader-android-app
scripts/dgradle :app:assembleDebug
```

Expected: BUILD SUCCESSFUL. (This task is the only one that exercises the toolchain; if Docker isn't available in this environment, skip and note it in the PR description.)

- [ ] **Step 5: Final commit (if any tweaks needed)**

If any of the previous steps surfaced a leftover, fix it and commit:

```bash
cd /Users/vito/repos/opds-ereader-android-app
git add -A
git commit -m ":memo: docs: clean up final stale references"
```

If everything is clean, skip the commit step.

---

## Self-review checklist (run before opening the PR)

- [ ] `LICENSE` is present and Apache-2.0 (or the user's chosen alternative).
- [ ] `NOTICE` lists the major third-party libraries.
- [ ] `README.md`, `docs/architecture.md`, `docs/sync-api.md`,
  `docs/development.md` contain zero references to Authentik, AppAuth,
  OIDC, PKCE, JWT, or JWKS.
- [ ] `:auth` module is described as a Keystore-backed Basic credential
  store everywhere it's mentioned.
- [ ] `local.properties.template` does not list calibreweb.* fields.
- [ ] Standard community files exist: `CONTRIBUTING.md`,
  `CODE_OF_CONDUCT.md`, `SECURITY.md`,
  `.github/PULL_REQUEST_TEMPLATE.md`,
  `.github/ISSUE_TEMPLATE/{bug_report,feature_request,config}.yml`.
- [ ] Branch builds locally (or on CI) without modifications to
  `local.properties`.
