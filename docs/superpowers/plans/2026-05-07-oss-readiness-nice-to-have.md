# OSS Readiness — Nice to Have

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move from "publishable source repo" to "credible self-hostable project that ships releases" — release-signing, automation, supply-chain hygiene, and the polish that converts curious clicks into adopters.

**Architecture:** Mostly build-config and CI changes, with one Android UI surface (the in-app open-source-licenses screen). No production runtime code changes.

**Tech Stack:** Gradle/Kotlin DSL, AboutLibraries plugin, GitHub Actions, Renovate, Docker Compose, fastlane (metadata only).

**Sequencing:** Tasks are roughly priority-ordered. Each is independent enough to land as its own PR.

**Prerequisite:** the must-have plan (`2026-05-07-oss-readiness-must-have.md`) is merged.

---

## Task 1: Release signing config (env-var-driven)

**Files:**
- Modify: `app/build.gradle.kts` (signingConfigs block, release buildType lines ~46–50)
- Create: `docs/release.md`
- Modify: `.github/workflows/android-ci.yaml` (add release job, secrets reference)

**Why:** Today `release` is signed with the debug key. That's unsafe to distribute and blocks Play / F-Droid / direct-download paths. We want a release config that uses a real keystore when secrets are present, and gracefully falls back to debug-signed for contributor builds.

- [ ] **Step 1: Add a signingConfigs block to `app/build.gradle.kts`**

Insert this inside `android { ... }`, after `kotlinOptions { jvmTarget = "17" }` and before `buildFeatures`:

```kotlin
signingConfigs {
    create("release") {
        val storePath = System.getenv("QUIRE_RELEASE_KEYSTORE")
        if (!storePath.isNullOrBlank()) {
            storeFile = file(storePath)
            storePassword = System.getenv("QUIRE_RELEASE_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("QUIRE_RELEASE_KEY_ALIAS")
            keyPassword = System.getenv("QUIRE_RELEASE_KEY_PASSWORD")
        }
    }
}
```

- [ ] **Step 2: Wire the release buildType to use it conditionally**

Replace the existing `release { ... }` block with:

```kotlin
release {
    isMinifyEnabled = false
    signingConfig =
        if (System.getenv("QUIRE_RELEASE_KEYSTORE").isNullOrBlank())
            signingConfigs.getByName("debug")
        else
            signingConfigs.getByName("release")
}
```

- [ ] **Step 3: Build once locally to confirm no regression**

```bash
cd /Users/vito/repos/opds-ereader-android-app
scripts/dgradle :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Add a release job to android-ci.yaml**

Append this job after `build:` in `.github/workflows/android-ci.yaml`:

```yaml
  release:
    needs: build
    runs-on: ubuntu-latest
    if: startsWith(github.ref, 'refs/tags/v')
    permissions:
      contents: write
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"
      - uses: android-actions/setup-android@v3
        with:
          packages: "platform-tools platforms;android-34 build-tools;34.0.0"
      - uses: gradle/actions/setup-gradle@v4
      - name: Decode keystore
        run: echo "$KEYSTORE_B64" | base64 -d > "$RUNNER_TEMP/release.keystore"
        env:
          KEYSTORE_B64: ${{ secrets.QUIRE_RELEASE_KEYSTORE_B64 }}
      - name: Assemble release APK
        env:
          QUIRE_RELEASE_KEYSTORE: ${{ runner.temp }}/release.keystore
          QUIRE_RELEASE_KEYSTORE_PASSWORD: ${{ secrets.QUIRE_RELEASE_KEYSTORE_PASSWORD }}
          QUIRE_RELEASE_KEY_ALIAS: ${{ secrets.QUIRE_RELEASE_KEY_ALIAS }}
          QUIRE_RELEASE_KEY_PASSWORD: ${{ secrets.QUIRE_RELEASE_KEY_PASSWORD }}
        run: ./gradlew :app:assembleRelease --stacktrace
      - name: Create GitHub Release
        uses: softprops/action-gh-release@v2
        with:
          files: app/build/outputs/apk/release/*.apk
          generate_release_notes: true
```

- [ ] **Step 5: Document the keystore generation flow**

Create `docs/release.md`:

```markdown
# Release process

Tag-driven. Push a `vYYYY.MM.DD.<run>` tag (the `build` job creates these
automatically on `main`) and the `release` job in `android-ci.yaml`
builds and signs a release APK and attaches it to a GitHub Release.

## One-time keystore setup

```sh
keytool -genkey -v \
  -keystore quire-release.keystore \
  -alias quire \
  -keyalg RSA -keysize 4096 -validity 10000
```

Keep `quire-release.keystore` somewhere you'll never lose it — Android
ties update integrity to the signing key. Losing it means users on the
old key can never upgrade.

## GitHub secrets

Add to repo settings → Secrets and variables → Actions:

- `QUIRE_RELEASE_KEYSTORE_B64` — `base64 < quire-release.keystore`.
- `QUIRE_RELEASE_KEYSTORE_PASSWORD`
- `QUIRE_RELEASE_KEY_ALIAS` — `quire` (or whatever `-alias` you used).
- `QUIRE_RELEASE_KEY_PASSWORD`

## Cutting a release

The `build` job already pushes `vYYYY.MM.DD.<run>` tags on every push
to `main`. The `release` job fires on those tags and produces a signed
APK + GitHub Release. Nothing manual needed.

If you want to cut an out-of-band release, push a tag manually:

```sh
git tag v2026.05.07.0
git push origin v2026.05.07.0
```
```

- [ ] **Step 6: Commit**

```bash
cd /Users/vito/repos/opds-ereader-android-app
git add app/build.gradle.kts .github/workflows/android-ci.yaml docs/release.md
git commit -m ":construction_worker: ci: signed release APK on tag"
```

---

## Task 2: In-app open-source-licenses screen

**Files:**
- Modify: `gradle/libs.versions.toml` (add aboutlibraries plugin + lib aliases)
- Modify: `build.gradle.kts` (declare plugin)
- Modify: `app/build.gradle.kts` (apply plugin, add dependency, configure)
- Modify: `app/src/main/java/io/theficos/ereader/ui/settings/SettingsScreen.kt` (add About entry)
- Create: `app/src/main/java/io/theficos/ereader/ui/settings/LicensesScreen.kt`
- Modify: navigation graph (add `licenses` destination)

**Why:** Apache-2.0 doesn't legally require attribution screens, but Readium (BSD-3) and many other deps do. AboutLibraries generates the data at build time from the resolved Gradle graph, so it stays accurate as deps change.

- [ ] **Step 1: Add the version-catalog entries**

In `gradle/libs.versions.toml`, add to `[versions]`:

```
aboutlibraries = "11.2.3"
```

Add to `[libraries]`:

```
aboutlibraries-compose = { module = "com.mikepenz:aboutlibraries-compose-m3", version.ref = "aboutlibraries" }
```

Add to `[plugins]`:

```
aboutlibraries = { id = "com.mikepenz.aboutlibraries.plugin", version.ref = "aboutlibraries" }
```

- [ ] **Step 2: Declare the plugin in the root build script**

In `build.gradle.kts` add to the `plugins {}` block:

```kotlin
alias(libs.plugins.aboutlibraries) apply false
```

- [ ] **Step 3: Apply the plugin and dep in `:app`**

In `app/build.gradle.kts`, add to the `plugins {}` block:

```kotlin
alias(libs.plugins.aboutlibraries)
```

Add to `dependencies {}`:

```kotlin
implementation(libs.aboutlibraries.compose)
```

- [ ] **Step 4: Build once to verify the plugin generates data**

```bash
cd /Users/vito/repos/opds-ereader-android-app
scripts/dgradle :app:assembleDebug
```

Expected: BUILD SUCCESSFUL. The plugin will have generated
`app/build/generated/aboutlibraries/...`.

- [ ] **Step 5: Add the LicensesScreen composable**

Create `app/src/main/java/io/theficos/ereader/ui/settings/LicensesScreen.kt`:

```kotlin
package io.theficos.ereader.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Open-source licenses") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LibrariesContainer(modifier = Modifier.padding(padding))
    }
}
```

- [ ] **Step 6: Add a navigation destination and a Settings entry**

This step depends on the existing nav setup. Find the NavHost (likely in `app/src/main/java/io/theficos/ereader/ui/.../Navigation.kt` or similar — search if unsure):

```bash
cd /Users/vito/repos/opds-ereader-android-app
grep -rn "NavHost\|composable(" app/src/main/java | head
```

Add a `composable("licenses") { LicensesScreen(onBack = { navController.popBackStack() }) }` entry to the NavHost.

In `SettingsScreen.kt` add a list item:

```kotlin
ListItem(
    headlineContent = { Text("Open-source licenses") },
    modifier = Modifier.clickable { onNavigateToLicenses() },
)
```

…and thread `onNavigateToLicenses` through the screen's parameter list and the nav callsite.

- [ ] **Step 7: Build and smoke-test on a device or emulator**

```bash
cd /Users/vito/repos/opds-ereader-android-app
scripts/dgradle :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open the app → Settings → Open-source licenses → confirm Readium, OkHttp, AndroidX, Compose, Coil all appear.

- [ ] **Step 8: Commit**

```bash
cd /Users/vito/repos/opds-ereader-android-app
git add gradle/libs.versions.toml build.gradle.kts app/build.gradle.kts app/src/main/java/io/theficos/ereader/ui/settings/
git commit -m ":sparkles: feat: in-app open-source-licenses screen"
```

---

## Task 3: Renovate config

**Files:**
- Create: `renovate.json`

**Why:** Multi-language repo (Gradle + Python + GitHub Actions + Docker base images). Renovate handles all four with one config; Dependabot needs four.

- [ ] **Step 1: Write `renovate.json`**

```json
{
  "$schema": "https://docs.renovatebot.com/renovate-schema.json",
  "extends": [
    "config:recommended",
    ":semanticCommitsDisabled",
    ":dependencyDashboard",
    "schedule:weekly"
  ],
  "labels": ["dependencies"],
  "prConcurrentLimit": 5,
  "prHourlyLimit": 0,
  "rangeStrategy": "bump",
  "packageRules": [
    {
      "matchManagers": ["github-actions"],
      "groupName": "github-actions",
      "pinDigests": true
    },
    {
      "matchManagers": ["dockerfile"],
      "pinDigests": true
    },
    {
      "matchPackageNames": ["org.jetbrains.kotlin:kotlin-gradle-plugin"],
      "groupName": "kotlin"
    },
    {
      "matchPackagePrefixes": ["androidx."],
      "groupName": "androidx",
      "schedule": ["before 9am on monday"]
    },
    {
      "matchPackagePrefixes": ["org.readium."],
      "groupName": "readium"
    }
  ],
  "vulnerabilityAlerts": {
    "labels": ["security"],
    "schedule": []
  }
}
```

- [ ] **Step 2: Commit**

```bash
cd /Users/vito/repos/opds-ereader-android-app
git add renovate.json
git commit -m ":construction_worker: ci: enable Renovate"
```

(After merge, install the Renovate GitHub App on the repo: https://github.com/apps/renovate.)

---

## Task 4: docker-compose for self-hosters

**Files:**
- Create: `server/docker-compose.yml`
- Create: `server/.env.example`
- Modify: `server/README.md` (add docker-compose quickstart)

**Why:** Right now self-hosting requires assembling Postgres + opds-sync + a calibre-web reachable to both, by hand. A docker-compose file collapses that to one command.

- [ ] **Step 1: Write `server/docker-compose.yml`**

```yaml
services:
  postgres:
    image: postgres:16-alpine
    restart: unless-stopped
    environment:
      POSTGRES_USER: opds_sync
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-changeme}
      POSTGRES_DB: opds_sync
    volumes:
      - opds_sync_pg:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U opds_sync"]
      interval: 5s
      timeout: 5s
      retries: 10

  opds-sync:
    image: ghcr.io/vitofico/opds-sync:latest
    restart: unless-stopped
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      OPDS_SYNC_DATABASE_URL: postgresql+asyncpg://opds_sync:${POSTGRES_PASSWORD:-changeme}@postgres:5432/opds_sync
      OPDS_SYNC_CWA_BASE_URL: ${OPDS_SYNC_CWA_BASE_URL}
      OPDS_SYNC_LOG_LEVEL: ${OPDS_SYNC_LOG_LEVEL:-INFO}
    ports:
      - "${OPDS_SYNC_PORT:-8000}:8000"
    command: >
      sh -c "alembic upgrade head &&
             uvicorn opds_sync.main:app --host 0.0.0.0 --port 8000"

volumes:
  opds_sync_pg:
```

- [ ] **Step 2: Write `server/.env.example`**

```
# Copy to .env and fill in. Do not commit .env.
OPDS_SYNC_CWA_BASE_URL=https://library.example.com
POSTGRES_PASSWORD=change-me
OPDS_SYNC_PORT=8000
OPDS_SYNC_LOG_LEVEL=INFO
```

- [ ] **Step 3: Update `server/.gitignore`**

```bash
cd /Users/vito/repos/opds-ereader-android-app/server
grep -q "^\.env$" .gitignore || echo ".env" >> .gitignore
```

- [ ] **Step 4: Update `server/README.md`**

Append:

```markdown

## Self-hosting via docker-compose

```sh
cd server
cp .env.example .env
# Edit .env: at minimum set OPDS_SYNC_CWA_BASE_URL and POSTGRES_PASSWORD.
docker compose up -d
curl http://localhost:8000/healthz
```

Migrations run automatically on container start. The image is published
to `ghcr.io/vitofico/opds-sync:latest` by `server-ci.yaml`.
```

- [ ] **Step 5: Verify the compose file is valid**

```bash
cd /Users/vito/repos/opds-ereader-android-app/server
docker compose config > /dev/null && echo "valid"
```

Expected: `valid`.

- [ ] **Step 6: Commit**

```bash
cd /Users/vito/repos/opds-ereader-android-app
git add server/docker-compose.yml server/.env.example server/.gitignore server/README.md
git commit -m ":sparkles: feat(server): docker-compose for self-hosting"
```

---

## Task 5: Spotless + ktlint for Android

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts`
- Modify: `.github/workflows/android-ci.yaml` (add spotless check step)
- Create: `.editorconfig`

- [ ] **Step 1: Add Spotless plugin alias**

In `gradle/libs.versions.toml`:

`[versions]` →

```
spotless = "6.25.0"
```

`[plugins]` →

```
spotless = { id = "com.diffplug.spotless", version.ref = "spotless" }
```

- [ ] **Step 2: Apply Spotless to all subprojects**

Add to root `build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.spotless)
    // … existing aliases unchanged …
}

subprojects {
    apply(plugin = "com.diffplug.spotless")
    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            ktlint("1.3.1")
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint("1.3.1")
        }
    }
}
```

- [ ] **Step 3: Add `.editorconfig`**

```
root = true

[*]
end_of_line = lf
insert_final_newline = true
charset = utf-8
indent_style = space
indent_size = 4
trim_trailing_whitespace = true

[*.{kt,kts}]
ktlint_standard_no-wildcard-imports = enabled
ktlint_standard_filename = enabled

[*.{yml,yaml,json}]
indent_size = 2

[Makefile]
indent_style = tab
```

- [ ] **Step 4: Apply formatting once and commit the diff**

```bash
cd /Users/vito/repos/opds-ereader-android-app
scripts/dgradle spotlessApply
git add -A
git commit -m ":art: chore: apply spotless formatting"
```

- [ ] **Step 5: Add CI check**

In `.github/workflows/android-ci.yaml`, after `Lint`:

```yaml
      - name: Spotless check
        run: ./gradlew spotlessCheck --stacktrace
```

- [ ] **Step 6: Commit the CI step**

```bash
cd /Users/vito/repos/opds-ereader-android-app
git add gradle/libs.versions.toml build.gradle.kts .editorconfig .github/workflows/android-ci.yaml
git commit -m ":construction_worker: ci: enforce spotless on PRs"
```

---

## Task 6: CodeQL for Kotlin and Python

**Files:**
- Create: `.github/workflows/codeql.yaml`

- [ ] **Step 1: Write the workflow**

```yaml
name: codeql
on:
  push:
    branches: [main]
  pull_request:
    branches: [main]
  schedule:
    - cron: "0 6 * * 1"

jobs:
  analyze:
    runs-on: ubuntu-latest
    permissions:
      security-events: write
      actions: read
      contents: read
    strategy:
      fail-fast: false
      matrix:
        language: [java-kotlin, python]
    steps:
      - uses: actions/checkout@v4
      - name: Initialize CodeQL
        uses: github/codeql-action/init@v3
        with:
          languages: ${{ matrix.language }}
      - if: matrix.language == 'java-kotlin'
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"
      - if: matrix.language == 'java-kotlin'
        run: ./gradlew assembleDebug -x test --stacktrace
      - if: matrix.language == 'python'
        uses: github/codeql-action/autobuild@v3
      - uses: github/codeql-action/analyze@v3
```

- [ ] **Step 2: Commit**

```bash
cd /Users/vito/repos/opds-ereader-android-app
git add .github/workflows/codeql.yaml
git commit -m ":lock: ci: enable CodeQL for Kotlin and Python"
```

---

## Task 7: Trivy scan + SBOM for the server image

**Files:**
- Modify: `.github/workflows/server-ci.yaml`

- [ ] **Step 1: Add SBOM and provenance to the existing buildx step**

Replace the `docker/build-push-action@v6` step with:

```yaml
      - uses: docker/build-push-action@v6
        with:
          context: server
          push: true
          sbom: true
          provenance: mode=max
          tags: |
            ghcr.io/${{ github.repository_owner }}/opds-sync:${{ github.sha }}
            ghcr.io/${{ github.repository_owner }}/opds-sync:latest
```

- [ ] **Step 2: Add a Trivy scan job**

Append after the `image:` job:

```yaml
  scan:
    needs: image
    runs-on: ubuntu-latest
    if: github.ref == 'refs/heads/main'
    permissions:
      contents: read
      security-events: write
    steps:
      - uses: aquasecurity/trivy-action@0.24.0
        with:
          image-ref: ghcr.io/${{ github.repository_owner }}/opds-sync:${{ github.sha }}
          format: sarif
          output: trivy.sarif
          severity: HIGH,CRITICAL
          ignore-unfixed: true
      - uses: github/codeql-action/upload-sarif@v3
        with:
          sarif_file: trivy.sarif
```

- [ ] **Step 3: Commit**

```bash
cd /Users/vito/repos/opds-ereader-android-app
git add .github/workflows/server-ci.yaml
git commit -m ":lock: ci: SBOM, provenance, and Trivy scan for server image"
```

---

## Task 8: Pin GitHub Actions to commit SHAs

**Files:**
- Modify: `.github/workflows/android-ci.yaml`
- Modify: `.github/workflows/server-ci.yaml`
- Modify: `.github/workflows/codeql.yaml` (if Task 6 landed)

**Why:** A compromised tag of a popular action can run arbitrary code in the workflow's secret context. Pin to SHAs; let Renovate update them.

- [ ] **Step 1: Find the current SHA for each action**

Use `gh` to resolve each:

```bash
gh api repos/actions/checkout/git/refs/tags/v4 --jq .object.sha
gh api repos/actions/setup-java/git/refs/tags/v4 --jq .object.sha
gh api repos/android-actions/setup-android/git/refs/tags/v3 --jq .object.sha
gh api repos/gradle/actions/git/refs/heads/main --jq .object.sha   # use a release tag instead in practice
gh api repos/actions/upload-artifact/git/refs/tags/v4 --jq .object.sha
gh api repos/astral-sh/setup-uv/git/refs/tags/v3 --jq .object.sha
gh api repos/docker/setup-buildx-action/git/refs/tags/v3 --jq .object.sha
gh api repos/docker/login-action/git/refs/tags/v3 --jq .object.sha
gh api repos/docker/build-push-action/git/refs/tags/v6 --jq .object.sha
gh api repos/softprops/action-gh-release/git/refs/tags/v2 --jq .object.sha
```

- [ ] **Step 2: Replace each `uses: org/action@vN` with `uses: org/action@<sha> # vN`**

The trailing comment lets Renovate (with `pinDigests: true`, see Task 3) keep them current.

- [ ] **Step 3: Re-run CI to confirm nothing broke**

Push a no-op commit or wait for the next PR.

- [ ] **Step 4: Commit**

```bash
cd /Users/vito/repos/opds-ereader-android-app
git add .github/workflows/
git commit -m ":lock: ci: pin actions to commit SHAs"
```

---

## Task 9: F-Droid metadata

**Files:**
- Create: `fastlane/metadata/android/en-US/title.txt`
- Create: `fastlane/metadata/android/en-US/short_description.txt`
- Create: `fastlane/metadata/android/en-US/full_description.txt`
- Create: `fastlane/metadata/android/en-US/changelogs/` (one file per release)
- Create: `fastlane/metadata/android/en-US/images/phoneScreenshots/` (PNGs)
- Create: `fastlane/metadata/android/en-US/images/icon.png`

**Why:** F-Droid is the natural store for this audience. Their inclusion process needs metadata in this exact layout. F-Droid signs reproducible builds itself, so you don't ship a signed APK to them — you just give them the tag.

- [ ] **Step 1: Write `title.txt`**

```
Quire
```

- [ ] **Step 2: Write `short_description.txt`** (≤80 chars)

```
Self-hosted EPUB reader for calibre-web. No telemetry, no cloud, your data.
```

- [ ] **Step 3: Write `full_description.txt`**

```
Quire is a native Android EPUB reader for people who self-host
calibre-web.

It browses your library over OPDS, downloads books on demand, renders
them with Readium, and syncs reading progress (and later highlights
and bookmarks) to a small companion server you run yourself
(opds-sync, included).

Features:
- OPDS catalog browsing and search
- EPUB reading with Readium (font, theme, line-height controls)
- Reading progress sync across devices via your own Postgres
- Multi-user from day one
- One credential: your existing calibre-web account

Privacy:
- No analytics, no crash reporting, no third-party SDKs
- Network calls go to your calibre-web and your sync server only
- Credentials stored in Android Keystore

Source code, server, and documentation:
https://github.com/vitofico/opds-ereader-android-app
```

- [ ] **Step 4: Add an initial changelog file**

Create `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` with the release notes for the current build. The file name must match `versionCode` (the integer one).

- [ ] **Step 5: Add screenshots and icon**

Drop PNG screenshots (1080×1920 or 1080×2400 typical) into
`phoneScreenshots/` and `icon.png` (512×512) into `images/`.

- [ ] **Step 6: Commit**

```bash
cd /Users/vito/repos/opds-ereader-android-app
git add fastlane/
git commit -m ":sparkles: chore: F-Droid fastlane metadata"
```

(Submission to F-Droid then happens via a PR to https://gitlab.com/fdroid/fdroiddata.)

---

## Task 10: Polish the README with screenshots

**Files:**
- Modify: `README.md` (insert screenshot row after the intro)
- Add: `docs/images/screenshot-catalog.png`, `docs/images/screenshot-reader.png`, `docs/images/screenshot-settings.png`

- [ ] **Step 1: Take three screenshots from a real device**

Catalog browse, reader page, settings. PNG, ≤1 MB each.

- [ ] **Step 2: Add to `docs/images/`**

```bash
mkdir -p /Users/vito/repos/opds-ereader-android-app/docs/images
# copy the PNGs in
```

- [ ] **Step 3: Insert into README**

After the intro paragraph in `README.md`, add:

```markdown
<p align="center">
  <img src="docs/images/screenshot-catalog.png" width="240" alt="Catalog">
  <img src="docs/images/screenshot-reader.png"  width="240" alt="Reader">
  <img src="docs/images/screenshot-settings.png" width="240" alt="Settings">
</p>
```

- [ ] **Step 4: Commit**

```bash
cd /Users/vito/repos/opds-ereader-android-app
git add README.md docs/images/
git commit -m ":memo: docs: add screenshots to README"
```

---

## Task 11: GitHub Discussions + repo metadata

**Files:** none (GitHub UI configuration).

- [ ] **Step 1: Enable Discussions**

Repo Settings → General → Features → Discussions: on.

- [ ] **Step 2: Set repo description and topics**

Description: `Self-hosted EPUB reading stack: native Android reader (Quire) + FastAPI sync server (opds-sync), backed by calibre-web.`

Topics: `android`, `kotlin`, `compose`, `epub`, `ereader`, `opds`, `calibre-web`, `readium`, `fastapi`, `self-hosted`, `sync`.

- [ ] **Step 3: Set "Sponsor this project"** (optional)

If you take sponsorships, link a `.github/FUNDING.yml`. Otherwise skip.

- [ ] **Step 4: Branch protection on `main`**

Settings → Branches → add rule for `main`:
- Require pull request before merging
- Require status checks: `android-ci / build`, `server-ci / test`, `codeql / analyze (java-kotlin)`, `codeql / analyze (python)` (only those that exist).
- Require linear history.

(Nothing to commit; this is repo configuration.)

---

## Task 12: Macrobenchmark module (optional, lowest priority)

Skipped here — the nowinandroid setup for this is well-documented and a meaningful undertaking. Add a short stub task only if/when startup performance becomes a complaint:

**Files:**
- Create: `benchmarks/` Gradle module mirroring `androidx.benchmark.macro.junit4` setup
- Modify: `settings.gradle.kts` to include `:benchmarks`

(See https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview for the canonical setup; this task is intentionally a placeholder.)

---

## Self-review checklist

- [ ] Release builds locally without keystore env vars (falls back to debug-signed).
- [ ] `:app:assembleRelease` produces a properly-signed APK when env vars are set.
- [ ] In-app open-source-licenses screen lists at least Readium, OkHttp, AndroidX, Compose.
- [ ] `renovate.json` validates against the schema.
- [ ] `docker compose -f server/docker-compose.yml config` is valid.
- [ ] Spotless clean on a fresh checkout.
- [ ] CodeQL workflow runs on PRs without errors.
- [ ] All actions are pinned to commit SHAs.
- [ ] Repo Topics, Description, and Discussions configured on GitHub.
