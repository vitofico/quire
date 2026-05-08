# F-Droid publishing — design

**Status:** approved, ready for implementation plan
**Date:** 2026-05-08
**Scope:** make Quire buildable and listable on F-Droid; cover both
blockers (version derivation, fdroiddata recipe) and polish items
(changelog, featureGraphic, CONTRIBUTING, tablet screenshots,
reproducibility verification, R8 follow-up).

---

## 1. Goal

Get `io.theficos.quire` onto F-Droid via a self-submitted MR to
[fdroiddata](https://gitlab.com/fdroid/fdroiddata), with a build that
F-Droid's builder can reproduce from a clean checkout of a release tag.

Out of scope: ProGuard / R8 enablement (deferred to follow-up issue),
i18n / additional locales (English only at first listing), Play Store
parity.

---

## 2. Current state and gaps

In place: Apache-2.0 license; FOSS-only deps (Compose, Readium, Coil,
OkHttp, Room, AboutLibraries); single `INTERNET` permission; fastlane
metadata directory (`title`, `short_description`, `full_description`,
5 phone screenshots, 512×512 `icon.png`); CalVer release tags
(`v2026.05.08.29` and earlier); env-driven keystore signing in CI.

Gaps:

1. **Version derivation is non-reproducible.** `app/build.gradle.kts:14-25`
   reads `BUILD_DATE` and `GITHUB_RUN_NUMBER` env vars. F-Droid's
   builder doesn't set these, so the fallback (`LocalDate.now()`,
   run `0`) fires — APK metadata won't match the tag.
2. **No fdroiddata recipe.** F-Droid needs
   `metadata/io.theficos.quire.yml` in its data repo before any build
   happens.
3. **Changelog filename is a placeholder.**
   `fastlane/metadata/android/en-US/changelogs/26050700.txt` doesn't
   correspond to any released versionCode.
4. **No `featureGraphic.png`.** Listing renders without a banner.
5. **CONTRIBUTING.md gitmoji list omits `chore:`.** Used by an existing
   commit, should be documented.
6. **No tablet screenshots.** Listing on tablet F-Droid clients reuses
   phone screenshots, looks low-effort.
7. **No local reproducibility verification.** F-Droid's builder will
   compare its APK to the signed release; we should run that comparison
   ourselves before submitting so there are no surprises.
8. **R8 / minify is disabled** with a "revisit before publishing" TODO.
   Not a blocker; deferred.

---

## 3. Approach (the real decisions)

### 3.1 Version derivation: `git describe --tags`

Replace the env-var-driven CalVer in `app/build.gradle.kts:14-25` with
a function that runs `git describe --tags --match 'v*' --always` from
the project root and parses the output:

| Output shape | Means | versionName | versionCode |
|---|---|---|---|
| `v2026.05.08.29` (exact tag) | clean release build | `2026.05.08.29` | `26050829` |
| `v2026.05.08.29-3-gabcdef` (post-tag) | dev build past a tag | `2026.05.08.29.dev3+gabcdef` | base-tag versionCode (no bump) |
| `abcdef` (no tag in history) | shallow clone or no tags yet | error, unless `QUIRE_VERSION_FALLBACK` set | error, unless fallback set |

The helper is a Kotlin function in `app/build.gradle.kts` (or a small
included build script if it grows) that:
- Invokes `git` via `Runtime.exec()` (or Gradle's `providers.exec` for
  config-cache friendliness — pick the one that works with current
  AGP/Gradle versions).
- Parses the output with a regex.
- Caches the result in a Gradle property so the helper runs once per
  configuration phase, not per task.
- Reads `QUIRE_VERSION_FALLBACK` (e.g. `2026.05.08.29`) only if `git`
  fails or returns no tag — used by F-Droid's first build before
  `AutoUpdateMode` populates, and by `git clone --depth 1` consumers.

versionCode formula stays the same: `yyMMdd*100 + run`. Computed from
the parsed tag, not from `LocalDate.now()`.

CI changes: `.github/workflows/android-ci.yaml` no longer exports
`BUILD_DATE` or `GITHUB_RUN_NUMBER` for the build step — just runs
`./gradlew assembleRelease`. The release job still creates the tag
(unchanged); the build now reads its version from that tag.

### 3.2 fdroiddata recipe and verification

New file in fdroiddata clone (not this repo): `metadata/io.theficos.quire.yml`:

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
  - versionName: <latest-tag-name>
    versionCode: <latest-tag-versionCode>
    commit: v<latest-tag-name>
    subdir: app
    gradle:
      - yes

AutoUpdateMode: Version v%v
UpdateCheckMode: Tags ^v\d+\.\d+\.\d+\.\d+$
CurrentVersion: <latest-tag-name>
CurrentVersionCode: <latest-tag-versionCode>
```

Local verification before submitting the MR (run inside an fdroiddata
checkout with `fdroidserver` installed):

```sh
fdroid lint io.theficos.quire
fdroid readmeta
fdroid rewritemeta io.theficos.quire
fdroid build --server --on-server -v -l io.theficos.quire
```

The `--server` flag spins fdroidserver's reproducible build VM. If
that succeeds and the resulting APK's content matches the GitHub
Release APK (after signature stripping), submission is ready.

---

## 4. Mechanical items (no design choice)

The implementation plan will expand these into concrete steps.

### 4.1 Changelog filename
- Rename `fastlane/metadata/android/en-US/changelogs/26050700.txt` to
  match the versionCode of the tag we submit on (whichever is latest
  at submission time).
- Going forward: every release tag gets a corresponding
  `<versionCode>.txt`. No automation in this pass — manual entry per
  release. The release-doc note in `docs/release.md` gets a one-line
  reminder.

### 4.2 featureGraphic.png
- 1024×500 PNG at `fastlane/metadata/android/en-US/images/featureGraphic.png`.
- Composition: existing `icon.png` (centered or left-thirds), wordmark
  "Quire", tagline "Self-hosted EPUB reader for calibre-web".
  Background: app's reader theme paper-tone, not pure white.
- Generated once, committed as a binary asset. No procedural generation.

### 4.3 CONTRIBUTING.md
- Add `:wrench: chore:` to the gitmoji example list.
- (Optional touch: add `:page_facing_up:` example since it appears in
  history.)

### 4.4 Tablet screenshots
- Capture 5 equivalents of the existing phone screenshots from a Pixel
  Tablet emulator (or 10" emulator profile, 1600×2560).
- Same naming convention (`01_library.png` … `05_reader.png`).
- Save to `fastlane/metadata/android/en-US/images/tenInchScreenshots/`.
- Update `images/README.md` to drop the "optional" qualifier on tablet
  screenshots since we're shipping them.

### 4.5 Local reproducibility verification
- Document in `docs/release.md` the same `fdroid build --server`
  command from §3.2, explaining how to compare its output APK against
  the signed GitHub Release APK using `apksigner verify --print-certs`
  (signature differs, expected) and `diffoscope` or `unzip -l` for
  content equality.
- This is a one-page section, not a CI job. F-Droid's own builder is
  the authoritative repro check post-submission.

### 4.6 R8 follow-up
- Open a GitHub issue titled "Enable R8 minification for release builds".
- Body: link to `app/build.gradle.kts:60` TODO comment, list the
  modules likely to need keep rules (Readium navigator/streamer,
  Room entities, kotlinx.serialization @Serializable classes,
  OkHttp/Coil), recommend testing flow on the eink device.
- Not part of this PR.

---

## 5. Files touched

In this repo (PR `feat/fdroid-publishing`):

- `app/build.gradle.kts` — version derivation rewrite
- `.github/workflows/android-ci.yaml` — drop env-var injection
- `fastlane/metadata/android/en-US/changelogs/26050700.txt` — rename
- `fastlane/metadata/android/en-US/images/featureGraphic.png` — new
- `fastlane/metadata/android/en-US/images/tenInchScreenshots/0[1-5]_*.png` — new
- `fastlane/metadata/android/en-US/images/README.md` — small edit
- `CONTRIBUTING.md` — gitmoji list update
- `docs/release.md` — repro-verify section

Outside this repo:

- New MR to fdroiddata adding `metadata/io.theficos.quire.yml`
- New GitHub issue for R8 follow-up

---

## 6. Acceptance criteria

1. `./gradlew :app:assembleRelease` from a clean checkout of
   `v2026.05.08.29` (or whichever tag is current at PR time) produces
   an APK with `versionName = 2026.05.08.29` and `versionCode = 26050829`,
   regardless of host date or env vars.
2. `./gradlew :app:assembleDebug` from a feature branch with no
   matching tag produces a build with `versionName` ending in `.dev<n>+g<sha>`.
3. `fdroid build --server --on-server io.theficos.quire` (with the
   recipe in a fdroiddata clone) succeeds, producing an APK whose
   unzipped content matches the GitHub Release APK.
4. F-Droid listing renders with title, descriptions, icon, 5 phone
   screenshots, 5 tablet screenshots, featureGraphic, and a changelog.
5. `CONTRIBUTING.md` gitmoji list includes `chore`.
6. `docs/release.md` includes the local repro-check command.
7. R8 follow-up issue exists, references the TODO at
   `app/build.gradle.kts:60`.

---

## 7. Risks

- **Shallow F-Droid clones.** If F-Droid's builder ever switches to a
  shallow clone that strips tags, `git describe` returns nothing and
  the fallback path fires. Mitigation: `QUIRE_VERSION_FALLBACK` env
  var documented in `docs/release.md`.
- **fdroidserver build differs from local Gradle build.** Causes:
  Java version skew (fdroidserver buildserver VM picks a JDK; ours
  needs to be 17 — verified by running `fdroid build --server` before
  submission), NDK presence (none in this app), or AGP-injected
  build metadata. Mitigation: run local `fdroid build --server`
  before MR; if it fails on JDK, declare via `gradle:` flavor or
  request the build VM matches what we use.
- **AutoUpdateMode regex too strict.** The regex `^v\d+\.\d+\.\d+\.\d+$`
  rejects pre-release tags like `v2026.05.08.29-rc1`. Acceptable —
  we don't ship rc tags. If we ever do, widen the regex.
