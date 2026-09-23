# Release process

Push-driven. Pushes to `main` that touch Android-relevant paths
(`app/**`, `auth/**`, `core/**`, `data/**`, `reader/**`, root Gradle
files, or the workflow itself) trigger `android-ci.yaml`. The `build`
job bumps `VERSION_NAME` in `gradle.properties` to the next CalVer
(`YYYY.MM.DD.<run>`) and `VERSION_CODE` to one above its current
value, or to `yyMMdd*1000` on a new day. It commits with a `[bot]`
author, tags `vYYYY.MM.DD.<run>`, and pushes both once the debug
build, unit tests and lint pass. The `release` job then builds and
signs a release APK from the freshly-pushed tag and attaches it to a
GitHub Release.

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

Add to repo Settings → Secrets and variables → Actions:

| Secret | Value |
|---|---|
| `QUIRE_RELEASE_KEYSTORE_B64` | `base64 < quire-release.keystore` (one line, no wrap) |
| `QUIRE_RELEASE_KEYSTORE_PASSWORD` | the `-storepass` you set when creating the keystore |
| `QUIRE_RELEASE_KEY_ALIAS` | `quire` (or whatever `-alias` you used) |
| `QUIRE_RELEASE_KEY_PASSWORD` | the `-keypass` you set when creating the key |

If `QUIRE_RELEASE_KEYSTORE_B64` is missing, the `release` job still
runs but produces a debug-signed APK. That's safe to publish for
testers but should not be your `latest` release.

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

`gradle.properties` carries the version (`VERSION_NAME` and
`VERSION_CODE`). Whatever's committed at HEAD is what the local build
reports — if you want a specific version locally, edit those values
before running.

```sh
export QUIRE_RELEASE_KEYSTORE=/abs/path/to/quire-release.keystore
export QUIRE_RELEASE_KEYSTORE_PASSWORD=...
export QUIRE_RELEASE_KEY_ALIAS=quire
export QUIRE_RELEASE_KEY_PASSWORD=...

scripts/dgradle :app:assembleRelease
# APK at app/build/outputs/apk/release/app-release.apk
```

If the env vars are unset, `:app:assembleRelease` falls back to
debug-signed.

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

The version values come from `gradle.properties` (`VERSION_NAME` and
`VERSION_CODE`), which CI bumps on every push to `main` before the
build runs. fdroidserver reads them via the recipe's `UpdateCheckData`
line, so each tag's APK metadata is statically derivable from source
without running Gradle.
