# F-Droid Publishing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Quire reproducibly buildable from a tag (so F-Droid's builder can rebuild it), submit a self-written `fdroiddata` recipe, and ship the listing-polish items (changelog, featureGraphic, tablet screenshots, CONTRIBUTING tweak, repro-build doc, R8 follow-up issue).

**Architecture:** Replace the env-var-driven CalVer in `app/build.gradle.kts` with a tag-driven version derived from `git describe`. Pure parsing logic lives in `buildSrc/` (unit-tested with JUnit). The Gradle script invokes `git` and feeds the output to the parser. Everything else (changelog, graphics, docs, off-repo MR + issue) is mechanical follow-up.

**Tech Stack:** Kotlin, Gradle Kotlin DSL with `buildSrc`, JUnit 4 (matches the rest of the repo's test stack), Truth for assertions, `git describe` for tag introspection, Gradle via `scripts/dgradle` (Docker). For F-Droid: `fdroidserver` CLI in an `fdroiddata` clone (run on host, not in dgradle).

**Spec:** `docs/superpowers/specs/2026-05-08-fdroid-publishing-design.md`

**Branch:** `feat/fdroid-publishing` (already created and checked out; spec already committed)

**Build/test rule:** Always use `scripts/dgradle …` from the repo root for in-repo Gradle work; never the host `./gradlew`. `fdroid` commands run on the host (outside Docker, in a separate `fdroiddata` clone).

**Conventional-commits format for this repo:** `:emoji: type: subject` — match commits already on `main`. The release workflow doesn't sign commits, but pre-commit hooks may run `ruff` (server-only) and secret detection — they won't fire on Kotlin/Gradle changes.

---

## File Structure

In-repo (this branch):

| File | Status | Responsibility |
|---|---|---|
| `buildSrc/build.gradle.kts` | new | Gradle plugin descriptor for `buildSrc` (Kotlin JVM + JUnit + Truth) |
| `buildSrc/src/main/kotlin/Version.kt` | new | Pure parser: `git describe` output → `(versionName, versionCode)` |
| `buildSrc/src/test/kotlin/VersionTest.kt` | new | JUnit tests for the parser (exact tag, post-tag, fallback, error) |
| `app/build.gradle.kts` | modify | Replace env-var version block with `git describe` + `Version.fromGitDescribe()` |
| `.github/workflows/android-ci.yaml` | modify | Drop `BUILD_DATE` / `GITHUB_RUN_NUMBER` injection (no longer read by build) |
| `fastlane/metadata/android/en-US/changelogs/26050700.txt` | rename | Filename must match a real versionCode (`26050829.txt` for current latest tag) |
| `fastlane/metadata/android/en-US/images/featureGraphic.png` | new | 1024×500 listing banner |
| `fastlane/metadata/android/en-US/images/tenInchScreenshots/0[1-5]_*.png` | new | 5 tablet screenshots, 1600×2560 |
| `fastlane/metadata/android/en-US/images/README.md` | modify | Drop "optional" qualifier on tablet screenshots; note featureGraphic shipped |
| `CONTRIBUTING.md` | modify | Add `:wrench: chore:` to gitmoji example list |
| `docs/release.md` | modify | New "Reproducibility check before submitting to F-Droid" section |

Off-repo:

| File | Status | Where | Responsibility |
|---|---|---|---|
| `metadata/io.theficos.quire.yml` | new | fdroiddata MR | F-Droid build recipe |
| GitHub issue: "Enable R8 minification for release builds" | new | github.com/vitofico/quire | Track R8 follow-up |

---

## Task 1: Create `buildSrc/` skeleton

**Why first:** Everything else either depends on the version parser, or is independent mechanical work that can run in parallel. Establishing `buildSrc/` first unblocks Tasks 2–3.

**Files:**
- Create: `buildSrc/build.gradle.kts`
- Create: `buildSrc/src/main/kotlin/Version.kt` (placeholder)
- Create: `buildSrc/src/test/kotlin/VersionTest.kt` (placeholder)

- [ ] **Step 1: Create `buildSrc/build.gradle.kts`**

```kotlin
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    google()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.truth:truth:1.4.4")
}

tasks.test {
    useJUnit()
}
```

- [ ] **Step 2: Create empty `Version.kt`**

```kotlin
// buildSrc/src/main/kotlin/Version.kt
data class VersionInfo(val name: String, val code: Int)

object Version {
    fun fromGitDescribe(output: String, fallback: String? = null): VersionInfo {
        TODO("Task 2 implements this")
    }
}
```

- [ ] **Step 3: Create empty `VersionTest.kt`**

```kotlin
// buildSrc/src/test/kotlin/VersionTest.kt
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VersionTest {
    @Test
    fun placeholder() {
        assertThat(true).isTrue()
    }
}
```

- [ ] **Step 4: Verify `buildSrc` compiles and tests pass**

Run: `scripts/dgradle :buildSrc:test`
Expected: BUILD SUCCESSFUL, 1 test passed (the placeholder).

If you see "Task 'test' not found in project ':buildSrc'", run `scripts/dgradle help` first to populate the configuration cache, then retry.

- [ ] **Step 5: Commit**

```bash
git add buildSrc/build.gradle.kts buildSrc/src
git commit -m ":construction: chore: scaffold buildSrc for version parser"
```

---

## Task 2: Implement and test the version parser (TDD)

**Why this exact form:** `git describe --tags --match 'v*' --always` produces three shapes. Tests pin each shape, then a single regex-based parser implements them.

**Files:**
- Modify: `buildSrc/src/main/kotlin/Version.kt`
- Modify: `buildSrc/src/test/kotlin/VersionTest.kt`

- [ ] **Step 1: Replace `VersionTest.kt` with the full test set**

```kotlin
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.Assert.assertThrows

class VersionTest {

    @Test
    fun exactTag_parsesNameAndCode() {
        val result = Version.fromGitDescribe("v2026.05.08.29")
        assertThat(result.name).isEqualTo("2026.05.08.29")
        assertThat(result.code).isEqualTo(26050829)
    }

    @Test
    fun exactTag_yearEndRollover() {
        val result = Version.fromGitDescribe("v2026.12.31.99")
        assertThat(result.name).isEqualTo("2026.12.31.99")
        assertThat(result.code).isEqualTo(26123199)
    }

    @Test
    fun exactTag_runOver99_wrapsModulo() {
        // Run-number byte is mod-100 to fit in Int safely; documented in spec.
        val result = Version.fromGitDescribe("v2026.05.08.103")
        assertThat(result.name).isEqualTo("2026.05.08.103")
        assertThat(result.code).isEqualTo(26050803)
    }

    @Test
    fun postTag_appendsDevSuffix_keepsBaseVersionCode() {
        val result = Version.fromGitDescribe("v2026.05.08.29-3-gabcdef0")
        assertThat(result.name).isEqualTo("2026.05.08.29.dev3+gabcdef0")
        assertThat(result.code).isEqualTo(26050829)
    }

    @Test
    fun postTag_trimsLeadingTrailingWhitespace() {
        val result = Version.fromGitDescribe("  v2026.05.08.29-1-gdeadbee\n")
        assertThat(result.name).isEqualTo("2026.05.08.29.dev1+gdeadbee")
    }

    @Test
    fun bareSha_withFallback_treatsFallbackAsExactTag() {
        val result = Version.fromGitDescribe("abcdef0", fallback = "2026.05.08.29")
        assertThat(result.name).isEqualTo("2026.05.08.29")
        assertThat(result.code).isEqualTo(26050829)
    }

    @Test
    fun bareSha_noFallback_throwsWithGuidance() {
        val ex = assertThrows(IllegalStateException::class.java) {
            Version.fromGitDescribe("abcdef0")
        }
        assertThat(ex).hasMessageThat().contains("QUIRE_VERSION_FALLBACK")
    }

    @Test
    fun emptyOutput_noFallback_throws() {
        assertThrows(IllegalStateException::class.java) {
            Version.fromGitDescribe("")
        }
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

Run: `scripts/dgradle :buildSrc:test`
Expected: 8 tests, 7 failing with "An operation is not implemented" (the `TODO()`), 1 placeholder passing — wait, the placeholder was replaced. So all 8 fail. Confirm failure messages mention `TODO`.

- [ ] **Step 3: Replace `Version.kt` with the implementation**

```kotlin
// buildSrc/src/main/kotlin/Version.kt
data class VersionInfo(val name: String, val code: Int)

object Version {

    private val EXACT = Regex("""^v(\d{4})\.(\d{2})\.(\d{2})\.(\d+)$""")
    private val POST_TAG = Regex("""^v(\d{4})\.(\d{2})\.(\d{2})\.(\d+)-(\d+)-(g[0-9a-f]+)$""")

    fun fromGitDescribe(output: String, fallback: String? = null): VersionInfo {
        val trimmed = output.trim()

        EXACT.matchEntire(trimmed)?.let { m ->
            val (yyyy, mm, dd, run) = m.destructured
            return VersionInfo(
                name = "$yyyy.$mm.$dd.$run",
                code = computeCode(yyyy, mm, dd, run)
            )
        }

        POST_TAG.matchEntire(trimmed)?.let { m ->
            val (yyyy, mm, dd, run, dist, sha) = m.destructured
            return VersionInfo(
                name = "$yyyy.$mm.$dd.$run.dev$dist+$sha",
                code = computeCode(yyyy, mm, dd, run)
            )
        }

        if (fallback != null) {
            return fromGitDescribe("v$fallback")
        }

        error(
            "Could not derive version from git describe output: '$trimmed'. " +
                "Set QUIRE_VERSION_FALLBACK env var to a tag name like '2026.05.08.29' " +
                "(without leading 'v'), or build from a checkout that has at least one " +
                "matching tag in history."
        )
    }

    private fun computeCode(yyyy: String, mm: String, dd: String, run: String): Int {
        val yyMMdd = (yyyy.takeLast(2) + mm + dd).toInt()
        return yyMMdd * 100 + (run.toInt() % 100)
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

Run: `scripts/dgradle :buildSrc:test`
Expected: 8 tests passing.

- [ ] **Step 5: Commit**

```bash
git add buildSrc/src/main/kotlin/Version.kt buildSrc/src/test/kotlin/VersionTest.kt
git commit -m ":sparkles: feat: tag-driven version parser in buildSrc"
```

---

## Task 3: Wire parser into `app/build.gradle.kts`

**Files:**
- Modify: `app/build.gradle.kts` (replace the CalVer block at lines 1-25 of the original, then update the `defaultConfig` versionCode/versionName references)

- [ ] **Step 1: Read the current app/build.gradle.kts to confirm line numbers**

Run: `cat -n app/build.gradle.kts | sed -n '1,40p'`
Confirm: lines 1-25 contain the `import java.time.LocalDate` block, the `BUILD_DATE` / `GITHUB_RUN_NUMBER` reads, and the `calverName` / `calverCode` declarations.

- [ ] **Step 2: Replace the version-derivation block**

In `app/build.gradle.kts`, replace the entire top section from line 1 through line 25 (inclusive — everything before the `android {` block) with:

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.aboutlibraries)
}

// Tag-driven CalVer. The build reads `git describe --tags --match 'v*'`
// and parses it via buildSrc/Version.kt. Set QUIRE_VERSION_FALLBACK
// (e.g. "2026.05.08.29") if building from a shallow clone with no tags.
fun gitDescribe(): String =
    try {
        val process = ProcessBuilder("git", "describe", "--tags", "--match", "v*", "--always")
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0) output else ""
    } catch (_: Exception) {
        ""
    }

val versionInfo = Version.fromGitDescribe(
    output = gitDescribe(),
    fallback = System.getenv("QUIRE_VERSION_FALLBACK")
)
```

(Remove the original `import java.time.LocalDate` and `import java.time.format.DateTimeFormatter` lines — no longer needed.)

- [ ] **Step 3: Update `defaultConfig` to use `versionInfo`**

In the `android { defaultConfig { ... } }` block, change:
```kotlin
versionCode = calverCode
versionName = calverName
```
to:
```kotlin
versionCode = versionInfo.code
versionName = versionInfo.name
```

- [ ] **Step 4: Verify Gradle reads the right version**

Run: `scripts/dgradle :app:properties -q | grep -E "^version(Name|Code):"`
Expected (assuming HEAD is past the `v2026.05.08.29` tag in this branch's history):
```
versionCode: 26050829
versionName: 2026.05.08.29.dev<n>+g<sha>
```
(Where `<n>` is the number of commits since the tag — at least 1, since we have the spec commit and the upcoming buildSrc commits.)

- [ ] **Step 5: Verify exact-tag behavior (manual)**

Run:
```bash
git log --oneline v2026.05.08.29..HEAD | wc -l
```
Confirm it's > 0 (we're past the tag). The point: `versionName` ends in `.dev<n>+g<sha>`, not just `2026.05.08.29` — that's the post-tag path working correctly.

- [ ] **Step 6: Verify fallback behavior**

In a worktree or with a temporary checkout outside the git tree, you'd test `QUIRE_VERSION_FALLBACK`. As a faster sanity check:
```bash
QUIRE_VERSION_FALLBACK=2026.05.08.29 scripts/dgradle :app:properties -q | grep -E "^version(Name|Code):"
```
The fallback only fires when `git describe` returns nothing — so this run still uses the real git output. To force the fallback path, the simplest test is unit-covered already in Task 2. No extra verification needed here.

- [ ] **Step 7: Commit**

```bash
git add app/build.gradle.kts
git commit -m ":sparkles: feat: derive app version from git tag instead of CI env vars"
```

---

## Task 4: Drop env-var injection from CI workflow

**Files:**
- Modify: `.github/workflows/android-ci.yaml`

- [ ] **Step 1: Read current workflow to find the env-var sections**

Run: `grep -n "BUILD_DATE\|GITHUB_RUN_NUMBER\|VERSION_NAME" .github/workflows/android-ci.yaml`

You'll see two relevant sections:
1. The `Compute version` step in the `build` job that exports `VERSION_NAME` to `$GITHUB_ENV` (used only for naming the uploaded artifact).
2. Possibly a similar step in the `release` job.

- [ ] **Step 2: Decide what to keep**

The build itself no longer needs `BUILD_DATE` or `GITHUB_RUN_NUMBER` — version comes from the tag. But the `Compute version` step also writes `VERSION_NAME` for the `actions/upload-artifact` step's `name` field. Keep computing a human-readable string for artifact naming, but drop any env vars consumed by Gradle.

Concretely: change the `Compute version` step to derive `VERSION_NAME` from `git describe --tags --always` so the artifact name matches the APK's actual versionName.

- [ ] **Step 3: Edit the `Compute version` step**

Find the step (currently around line 47-54 of the workflow):
```yaml
      - id: version
        name: Compute version
        run: |
          BUILD_DATE=$(date -u +%Y-%m-%d)
          VERSION="${BUILD_DATE//-/.}.${GITHUB_RUN_NUMBER}"
          echo "VERSION_NAME=$VERSION" >> "$GITHUB_ENV"
          echo "version=$VERSION" >> "$GITHUB_OUTPUT"
          echo "Building version $VERSION"
```

Replace with:
```yaml
      - id: version
        name: Compute version
        run: |
          # Match what app/build.gradle.kts derives from the same checkout.
          # Fetch tags first — actions/checkout@v4 with default fetch-depth
          # may not pull tags.
          git fetch --tags --depth=1 origin || true
          VERSION=$(git describe --tags --match 'v*' --always | sed 's/^v//')
          echo "VERSION_NAME=$VERSION" >> "$GITHUB_ENV"
          echo "version=$VERSION" >> "$GITHUB_OUTPUT"
          echo "Building version $VERSION"
```

- [ ] **Step 4: Ensure `actions/checkout` fetches tags**

Find both `actions/checkout@…` steps. Add `with: fetch-depth: 0` (or `fetch-tags: true` if available) so `git describe` works inside the build.

For the `build` job:
```yaml
      - uses: actions/checkout@34e114876b0b11c390a56381ad16ebd13914f8d5 # v4
        with:
          fetch-depth: 0
```

For the `release` job, same treatment.

- [ ] **Step 5: Verify the YAML parses**

Run: `python3 -c "import yaml, sys; yaml.safe_load(open('.github/workflows/android-ci.yaml'))" && echo OK`
Expected: `OK`. (If `yaml` isn't installed: `pip install pyyaml` or skip — GitHub will reject malformed YAML on push.)

- [ ] **Step 6: Commit**

```bash
git add .github/workflows/android-ci.yaml
git commit -m ":construction_worker: ci: drop env-var version injection, fetch tags for git-describe"
```

---

## Task 5: Rename placeholder changelog to current versionCode

**Files:**
- Rename: `fastlane/metadata/android/en-US/changelogs/26050700.txt` → `26050829.txt`

The latest tag is `v2026.05.08.29`, versionCode `26050829`. F-Droid maps changelog filename to versionCode; the placeholder `26050700` doesn't match any tag.

- [ ] **Step 1: Confirm the latest tag**

Run: `git tag -l 'v*' --sort=-v:refname | head -1`
Expected: `v2026.05.08.29` (or, if a newer tag has been pushed since this plan was written, use that one's versionCode — `yyMMdd*100 + run` per the formula in `Version.kt`).

- [ ] **Step 2: Rename the file**

```bash
git mv fastlane/metadata/android/en-US/changelogs/26050700.txt \
       fastlane/metadata/android/en-US/changelogs/26050829.txt
```

- [ ] **Step 3: Update the changelog README to drop the placeholder note**

Edit `fastlane/metadata/android/en-US/changelogs/README.md`:
- Find the paragraph starting `\`26050700.txt\` in this directory is a placeholder…`
- Replace it with: `Each release tag gets a corresponding \`<versionCode>.txt\` here. Filename = \`yyMMdd*100 + (CI_run % 100)\` of the tag.`

- [ ] **Step 4: Commit**

```bash
git add fastlane/metadata/android/en-US/changelogs/
git commit -m ":memo: docs: align changelog filename with v2026.05.08.29 versionCode"
```

---

## Task 6: Add `:wrench: chore:` to CONTRIBUTING.md gitmoji list

**Files:**
- Modify: `CONTRIBUTING.md` (the "Common gitmoji used here" paragraph)

- [ ] **Step 1: Find the list**

Run: `grep -n "Common gitmoji" CONTRIBUTING.md`

- [ ] **Step 2: Edit the list**

In `CONTRIBUTING.md`, find the paragraph:
```
Common gitmoji used here: `:sparkles:` (feat), `:bug:` (fix),
`:memo:` (docs), `:white_check_mark:` (tests), `:construction_worker:`
(CI), `:art:` (refactor/style), `:fire:` (removals),
`:lock:` (security), `:page_facing_up:` (legal/license).
```

Add `:wrench:` (chore) and `:construction:` (work-in-progress / scaffolding) so the list reflects what's actually used:

```
Common gitmoji used here: `:sparkles:` (feat), `:bug:` (fix),
`:memo:` (docs), `:white_check_mark:` (tests), `:construction_worker:`
(CI), `:wrench:` (chore), `:construction:` (scaffolding / WIP),
`:art:` (refactor/style), `:fire:` (removals), `:lock:` (security),
`:page_facing_up:` (legal/license).
```

- [ ] **Step 3: Add a `chore:` example to the example block**

In the same section, find the code-fenced examples:
```
:sparkles: feat: add bookmarks sync endpoint
:bug: fix: handle 401 from calibre-web during auth probe
:memo: docs: clarify identity precedence
:white_check_mark: test: cover the alias-merge transaction
:construction_worker: ci: pin actions to commit SHAs
```

Add one line:
```
:wrench: chore: bump renovate config
```

- [ ] **Step 4: Commit**

```bash
git add CONTRIBUTING.md
git commit -m ":memo: docs: document chore type and missing gitmoji in CONTRIBUTING"
```

---

## Task 7: Capture tablet screenshots

**Files:**
- Create: `fastlane/metadata/android/en-US/images/tenInchScreenshots/01_library.png`
- Create: `fastlane/metadata/android/en-US/images/tenInchScreenshots/02_catalog.png`
- Create: `fastlane/metadata/android/en-US/images/tenInchScreenshots/03_settings.png`
- Create: `fastlane/metadata/android/en-US/images/tenInchScreenshots/04_licenses.png`
- Create: `fastlane/metadata/android/en-US/images/tenInchScreenshots/05_reader.png`

**Manual task — requires an Android emulator on the host.** This task can't be automated because it produces binary screenshots that need a running app and a configured calibre-web fixture.

- [ ] **Step 1: Build the debug APK**

```bash
scripts/dgradle :app:assembleDebug
```
APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 2: Start a 10" tablet emulator**

In Android Studio, create an AVD with profile "Pixel Tablet" (1600×2560, API 34). Start it. Or via CLI if `avdmanager` is on PATH:
```bash
avdmanager create avd -n quire-tablet -k "system-images;android-34;google_apis;x86_64" -d "pixel_tablet"
emulator -avd quire-tablet &
```

- [ ] **Step 3: Install the APK and configure**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p io.theficos.quire 1   # launches the app
```
Configure with the same calibre-web URL and credentials you used for the phone screenshots so the library content matches.

- [ ] **Step 4: Capture each screen**

Navigate to each screen and capture, in order matching the phone set:
1. Library grid (`01_library.png`)
2. OPDS catalog browse (`02_catalog.png`)
3. Settings (`03_settings.png`)
4. Open-source licenses (`04_licenses.png`)
5. Reader with a book open (`05_reader.png`)

For each:
```bash
adb exec-out screencap -p > fastlane/metadata/android/en-US/images/tenInchScreenshots/0N_<name>.png
```

- [ ] **Step 5: Verify dimensions**

```bash
for f in fastlane/metadata/android/en-US/images/tenInchScreenshots/*.png; do
  python3 -c "import sys; from PIL import Image; im=Image.open('$f'); print('$f', im.size)"
done
```
Expected: each is 1600×2560 (or 2560×1600 if landscape was captured — F-Droid accepts either, but pick one orientation and stick to it).

- [ ] **Step 6: Update images/README.md**

Edit `fastlane/metadata/android/en-US/images/README.md`:
- In the "tenInchScreenshots" section, drop the "optional" qualifier — change `## tenInchScreenshots/ — optional` to `## tenInchScreenshots/ — recommended`.
- Add a one-liner under the heading: `Captured at 1600×2560 from a Pixel Tablet emulator. Same naming as phoneScreenshots.`

- [ ] **Step 7: Commit**

```bash
git add fastlane/metadata/android/en-US/images/tenInchScreenshots/ \
        fastlane/metadata/android/en-US/images/README.md
git commit -m ":sparkles: feat: tablet screenshots for F-Droid listing"
```

---

## Task 8: Add featureGraphic.png

**Files:**
- Create: `fastlane/metadata/android/en-US/images/featureGraphic.png`
- Modify: `fastlane/metadata/android/en-US/images/README.md`

**Manual task — requires an image editor.** 1024×500 PNG, F-Droid's banner size. The asset isn't auto-generated; pick the production tool you prefer (Inkscape, Figma, Affinity, ImageMagick scripted).

Composition (per spec §4.2):
- Background: app's reader theme paper-tone (off-white, e.g. `#F2EBDD`), not pure white.
- Existing `icon.png` placed left-thirds, ~256px tall.
- Wordmark "Quire" (any clean serif or geometric sans, no specific font required) right of the icon.
- Tagline beneath the wordmark: "Self-hosted EPUB reader for calibre-web".

- [ ] **Step 1: Create the image**

Produce `featureGraphic.png` matching the brief above. Save to `fastlane/metadata/android/en-US/images/featureGraphic.png`.

- [ ] **Step 2: Verify dimensions and format**

```bash
python3 -c "from PIL import Image; im=Image.open('fastlane/metadata/android/en-US/images/featureGraphic.png'); print(im.size, im.mode, im.format)"
```
Expected: `(1024, 500) RGB PNG` (or `RGBA` — both fine; F-Droid handles both).

```bash
ls -la fastlane/metadata/android/en-US/images/featureGraphic.png
```
Expected: under 1 MB.

- [ ] **Step 3: Update images/README.md**

In `fastlane/metadata/android/en-US/images/README.md`, change the featureGraphic section heading from "optional but improves listing" to "shipped" and remove the "Place at:" instruction (the file is now committed).

- [ ] **Step 4: Commit**

```bash
git add fastlane/metadata/android/en-US/images/featureGraphic.png \
        fastlane/metadata/android/en-US/images/README.md
git commit -m ":sparkles: feat: featureGraphic.png for F-Droid listing"
```

---

## Task 9: Add reproducibility-check section to docs/release.md

**Files:**
- Modify: `docs/release.md`

- [ ] **Step 1: Append a new section**

At the end of `docs/release.md`, append:

````markdown

## Reproducibility check before submitting to F-Droid

F-Droid's builder rebuilds every release from source and compares the
output to the signed APK in your GitHub Release. If the contents
differ, F-Droid won't publish. Run the same check locally before
submitting the recipe MR.

```sh
# In an fdroiddata clone (https://gitlab.com/fdroid/fdroiddata),
# with fdroidserver installed:
cd ~/src/fdroiddata
fdroid lint io.theficos.quire
fdroid readmeta
fdroid rewritemeta io.theficos.quire
fdroid build --server -v -l io.theficos.quire
```

The `--server` flag spins fdroidserver's reproducible build VM
(headless VirtualBox by default; podman backend also supported). On
success, the unsigned APK lands in
`~/src/fdroiddata/unsigned/io.theficos.quire_<versionCode>.apk`.

Compare it to your signed release APK:

```sh
# Strip signatures from both, then diff the contents.
cd /tmp && mkdir cmp && cd cmp
unzip -q ~/src/fdroiddata/unsigned/io.theficos.quire_*.apk -d a
unzip -q ~/Downloads/app-release.apk -d b
rm -rf a/META-INF b/META-INF       # signatures differ by design
diff -r a b && echo "REPRODUCIBLE"
```

If `diff` reports no differences, F-Droid will accept the build. If
it reports differences in `classes*.dex`, the build is non-reproducible
— check JDK version, AGP version, and `gradle.properties` flags in
the fdroidserver VM vs the CI runner.

If `git describe` returns nothing inside the fdroidserver VM (it does
a non-shallow clone, but on rare runs tags might not propagate),
set `QUIRE_VERSION_FALLBACK` in the recipe's `Builds:` block:

```yaml
Builds:
  - versionName: 2026.05.08.29
    versionCode: 26050829
    commit: v2026.05.08.29
    subdir: app
    gradle: [ yes ]
    env:
      QUIRE_VERSION_FALLBACK: 2026.05.08.29
```
````

- [ ] **Step 2: Commit**

```bash
git add docs/release.md
git commit -m ":memo: docs: F-Droid reproducibility check workflow in release.md"
```

---

## Task 10: Push branch and open PR

- [ ] **Step 1: Push the branch**

```bash
git push -u origin feat/fdroid-publishing
```

- [ ] **Step 2: Open the PR**

```bash
gh pr create --title ":sparkles: feat: F-Droid publishing readiness" --body "$(cat <<'EOF'
## Summary

- Tag-driven version derivation (`buildSrc/Version.kt`) replacing CI env vars — F-Droid can now reproduce a build from a tag.
- CI workflow drops `BUILD_DATE` / `GITHUB_RUN_NUMBER` injection; reads `git describe` instead.
- `featureGraphic.png` and tablet screenshots for the F-Droid listing.
- Changelog filename aligned with the actual versionCode of the latest tag.
- `CONTRIBUTING.md` documents `:wrench: chore:` (already used in history).
- `docs/release.md` documents the local reproducibility check.

## Test plan

- [ ] `scripts/dgradle :buildSrc:test` — 8 tests pass
- [ ] `scripts/dgradle :app:properties -q | grep version` — versionName/versionCode derived from tag
- [ ] `scripts/dgradle :app:assembleDebug` — APK builds
- [ ] `scripts/dgradle test` — full test suite passes
- [ ] CI green on the PR
- [ ] Visual: confirm `featureGraphic.png` and 5 tablet screenshots render correctly

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Capture the PR URL from the output.

---

## Task 11 (off-repo): Submit fdroiddata recipe MR

**This task happens outside the Quire repo, after the PR in Task 10 is merged and a fresh tag is cut.**

- [ ] **Step 1: Clone fdroiddata**

```bash
mkdir -p ~/src && cd ~/src
git clone https://gitlab.com/fdroid/fdroiddata.git
cd fdroiddata
```

- [ ] **Step 2: Install fdroidserver if missing**

```bash
pipx install fdroidserver
# OR: pip install --user fdroidserver
fdroid --version   # confirm it runs
```

You also need a buildserver backend. The simplest on Linux is `podman` (`apt install podman`); on macOS, set up VirtualBox + Vagrant per fdroidserver docs.

- [ ] **Step 3: Identify the latest tag and versionCode**

In the Quire repo:
```bash
cd ~/src/quire   # adjust to your path
LATEST=$(git tag -l 'v*' --sort=-v:refname | head -1)
echo "Latest tag: $LATEST"
# Compute versionCode: yyMMdd*100 + (run % 100)
```

- [ ] **Step 4: Create the recipe**

Back in `fdroiddata`:
```bash
cd ~/src/fdroiddata
mkdir -p metadata
```

Create `metadata/io.theficos.quire.yml`:
```yaml
Categories:
  - Reading
License: Apache-2.0
SourceCode: https://github.com/vitofico/quire
IssueTracker: https://github.com/vitofico/quire/issues
Changelog: https://github.com/vitofico/quire/releases

AutoName: Quire

RepoType: git
Repo: https://github.com/vitofico/quire.git

Builds:
  - versionName: <FILL_IN_FROM_LATEST_TAG>     # e.g. 2026.05.08.29
    versionCode: <FILL_IN_COMPUTED>            # e.g. 26050829
    commit: v<FILL_IN_FROM_LATEST_TAG>         # e.g. v2026.05.08.29
    subdir: app
    gradle:
      - yes

AutoUpdateMode: Version v%v
UpdateCheckMode: Tags ^v\d+\.\d+\.\d+\.\d+$
CurrentVersion: <FILL_IN_FROM_LATEST_TAG>
CurrentVersionCode: <FILL_IN_COMPUTED>
```

Replace the four `<FILL_IN_…>` values with the latest tag's name and computed versionCode.

- [ ] **Step 5: Lint and rewrite the metadata**

```bash
fdroid lint io.theficos.quire
fdroid readmeta
fdroid rewritemeta io.theficos.quire
```

`rewritemeta` normalizes formatting; if it touches the file, commit the result.

- [ ] **Step 6: Run a reproducible build**

```bash
fdroid build --server -v -l io.theficos.quire
```

Expected: `BUILD SUCCESSFUL`, unsigned APK at `unsigned/io.theficos.quire_<versionCode>.apk`.

If the build fails, read the log for whether it's a Gradle issue (likely fixable in the recipe — JDK version, missing flag) or a source-code issue (would need a fix back in the Quire repo).

- [ ] **Step 7: Compare against the GitHub Release APK**

Follow the `unzip` + `diff -r` recipe from `docs/release.md`. If the result is "REPRODUCIBLE", proceed. If not, debug before submitting.

- [ ] **Step 8: Commit and open MR**

```bash
git checkout -b add-quire
git add metadata/io.theficos.quire.yml
git commit -m "New app: io.theficos.quire (Quire)"
git push -u origin add-quire
```

Open the MR at https://gitlab.com/fdroid/fdroiddata/-/merge_requests with title "New app: Quire (io.theficos.quire)" and a body that includes:
- Link to the GitHub Release used as the build reference
- Link back to the Quire repo
- Confirmation that `fdroid build --server` succeeded locally and the APK was reproducible vs the signed release
- Acknowledgment of the F-Droid Inclusion Policy

Capture the MR URL.

---

## Task 12 (off-repo): Open R8 follow-up issue

- [ ] **Step 1: Create the issue**

```bash
gh issue create --repo vitofico/quire \
  --title "Enable R8 minification for release builds" \
  --body "$(cat <<'EOF'
## Context

`app/build.gradle.kts` currently has \`isMinifyEnabled = false\` in the
release block, with the comment "Phase 1 only; revisit before publishing".
Now that the F-Droid listing has shipped (#<TASK_10_PR_NUMBER>), this is
the next polish item.

## Why

R8 typically shrinks the APK 30–50% and removes unused code. The current
release APK is larger than it needs to be for an F-Droid download.

## What needs to happen

1. Set \`isMinifyEnabled = true\` and \`isShrinkResources = true\` in the
   release \`buildType\`.
2. Add Proguard keep rules in \`app/proguard-rules.pro\` for modules that
   use reflection or generated code:
   - **Readium navigator / streamer / shared / opds** — Readium uses
     reflection on resource handlers; check Readium's published consumer
     rules or upstream issues.
   - **Room** — entities and DAOs (the Room compiler usually generates
     keep rules itself; verify with \`./gradlew :app:assembleRelease\`).
   - **kotlinx.serialization** — \`@Serializable\` classes need to keep
     their companion serializers; the kotlinx-serialization Gradle plugin
     adds rules but double-check.
   - **OkHttp / Coil** — usually OK out of the box, but watch for
     interceptor-related warnings in the R8 log.
3. Test on the eink device (the target hardware) — R8 bugs often surface
   at runtime on specific code paths, not in unit tests.

## Done when

- \`scripts/dgradle :app:assembleRelease\` produces a minified APK
  noticeably smaller than the un-minified one.
- App launches, browses OPDS, opens an EPUB, syncs progress on the eink
  device with no R8-related crashes.
- F-Droid still rebuilds reproducibly (run the
  \`fdroid build --server\` check from \`docs/release.md\`).
EOF
)"
```

Capture the issue URL.

---

## Self-Review

**Spec coverage check:**

| Spec section | Task |
|---|---|
| §3.1 Version derivation | Tasks 1, 2, 3 |
| §3.2 fdroiddata recipe | Task 11 |
| §4.1 Changelog filename | Task 5 |
| §4.2 featureGraphic.png | Task 8 |
| §4.3 CONTRIBUTING.md | Task 6 |
| §4.4 Tablet screenshots | Task 7 |
| §4.5 Reproducibility verification | Task 9 |
| §4.6 R8 follow-up | Task 12 |
| §5 Files touched | covered across tasks |
| §6 Acceptance criteria | covered by Task 10 PR test plan + Task 11 build check |
| §7 Risks | mitigations present in Tasks 3, 4, 9 (fallback env var documented in all three places) |

CI workflow change (§3.1 last paragraph) → Task 4. Covered.

**Placeholder scan:** No "TBD" / "TODO" / "fill in details" left except `<FILL_IN_FROM_LATEST_TAG>` in Task 11, which is intentional template syntax — the values can only be known at submission time. Same for `<TASK_10_PR_NUMBER>` in Task 12. Each has explicit instruction on how to compute / find the value.

**Type consistency:** `Version.fromGitDescribe()` signature is identical in `Version.kt` (Task 2), `VersionTest.kt` (Task 2), and `app/build.gradle.kts` (Task 3). `VersionInfo` data class with `name: String, code: Int` referenced consistently.

---

## Execution order

Tasks 1, 2, 3, 4 are sequential (each depends on the previous).

Tasks 5, 6, 9 are independent of each other and the version-derivation chain — runnable in parallel.

Tasks 7, 8 are manual (require host emulator / image editor) and independent.

Task 10 depends on all in-repo tasks (1–9).

Tasks 11, 12 happen after Task 10 PR merges and a fresh release tag is cut.
