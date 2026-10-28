# Release process

Push-driven. Pushes to `main` that touch Android-relevant paths
(`app/**`, `auth/**`, `core/**`, `data/**`, `reader/**`, root Gradle
files, or the workflow itself) trigger `android-ci.yaml`. The `build`
job bumps `VERSION_NAME` in `gradle.properties` to the next CalVer
(`YYYY.MM.DD.<run>`) and `VERSION_CODE` to one above its current
value, or to `yyMMdd*1000` on a new day. It commits with a `[bot]`
author, tags `vYYYY.MM.DD.<run>`, and pushes both once the debug and
optimized release builds, unit tests and lint pass. The `release` job then
builds and signs a release APK from the freshly-pushed tag and attaches it
to a GitHub Release.

Server-only PRs (everything under `server/**`) **do not cut a release**
— the path filter excludes them. A batch of stacked server PRs that
lands without any Android-relevant change produces no APK and no tag;
the next Android-relevant push picks up the next CalVer slot. This is
intentional and matches CalVer's "calendar + run number" semantics.

### Merges that land while a release is building

The `build` job pushes its bump commit and tag in one atomic push,
after the checks pass. If anything else reached `main` in the
meantime, an Android change or a server-only one, that push is
rejected and nothing is published, neither the commit nor the tag.
The run then dispatches a fresh `android-ci` run on `main`, which
builds the new tip (the rejected run's changes plus whatever landed
since) and releases it once it passes the same checks. PRs can be
merged back to back: a burst of merges ends up in one release rather
than one each, because GitHub keeps only the newest pending run per
concurrency group and that run builds the newest commit.

There is no in-place retry. Rebasing the bump onto the new tip
conflicts on the `VERSION_NAME` line, and resetting onto it would
publish a tag whose tree the run never tested. Re-running the
rejected run does not help either, because a re-run builds the same
commit again.

### Mode-branched migrations on deploy

Container starts run `python /app/scripts/migrate.py`, which upgrades
the unlabeled `0001..0004` backbone and then `alembic upgrade
<branch>@head` for each branch enabled by `QUIRE_SERVER_PROGRESS_ENABLED`
and `QUIRE_SERVER_AI_ENABLED`. Sync-only and AI-only deployments skip the
other branch's migrations silently. See `server/migrations/README.md`.

## Signing identity and GitHub secrets

Use the existing owner-controlled Quire key. Do not generate a replacement:
Android upgrades and F-Droid depend on its certificate. Keep the keystore and
passwords outside the repository, with an offline backup.

The public SHA-256 certificate fingerprint in
`scripts/release-certificate-sha256.txt` is pinned to Quire's
[F-Droid AllowedAPKSigningKeys](https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/io.theficos.quire.yml):

```text
3c1814b1499c7c7996110835995d4112a39449f8da824333ea77aa7343948ff3
```

The workflows verify the APK signature and require this certificate, the
release package `io.theficos.quire`, the source version name/code and a
non-debuggable build before publication. They fail if any required signing
secret is missing; debug-signed or unsigned APKs must never be published.

Add the existing key's values to Settings → Secrets and variables → Actions:

| Secret | Value |
|---|---|
| `QUIRE_RELEASE_KEYSTORE_B64` | Base64 of the existing keystore, one line |
| `QUIRE_RELEASE_KEYSTORE_PASSWORD` | Its keystore password |
| `QUIRE_RELEASE_KEY_ALIAS` | Its existing key alias |
| `QUIRE_RELEASE_KEY_PASSWORD` | Its key password |

The temporary CI keystore is restricted to the runner user and removed even
when a later step fails. A missing APK requires all four secrets; repairing
sidecars for an existing verified APK needs no private signing key.

## Cutting a release

Land a commit on `main`. The `build` job's *Compute version* step picks
the next CalVer, writes it into `gradle.properties` and commits + tags
it locally; the job pushes both once the build, tests and lint pass.
Nothing manual needed.

To cut an out-of-band release, open `android-ci` in the Actions tab
and use *Run workflow* on `main`. That run releases `main` as it
stands, and cuts a new version even when nothing changed since the
last release. An empty commit does not work: it changes no files, so
it matches none of the path filters.
Manually-pushed tags are not used by this workflow.

## Local release builds

`gradle.properties` is the source of truth for `VERSION_NAME` and
`VERSION_CODE`. CalVer must contain a real calendar date and a positive run;
Android version codes must be integers from 1 to 2100000000. Normal CI retains
Quire's monotonic `max(previousCode + 1, yyMMdd * 1000)` algorithm.

With all four `QUIRE_RELEASE_*` signing variables unset, build an unsigned APK:

```sh
scripts/dgradle :app:clean :app:assembleRelease
# app/build/outputs/apk/release/app-release-unsigned.apk
```

Release builds no longer fall back to the debug key. Partial signing
configuration fails. Native Gradle builds can still use all four explicit
signing variables; `scripts/dgradle` intentionally does not pass host secrets
into its build container.

Release builds enable R8 code optimization and resource shrinking using
`proguard-android-optimize.txt` plus the libraries' consumer rules. Debug builds
remain unoptimized. Release UI compilation disables Kotlin incremental
compilation: cached incremental Compose classes differed from clean output and
changed the optimized DEX and baseline profile. Full compilation uses its own
build-cache key while debug compilation remains incremental.

Release ArtProfile tasks are disabled using
[F-Droid's baseline-profile workaround](https://f-droid.org/docs/Reproducible_Builds/#bug-baselineprof-not-deterministic):
AGP 8.6 generated different `baseline.prof` bytes with identical DEX and R8
mapping. This omits bundled startup-optimization hints. Use `:app:clean` before
release assembly to remove profiles left by older builds; CI does the same.

Keep `app/build/outputs/mapping/release/mapping.txt` with
each release's build records so obfuscated stack traces can be retraced; the
normal release job uploads it as a CI artifact. When changing dependencies,
check the optimized APK's startup, EPUB rendering,
login, sync and barcode scanning; unit tests alone do not exercise the
optimized DEX. Add narrowly scoped keep rules only when a dynamic entry point
requires them. Do not suppress all R8 warnings or keep entire library packages.

For local signing, create a private directory outside the repository and put
these files in it. Use the existing key; these instructions do not create one.
Restrict the directory to the owner (`chmod 700`) and its files (`chmod 600`).

| File | Contents |
|---|---|
| `quire-release.keystore` | Existing owner keystore |
| `keystore-password.txt` | Keystore password, one line |
| `key-password.txt` | Key password, one line |
| `key-alias.txt` | Existing key alias, one line |
| `certificate-sha256.txt` | Expected public SHA-256 fingerprint above |

```sh
scripts/sign-release /absolute/private/signing-directory
# app/build/outputs/apk/release/app-release.apk
```

This uses the already-built `quire-build:latest` image with networking disabled,
a read-only filesystem, dropped capabilities and no repository/cache mounts.
Only the unsigned APK, private signing files (read-only) and a temporary output
directory are mounted. It checks the signature, expected certificate and actual
APK identity before moving the verified signed APK into place. Passwords are
read from files, never command arguments.

## Release records and recovery

Each release publishes exactly these expected assets:

- `app-release.apk`, preserving the upstream download URL used by F-Droid.
- `app-release.apk.sha256`, a SHA-256 checksum of those exact APK bytes.
- `release-identity.json`, recording the package/version, peeled source commit,
  APK digest/size and verified certificate fingerprint, without a build timestamp.

If a tag has no APK, run `release-apk` in Actions with that existing
`vYYYY.MM.DD.<run>` tag. The source version must match the tag. Recovery uses
verification tooling from the workflow's commit, so it also supports older tags
that do not contain these scripts. It does not change the version or create a tag.

If the APK already exists, recovery downloads and verifies those bytes instead
of rebuilding them, then derives missing sidecars from them. All existing
expected assets are compared before uploads: identical assets are skipped;
different bytes fail the run. Only missing assets are uploaded. An existing
APK or sidecar is never deleted or replaced, including during a rerun. Both
workflows share a per-tag publication lock. Normal CI marks a newly created release
Latest only if its CalVer date/run is numerically newest. Recovery always creates
with `--latest=false` and leaves an existing release's metadata unchanged, so a
repair cannot displace a newer release that appeared during its build.

## Reproducibility check before submitting to F-Droid

F-Droid rebuilds the tagged source and verifies it against the signed upstream
APK. Matching extracted ZIP contents alone is insufficient: APK v2/v3 signatures
cover ZIP metadata, ordering and all other signed bytes. Use
[APK signature copying](https://f-droid.org/docs/Reproducible_Builds/) followed by
signature verification. Build-tools 34.0.0 is selected explicitly for signing.

Build the exact tag with the F-Droid recipe and retain the unsigned output:

```sh
# In an fdroiddata clone with fdroidserver installed:
fdroid lint io.theficos.quire
fdroid readmeta
fdroid build --server -v -l io.theficos.quire
```

Download that tag's `app-release.apk` from the upstream GitHub Release and
verify its pinned certificate. With `apksigcopier` installed, copy its signatures
to the independently rebuilt unsigned APK, then verify the reconstructed APK:

```sh
apksigcopier copy /path/to/upstream/app-release.apk \
  /path/to/rebuilt/app-release-unsigned.apk /tmp/quire-reconstructed.apk
"$ANDROID_HOME/build-tools/34.0.0/apksigner" verify --verbose --print-certs \
  /tmp/quire-reconstructed.apk
cmp /path/to/upstream/app-release.apk /tmp/quire-reconstructed.apk
```

A successful copied-signature verification establishes that the signed bytes
match; `cmp` also checks full byte equality. Use `diffoscope` to investigate
failures. Matching local rebuilds provide useful evidence, while acceptance by
F-Droid still depends on its recipe, environment and own verification run.

The recipe reads the static version from `gradle.properties`. Keep the committed
`app/src/main/res/raw/aboutlibraries.json` byte-identical during ordinary builds;
regenerate it only after dependency changes using the documented export task.
