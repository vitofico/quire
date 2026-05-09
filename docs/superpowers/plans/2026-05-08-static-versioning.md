# Static Versioning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the runtime `git describe`-based version derivation with a static `VERSION_NAME` / `VERSION_CODE` in `gradle.properties`, bumped + committed by CI on each push to `main`. Aligns with the standard Android-on-F-Droid pattern, unblocks `fdroid checkupdates` auto-update, and removes the build's git subprocess dependency.

**Architecture:** `gradle.properties` is the single source of truth for the app's version. `app/build.gradle.kts` reads `VERSION_NAME` and `VERSION_CODE` from project properties and assigns them directly. The `buildSrc/` Version parser, the `gitDescribe()` ProcessBuilder helper, and the `QUIRE_VERSION_FALLBACK` env var all disappear. CI on `main` bumps the two values to the next CalVer (`YYYY.MM.DD.<run>` / `yyMMdd*100 + run%100`), commits the change with `[bot]` author, tags `v$VERSION_NAME`, and pushes both before Gradle runs. PR / feature-branch builds just consume whatever values are in the file — they don't bump or tag. The F-Droid recipe regains `AutoUpdateMode: Version` + `UpdateCheckMode: Tags …` + `UpdateCheckData: gradle.properties|VERSION_CODE=(\d+)|.|VERSION_NAME=(.*)` and drops `VercodeOperation` (no longer needed).

**Tech Stack:** Kotlin DSL Gradle (`app/build.gradle.kts`), `gradle.properties` (Java properties format), GitHub Actions (`.github/workflows/android-ci.yaml`, `codeql.yaml`), `scripts/dgradle` (Docker-wrapped Gradle), F-Droid metadata YAML.

**Branch:** `chore/static-versioning` (created in Task 1).

**Build/test rule:** All Gradle invocations go through `scripts/dgradle` from the repo root. Never the host `./gradlew`.

---

## File Structure

In-repo (this branch):

| File | Status | Responsibility |
|---|---|---|
| `gradle.properties` | modify | Add `VERSION_NAME` and `VERSION_CODE` lines (initial values match the latest tag at branch time) |
| `app/build.gradle.kts` | modify | Drop `gitDescribe()`, drop `Version.fromGitDescribe()` call, read version from project properties |
| `buildSrc/build.gradle.kts` | delete | No longer needed |
| `buildSrc/src/main/kotlin/Version.kt` | delete | No longer needed |
| `buildSrc/src/test/kotlin/VersionTest.kt` | delete | No longer needed |
| `buildSrc/` (directory) | delete | Empty after the above; remove the directory |
| `.github/workflows/android-ci.yaml` | modify | Compute version step bumps `gradle.properties` + commits + tags on `main`; just reads on PR; release job drops `QUIRE_VERSION_FALLBACK` env |
| `.github/workflows/codeql.yaml` | modify | Drop `QUIRE_VERSION_FALLBACK` env (no longer needed); drop `fetch-tags: true` from checkout (still keep `fetch-depth: 0` since CodeQL benefits from full history for analysis) |
| `docs/release.md` | modify | Rewrite the version-derivation explanation to reflect the new static-properties model; drop `QUIRE_VERSION_FALLBACK` references |

Out-of-repo (after main merge produces a fresh tag):

| File | Status | Where | Responsibility |
|---|---|---|---|
| `metadata/io.theficos.quire.yml` | modify | fdroiddata fork (`add-quire` branch) | Restore `AutoUpdateMode: Version`, simplify to `UpdateCheckData: gradle.properties|VERSION_CODE=(\d+)|.|VERSION_NAME=(.*)`, drop `VercodeOperation`, bump to new commit SHA |

---

## Task 1: Create branch and seed `gradle.properties` with current version

**Files:**
- Modify: `gradle.properties`

- [ ] **Step 1: Create the branch**

```bash
git checkout main
git pull origin main
git checkout -b chore/static-versioning
```

- [ ] **Step 2: Find the current latest tag's version values**

```bash
LATEST_TAG=$(git tag -l 'v*' --sort=-v:refname | head -1)
echo "Latest tag: $LATEST_TAG"
```

Expected: `v2026.05.08.43` (or newer if more tags have been cut since this plan was written).

The `VERSION_NAME` is the tag without the `v` prefix (e.g., `2026.05.08.43`). The `VERSION_CODE` is computed by the formula `yyMMdd*100 + run%100` — for `2026.05.08.43` it's `26050843`. If the latest tag is different, recompute: take the last two digits of the year × 10000, plus month × 100, plus day, multiply by 100, add run number mod 100.

- [ ] **Step 3: Add the two properties to `gradle.properties`**

Read `gradle.properties`. Append (with one blank line before, after the existing content) two new lines:

```properties

# App version. Bumped by CI on every push to main; locally these are the
# values of the most recent release tag.
VERSION_NAME=2026.05.08.43
VERSION_CODE=26050843
```

(Substitute the actual latest tag's values from Step 2.)

- [ ] **Step 4: Verify file content**

Run: `cat gradle.properties`
Expected: original content unchanged, plus the new comment + two `VERSION_*` lines at the bottom.

- [ ] **Step 5: Commit**

```bash
git add gradle.properties
git commit -m ":construction: chore: seed gradle.properties with current version values"
```

---

## Task 2: Read version from project properties in `app/build.gradle.kts`

**Files:**
- Modify: `app/build.gradle.kts` (lines 1-26 of the current file — the `gitDescribe()` function and `versionInfo` declaration)

- [ ] **Step 1: Read the current top of `app/build.gradle.kts`**

Run: `sed -n '1,30p' app/build.gradle.kts`

Confirm the current content includes:
- The `plugins { ... }` block (lines 1-6)
- A `// Tag-driven CalVer ...` comment (lines 8-10)
- A `fun gitDescribe(): String = ...` function (lines 11-21)
- A `val versionInfo = Version.fromGitDescribe(...)` declaration (lines 23-26)

- [ ] **Step 2: Replace lines 1-26 with the static-properties version**

Open `app/build.gradle.kts`. Replace the entire content from line 1 through (and including) the closing `)` of the `versionInfo` declaration with:

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.aboutlibraries)
}

// Static version. Source of truth: gradle.properties (VERSION_NAME, VERSION_CODE).
// CI bumps these on every push to main; local devs see the most recent release.
val appVersionName: String = project.property("VERSION_NAME") as String
val appVersionCode: Int = (project.property("VERSION_CODE") as String).toInt()
```

The next line (currently blank) and everything after (the `aboutLibraries { ... }` block, `android { ... }` block, etc.) stays unchanged.

- [ ] **Step 3: Update the `defaultConfig` references**

Inside the `android { defaultConfig { ... } }` block, find:

```kotlin
versionCode = versionInfo.code
versionName = versionInfo.name
```

Replace with:

```kotlin
versionCode = appVersionCode
versionName = appVersionName
```

- [ ] **Step 4: Verify locally with dgradle**

Run: `scripts/dgradle :app:assembleDebug --stacktrace`
Expected: `BUILD SUCCESSFUL`. (`scripts/dgradle` builds inside Docker; first run after a Dockerfile change rebuilds the image.)

If you see `Could not get unknown property 'VERSION_NAME'`, the `gradle.properties` lines from Task 1 didn't land — verify with `grep VERSION_NAME gradle.properties`.

If you see `Unresolved reference: Version` or `Unresolved reference: gitDescribe`, leftover references to the old code remain in `app/build.gradle.kts` — `grep -n 'gitDescribe\|Version.fromGitDescribe\|versionInfo' app/build.gradle.kts` should return nothing.

- [ ] **Step 5: Confirm the APK reports the right version**

Run: `unzip -p app/build/outputs/apk/debug/app-debug.apk AndroidManifest.xml | strings | grep -E '2026\.05\.08'`
Expected: at least one line containing the `VERSION_NAME` value from `gradle.properties` (e.g., `2026.05.08.43`).

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts
git commit -m ":sparkles: feat: read app version from gradle.properties instead of git describe"
```

---

## Task 3: Delete `buildSrc/`

**Files:**
- Delete: `buildSrc/build.gradle.kts`
- Delete: `buildSrc/src/main/kotlin/Version.kt`
- Delete: `buildSrc/src/test/kotlin/VersionTest.kt`
- Delete: `buildSrc/` (the now-empty directory)

- [ ] **Step 1: Confirm nothing references the buildSrc API anywhere else**

Run: `grep -rn 'Version\.fromGitDescribe\|VersionInfo\|buildSrc' --include='*.kts' --include='*.kt' .`
Expected: no matches (or only matches in the plan/spec docs, which is fine).

If anything else in the codebase imports `Version` or `VersionInfo`, that file needs updating before this delete. The current codebase only uses these from `app/build.gradle.kts`, which Task 2 already cleaned up.

- [ ] **Step 2: Delete the buildSrc tree**

```bash
git rm -r buildSrc/
```

- [ ] **Step 3: Verify it's gone and Gradle still configures**

```bash
ls buildSrc/ 2>&1   # Expected: "ls: buildSrc/: No such file or directory"
scripts/dgradle help --quiet 2>&1 | tail -5
```

The `dgradle help` should print a Gradle help section without errors.

- [ ] **Step 4: Commit**

```bash
git commit -m ":fire: chore: remove buildSrc/ — no longer needed with static versioning"
```

---

## Task 4: Update CI workflow's `Compute version` step on main to bump + commit + tag

**Files:**
- Modify: `.github/workflows/android-ci.yaml` (the `build` job: add `permissions: contents: write`, replace the `Compute version` step)

- [ ] **Step 1: Add `permissions: contents: write` to the `build` job**

The build job will `git push` a bump commit + tag. The default workflow token has read-only permissions, so we need to opt the build job into write.

In `.github/workflows/android-ci.yaml`, find the build job header:

```yaml
  build:
    runs-on: ubuntu-latest
    outputs:
      version: ${{ steps.version.outputs.version }}
```

Replace with:

```yaml
  build:
    runs-on: ubuntu-latest
    permissions:
      contents: write   # to push the version-bump commit and tag
    outputs:
      version: ${{ steps.version.outputs.version }}
```

(The `release` job already has `permissions: contents: write` for its Release-creation step; we're matching that for the build job.)

- [ ] **Step 2: Replace the Compute version step**

In `.github/workflows/android-ci.yaml`, find the existing step:

```yaml
      - id: version
        name: Compute version
        run: |
          if [ "$GITHUB_REF" = "refs/heads/main" ]; then
            # On main: bump CalVer (date + CI run number) and tag the HEAD
            # locally so Gradle's git-describe finds an exact-match tag.
            # The release job pushes this tag when it creates the Release.
            BUILD_DATE=$(date -u +%Y-%m-%d)
            VERSION="${BUILD_DATE//-/.}.${GITHUB_RUN_NUMBER}"
            git tag "v$VERSION"
          else
            # On PRs / feature branches: derive from existing tag history.
            # Post-tag dev format is fine for non-release artifact names.
            if ! git describe --tags --match 'v*' --abbrev=0 >/dev/null 2>&1; then
              echo "::error::No matching v* tag in history. Build will fail."
              git tag -l 'v*' | head
              exit 1
            fi
            VERSION=$(git describe --tags --match 'v*' --always | sed 's/^v//')
          fi
          echo "VERSION_NAME=$VERSION" >> "$GITHUB_ENV"
          echo "version=$VERSION" >> "$GITHUB_OUTPUT"
          echo "Building version $VERSION"
```

Replace the entire step with:

```yaml
      - id: version
        name: Compute version
        env:
          # Use the GitHub-provided token to push the bump commit and tag.
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          if [ "$GITHUB_REF" = "refs/heads/main" ]; then
            # On main: compute the next CalVer from date + run number,
            # write it into gradle.properties, commit + tag, then push
            # both before Gradle runs. The build then reads the bumped
            # values directly from gradle.properties.
            BUILD_DATE=$(date -u +%Y-%m-%d)
            NEW_NAME="${BUILD_DATE//-/.}.${GITHUB_RUN_NUMBER}"
            YY=$(date -u +%y)
            MM=$(date -u +%m)
            DD=$(date -u +%d)
            NEW_CODE=$(( (10#$YY * 10000 + 10#$MM * 100 + 10#$DD) * 100 + GITHUB_RUN_NUMBER % 100 ))

            sed -i "s/^VERSION_NAME=.*/VERSION_NAME=$NEW_NAME/" gradle.properties
            sed -i "s/^VERSION_CODE=.*/VERSION_CODE=$NEW_CODE/" gradle.properties

            git config user.name "github-actions[bot]"
            git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
            git remote set-url origin "https://x-access-token:${GH_TOKEN}@github.com/${GITHUB_REPOSITORY}.git"
            git add gradle.properties
            git commit -m ":bookmark: chore: release v$NEW_NAME"
            git tag "v$NEW_NAME"
            git push origin "HEAD:$GITHUB_REF" "v$NEW_NAME"

            VERSION="$NEW_NAME"
          else
            # On PRs / feature branches: just read the static value from
            # gradle.properties — no bump, no tag, no commit.
            VERSION=$(grep '^VERSION_NAME=' gradle.properties | cut -d= -f2)
          fi
          echo "VERSION_NAME=$VERSION" >> "$GITHUB_ENV"
          echo "version=$VERSION" >> "$GITHUB_OUTPUT"
          echo "Building version $VERSION"
```

The diff vs the old step:

- Adds `env: GH_TOKEN` so the step can authenticate the `git push` back to the repo.
- The `main` branch path now bumps `gradle.properties`, commits + tags + pushes, instead of just creating a local tag.
- The non-main path now reads the value statically from `gradle.properties` — no `git describe` fallback is needed.

The build job's `outputs.version` (consumed by the release job) keeps the same shape — a `2026.05.08.NN` string without the `v` prefix.

- [ ] **Step 3: Verify YAML parses**

Run: `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/android-ci.yaml')); print('OK')"`
Expected: `OK`.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/android-ci.yaml
git commit -m ":construction_worker: ci: bump+tag gradle.properties on main, read static on PR"
```

---

## Task 5: Drop `QUIRE_VERSION_FALLBACK` from the release job

**Files:**
- Modify: `.github/workflows/android-ci.yaml` (the `Assemble release APK` step in the `release` job, currently around lines 120-130)

- [ ] **Step 1: Edit the env block**

In `.github/workflows/android-ci.yaml`, find the `Assemble release APK` step:

```yaml
      - name: Assemble release APK
        env:
          # The build job created the tag locally on its runner so Gradle's
          # git-describe found an exact match. Each release-job runner gets
          # a fresh checkout with no local tag, so feed the same CalVer in
          # via the parser's fallback path.
          QUIRE_VERSION_FALLBACK: ${{ needs.build.outputs.version }}
          QUIRE_RELEASE_KEYSTORE: ${{ secrets.QUIRE_RELEASE_KEYSTORE_B64 != '' && format('{0}/release.keystore', runner.temp) || '' }}
          QUIRE_RELEASE_KEYSTORE_PASSWORD: ${{ secrets.QUIRE_RELEASE_KEYSTORE_PASSWORD }}
          QUIRE_RELEASE_KEY_ALIAS: ${{ secrets.QUIRE_RELEASE_KEY_ALIAS }}
          QUIRE_RELEASE_KEY_PASSWORD: ${{ secrets.QUIRE_RELEASE_KEY_PASSWORD }}
        run: ./gradlew :app:assembleRelease --stacktrace
```

Replace it with:

```yaml
      - name: Assemble release APK
        env:
          QUIRE_RELEASE_KEYSTORE: ${{ secrets.QUIRE_RELEASE_KEYSTORE_B64 != '' && format('{0}/release.keystore', runner.temp) || '' }}
          QUIRE_RELEASE_KEYSTORE_PASSWORD: ${{ secrets.QUIRE_RELEASE_KEYSTORE_PASSWORD }}
          QUIRE_RELEASE_KEY_ALIAS: ${{ secrets.QUIRE_RELEASE_KEY_ALIAS }}
          QUIRE_RELEASE_KEY_PASSWORD: ${{ secrets.QUIRE_RELEASE_KEY_PASSWORD }}
        run: ./gradlew :app:assembleRelease --stacktrace
```

(Drop the comment block and the `QUIRE_VERSION_FALLBACK` env var — no longer needed since `gradle.properties` carries the version.)

- [ ] **Step 2: Verify the release job's checkout still has the bumped commit**

The `release` job has `needs: build`, so it runs *after* the build job pushes the bump. The release job's `actions/checkout@v4` step checks out `${{ github.sha }}` by default, which is the SHA of the *triggering commit* — that's the merge commit, not the bump commit the build job created. The bump commit is unreachable from `github.sha`.

To make the release job see the bumped `gradle.properties`, add `ref: ${{ needs.build.outputs.version }}` to the checkout — wait, that's the version string not a ref. The right ref is the tag the build job created.

Update the release job's checkout step to:

```yaml
      - uses: actions/checkout@34e114876b0b11c390a56381ad16ebd13914f8d5 # v4
        with:
          ref: v${{ needs.build.outputs.version }}
          fetch-depth: 0
          fetch-tags: true
```

The `ref:` field tells `actions/checkout` to fetch and check out the named tag instead of the default `${{ github.sha }}`. The build job pushed `v$VERSION_NAME` before this job started, so it's available remotely.

- [ ] **Step 3: Verify YAML parses**

Run: `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/android-ci.yaml')); print('OK')"`
Expected: `OK`.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/android-ci.yaml
git commit -m ":construction_worker: ci: drop QUIRE_VERSION_FALLBACK from release job, checkout the freshly-pushed tag"
```

---

## Task 6: Update the CodeQL workflow

**Files:**
- Modify: `.github/workflows/codeql.yaml` (the `if: matrix.language == 'java-kotlin'` run step, currently around lines 46-53)

- [ ] **Step 1: Replace the env-bearing run step**

In `.github/workflows/codeql.yaml`, find:

```yaml
      - if: matrix.language == 'java-kotlin'
        env:
          # CodeQL doesn't need a meaningful version — fall back to a fixed
          # placeholder so the version derivation in app/build.gradle.kts
          # succeeds even when no v* tag is reachable from this commit.
          QUIRE_VERSION_FALLBACK: 2026.05.08.0
        run: ./gradlew assembleDebug -x test --stacktrace
```

Replace with:

```yaml
      - if: matrix.language == 'java-kotlin'
        run: ./gradlew assembleDebug -x test --stacktrace
```

(The version is now read from the committed `gradle.properties` — no fallback env var needed.)

- [ ] **Step 2: Drop `fetch-tags: true` from the checkout (optional cleanup)**

Find the `actions/checkout@…` step:

```yaml
      - uses: actions/checkout@34e114876b0b11c390a56381ad16ebd13914f8d5 # v4
        with:
          fetch-depth: 0
          fetch-tags: true
```

Change to:

```yaml
      - uses: actions/checkout@34e114876b0b11c390a56381ad16ebd13914f8d5 # v4
        with:
          fetch-depth: 0
```

(`fetch-depth: 0` is still useful to give CodeQL full history for analysis context. `fetch-tags: true` was only there to feed `git describe`, which no longer runs.)

- [ ] **Step 3: Verify YAML parses**

Run: `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/codeql.yaml')); print('OK')"`
Expected: `OK`.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/codeql.yaml
git commit -m ":construction_worker: ci(codeql): drop QUIRE_VERSION_FALLBACK and fetch-tags"
```

---

## Task 7: Rewrite the version-derivation section of `docs/release.md`

**Files:**
- Modify: `docs/release.md`

- [ ] **Step 1: Read the current file**

Run: `cat docs/release.md`

The file documents the release process. Sections include "One-time keystore setup", "GitHub secrets", "Cutting a release", "Local release builds", and "Reproducibility check before submitting to F-Droid".

The sections that explain version derivation are inline in "Cutting a release" and "Local release builds". They mention `git describe` and `QUIRE_VERSION_FALLBACK`. We need to update those.

- [ ] **Step 2: Replace any reference to `QUIRE_VERSION_FALLBACK` and `git describe`-based version derivation**

For each occurrence, update to describe the static `gradle.properties` model. Specifically:

In **"Cutting a release"** section, replace any text that says the workflow derives the version from `git describe` with text that describes the new flow: the workflow bumps `gradle.properties`, commits with a `[bot]` author, tags `v$VERSION`, pushes, then builds. So pushing to `main` is what triggers a release; no manual tagging needed.

In **"Local release builds"** section, drop any mention of `QUIRE_VERSION_FALLBACK`. Local release builds simply use whatever `VERSION_NAME` / `VERSION_CODE` are committed to `gradle.properties` at HEAD.

In the **F-Droid reproducibility section** (added in a previous task), update the YAML example that shows `QUIRE_VERSION_FALLBACK` in `Builds:` `env:` — that example is no longer needed since the recipe will use `UpdateCheckData` instead. Either delete that paragraph entirely or replace with a paragraph noting that fdroidserver reads `VERSION_CODE` / `VERSION_NAME` directly from `gradle.properties` via the recipe's `UpdateCheckData` field.

Concrete edit (one paragraph) — find:

```markdown
If `git describe` returns nothing inside the fdroidserver VM (it does
a non-shallow clone, but on rare runs tags might not propagate),
set `QUIRE_VERSION_FALLBACK` in the recipe's `Builds:` block:

```yaml
Builds:
  - versionName: 2026.05.08.30
    versionCode: 26050830
    commit: v2026.05.08.30
    subdir: app
    gradle: [ yes ]
    env:
      QUIRE_VERSION_FALLBACK: 2026.05.08.30
```
```

Replace with:

```markdown
The version values come from `gradle.properties` (`VERSION_NAME` and
`VERSION_CODE`), which CI bumps on every push to `main` before the
build runs. fdroidserver reads them via the recipe's `UpdateCheckData`
line, so each tag's APK metadata is statically derivable from source
without running Gradle.
```

- [ ] **Step 3: Confirm no stale references remain**

Run: `grep -n 'QUIRE_VERSION_FALLBACK\|git describe\|gitDescribe' docs/release.md`
Expected: no matches.

- [ ] **Step 4: Commit**

```bash
git add docs/release.md
git commit -m ":memo: docs: rewrite release.md for static gradle.properties versioning"
```

---

## Task 8: Push branch and verify CI

**Files:** none (push + watch)

- [ ] **Step 1: Push the branch**

```bash
git push -u origin chore/static-versioning
```

- [ ] **Step 2: Watch the android-ci pipeline**

Run: `gh run list --branch chore/static-versioning --workflow android-ci.yaml --limit 1`
Then either wait or `gh run watch <id>`.

- [ ] **Step 3: Confirm the build succeeded with the static version**

Once `android-ci` completes:

```bash
gh run view --job=$(gh run list --branch chore/static-versioning --workflow android-ci.yaml --limit 1 --json databaseId -q '.[0].databaseId') --log 2>&1 | grep -E '^Building version|VERSION_NAME='
```

Expected: a line `Building version 2026.05.08.NN` matching the `VERSION_NAME` in `gradle.properties` at HEAD. No "Could not derive version" error.

- [ ] **Step 4: Confirm no `release` job runs**

Run: `gh run view <id> --json jobs -q '.jobs[].name'`
Expected: only the `build` job. The `release` job has `if: github.ref == 'refs/heads/main'`, so it should skip.

If `release` ran on a branch, the `if` condition is broken — go back to Tasks 4-5 and verify.

- [ ] **Step 5: No commit needed (only verification)**

---

## Task 9: Open and merge a PR to land the change on main

**Files:** none (PR + merge)

- [ ] **Step 1: Open a PR**

```bash
gh pr create --title ":sparkles: feat: static versioning via gradle.properties" --body "$(cat <<'EOF'
## Summary

Replaces the runtime `git describe` version derivation with static `VERSION_NAME` / `VERSION_CODE` in `gradle.properties`, bumped + committed + tagged by CI on every push to `main`.

## Why

- Aligns with the standard Android-on-F-Droid pattern (Fossify, NewPipe, Aegis, Tasks.org, …).
- Unblocks `fdroid checkupdates` auto-update detection — fdroidserver can now statically parse the version from source.
- Removes the build's git subprocess dependency; reproducible without a full git checkout.
- Deletes `buildSrc/` (Version parser + tests) and the `QUIRE_VERSION_FALLBACK` env var plumbing.

## Out of scope

- F-Droid recipe update — done in a follow-up commit on the fdroiddata MR.

## Test plan

- [ ] `chore/static-versioning` branch CI: `android-ci` build job green; APK `versionName` matches `gradle.properties`.
- [ ] After merge: `main` CI auto-bumps `gradle.properties`, creates a `v2026.05.08.<run>` tag, publishes a Release.
- [ ] APK signing cert SHA-256 unchanged (release keystore secrets unchanged).
- [ ] No `Could not derive version` errors.
EOF
)"
```

- [ ] **Step 2: Wait for CI**

```bash
gh pr checks --watch
```

- [ ] **Step 3: Merge with squash**

```bash
gh pr merge --squash --delete-branch
```

(Match the project's existing squash-merge convention from PRs #2/#3/#4.)

- [ ] **Step 4: Verify main CI bumped + tagged**

After the squash-merge runs trigger CI on main:

```bash
sleep 30
gh run list --branch main --workflow android-ci.yaml --limit 1
```

Wait for the run to complete. Then:

```bash
git fetch --tags origin
git tag -l 'v*' --sort=-v:refname | head -1
gh release list --limit 1
```

Expected: a fresh `v2026.05.08.<run>` tag exists, a Release with that tag is published, and the APK is attached.

If CI fails on the `git push` step (e.g., "permission denied"): the workflow's `permissions: contents: write` may not be set on the build job. Check `permissions:` on the `build` job — it currently doesn't have one explicitly, so it inherits the workflow default. We may need to add `permissions: contents: write` to the build job in a follow-up if push fails.

---

## Task 10: Update fdroiddata recipe to match the new versioning

**Files:** outside this repo — performed in a checkout of `https://gitlab.com/vituzz/fdroiddata.git` on the `add-quire` branch.

This task happens after Task 9 produces a fresh tag.

- [ ] **Step 1: Note the new tag and commit SHA**

```bash
NEW_TAG=$(git tag -l 'v*' --sort=-v:refname | head -1)
NEW_SHA=$(git rev-parse "$NEW_TAG")
NEW_NAME="${NEW_TAG#v}"
NEW_CODE=$(grep '^VERSION_CODE=' gradle.properties | cut -d= -f2)
echo "Tag: $NEW_TAG  SHA: $NEW_SHA  Name: $NEW_NAME  Code: $NEW_CODE"
```

- [ ] **Step 2: Clone the fdroiddata fork (token-authed)**

```bash
TOKEN=$(grep '^        token: ' ~/Library/Application\ Support/glab-cli/config.yml | head -1 | awk '{print $2}')
mkdir -p /tmp/fdroid-quire
cd /tmp/fdroid-quire
git clone --depth 1 -b add-quire "https://oauth2:${TOKEN}@gitlab.com/vituzz/fdroiddata.git" --quiet
cd fdroiddata
```

- [ ] **Step 3: Replace the recipe with the static-versioning version**

Open `metadata/io.theficos.quire.yml` and replace its contents with:

```yaml
Categories:
  - Ebook Reader
License: Apache-2.0
AuthorName: vitofico
AuthorWebSite: https://github.com/vitofico
SourceCode: https://github.com/vitofico/quire
IssueTracker: https://github.com/vitofico/quire/issues
Changelog: https://github.com/vitofico/quire/releases

AutoName: Quire

RepoType: git
Repo: https://github.com/vitofico/quire.git
Binaries: https://github.com/vitofico/quire/releases/download/v%v/app-release.apk

Builds:
  - versionName: 2026.05.08.NN
    versionCode: 26050NN0
    commit: <NEW_SHA>
    subdir: app
    gradle:
      - yes

AllowedAPKSigningKeys: 3c1814b1499c7c7996110835995d4112a39449f8da824333ea77aa7343948ff3

AutoUpdateMode: Version
UpdateCheckMode: Tags ^v\d+\.\d+\.\d+\.\d+$
UpdateCheckData: gradle.properties|VERSION_CODE=(\d+)|.|VERSION_NAME=(.*)
CurrentVersion: 2026.05.08.NN
CurrentVersionCode: 26050NN0
```

Substitute `2026.05.08.NN`, `26050NN0`, and `<NEW_SHA>` with the values from Step 1.

Note the changes vs the previous recipe:
- `VercodeOperation` is **removed** (no longer needed — vercode is now in `gradle.properties` and `UpdateCheckData` parses it directly).
- `UpdateCheckData: gradle.properties|VERSION_CODE=(\d+)|.|VERSION_NAME=(.*)` is **added**, matching the Fossify Gallery / standard pattern.

- [ ] **Step 4: Commit and push**

```bash
git -c user.name="vito" -c user.email="vito.fico@hivepower.tech" add metadata/io.theficos.quire.yml
git -c user.name="vito" -c user.email="vito.fico@hivepower.tech" commit -m "Switch to UpdateCheckData parsing of gradle.properties (drops VercodeOperation hack)"
git push origin add-quire
```

- [ ] **Step 5: Comment on the MR with the rationale**

```bash
cat > /tmp/mr-note.md <<'EOF'
Bumped to **v<NEW_NAME>** (commit `<NEW_SHA>`). Reworked the upstream version derivation to use static `VERSION_NAME` / `VERSION_CODE` in `gradle.properties` (the Android-standard pattern, matching what Fossify, NewPipe, Aegis, etc. do).

This means the recipe can drop the `VercodeOperation` hack and just use a regular `UpdateCheckData` line — `checkupdates` should now work cleanly.
EOF
glab mr note create 37939 --repo fdroid/fdroiddata --message "$(cat /tmp/mr-note.md)"
rm -f /tmp/mr-note.md
```

Substitute `<NEW_NAME>` and `<NEW_SHA>` with the actual values.

- [ ] **Step 6: Cleanup temp folder**

```bash
cd /
rm -rf /tmp/fdroid-quire
```

---

## Self-Review

**1. Spec coverage:**

| Spec requirement | Task |
|---|---|
| `gradle.properties` becomes the source of truth | Task 1 |
| `app/build.gradle.kts` reads from project properties | Task 2 |
| Delete `buildSrc/` (Version parser + tests + module) | Task 3 |
| CI on `main` bumps + commits + tags before Gradle runs | Task 4 |
| CI on PR / feature branches reads static value | Task 4 |
| Release job no longer needs `QUIRE_VERSION_FALLBACK` | Task 5 |
| Release job's checkout sees the bumped commit | Task 5 (Step 2 — `ref: v$VERSION_NAME`) |
| CodeQL workflow drops `QUIRE_VERSION_FALLBACK` | Task 6 |
| `docs/release.md` reflects new flow | Task 7 |
| Branch-CI verification before merge | Task 8 |
| Merge to main + verify auto-bump | Task 9 |
| F-Droid recipe restored to AutoUpdateMode + drops VercodeOperation | Task 10 |

**2. Placeholder scan:**

Searched for "TBD", "TODO", "fill in", "Add appropriate", "Similar to Task N" — none present.

The placeholders `2026.05.08.NN`, `26050NN0`, `<NEW_SHA>`, `<NEW_NAME>` in Task 10 are intentional substitution slots — values can only be known at execution time after Task 9 produces a fresh tag. Each has explicit instructions for how to compute / obtain the value (Step 1).

**3. Type consistency:**

- `appVersionName: String` and `appVersionCode: Int` are referenced consistently in Task 2.
- `VERSION_NAME` / `VERSION_CODE` (uppercase) used consistently across `gradle.properties`, the build script, the workflow, and the F-Droid recipe (matching Fossify Gallery's convention, which is the reference recipe).
- The `version` output from the build job is the un-prefixed CalVer (`2026.05.08.NN`); the tag is `v$VERSION` (with prefix). Used consistently in Tasks 4, 5, 9, 10.

---

## Execution order

Tasks 1 → 2 → 3 are sequential (each depends on the previous: properties exist, build reads them, then buildSrc can be deleted).

Task 3 → Task 4: both can technically run before each other, but doing 3 first ensures the build is already in a working state when the workflow changes. Sequential.

Tasks 4, 5, 6 modify different parts of the workflow files — sequential is safer to avoid merge conflicts within a single file.

Task 7 (docs) is independent of 4-6 — could run in parallel, but sequential keeps the commit history readable.

Task 8 verifies all in-repo changes — must run after Tasks 1-7.

Task 9 lands the change.

Task 10 is post-merge follow-up.
