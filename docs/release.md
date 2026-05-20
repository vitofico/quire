# Release process

Push-driven. Pushes to `main` that touch Android-relevant paths
(`app/**`, `auth/**`, `core/**`, `data/**`, `reader/**`, root Gradle
files, or the workflow itself) trigger `android-ci.yaml`. The `build`
job bumps `VERSION_NAME` / `VERSION_CODE` in `gradle.properties` to the
next CalVer (`YYYY.MM.DD.<run>` / `yyMMdd*100 + run%100`), commits with
a `[bot]` author, tags `vYYYY.MM.DD.<run>`, and pushes both before
Gradle runs. The `release` job then builds and signs a release APK from
the freshly-pushed tag and attaches it to a GitHub Release.

Server-only PRs (everything under `server/**`) **do not cut a release**
— the path filter excludes them. A batch of stacked server PRs that
lands without any Android-relevant change produces no APK and no tag;
the next Android-relevant push picks up the next CalVer slot. This is
intentional and matches CalVer's "calendar + run number" semantics.

### Known limitation: version-bump race on stacked Android merges

The `build` job commits the version bump and tag, then pushes back to
`main`. If two Android-relevant PRs merge inside one CI cycle, the
second push from the `[bot]` author can lose the race with the first
and the second tag is silently orphaned. Observed on 2026-05-17 when
PR #21 (PR6 inspect-insight) and PR #22 (PR8 series shelf) merged
within minutes: PR8's standalone tag never materialized; both PRs
shipped in PR6's release `v2026.05.17.87`. No artifact was lost — the
release APK contains the merged tree at the time the build ran — but
the per-PR tag mapping degrades.

Workaround for now: when stacking Android merges, wait for the version-
bump push from the previous run to land before merging the next PR.
The longer-term fix is a `git pull --rebase` step in the `build` job
before the push back, so a lost race retries instead of orphaning a
tag — tracked as a follow-up.

### Mode-branched migrations on deploy

Container starts run `python /app/scripts/migrate.py`, which upgrades
the unlabeled `0001..0004` backbone and then `alembic upgrade
<branch>@head` for each branch enabled by `QUIRE_SERVER_PROGRESS_ENABLED`
and `QUIRE_SERVER_AI_ENABLED`. Sync-only and AI-only deployments skip the
other branch's migrations silently. See `server/migrations/README.md`.

### Post-release cluster operator checklist (server rename)

The rename release publishes both `ghcr.io/vitofico/quire-server:<sha>`
and `ghcr.io/vitofico/quire-server:latest` AND keeps the legacy
`ghcr.io/vitofico/opds-sync:<sha>` / `:latest` tags pointing at the same
digest for one cycle. The Quire repo does **not** modify any cluster
manifests; that work is an operational ticket against
`theficos-cluster` covering, in order: (1) additive apply of the new
Secret / ConfigMap / Deployment / Service for `quire-server`, (2)
`/readyz` green on the new pod, (3) flip the calibre-web ingress
backend, (4) remove the old `opds-sync` resources after the next
release cycle confirms the new name is healthy.

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
the next CalVer, writes it into `gradle.properties`, commits + tags +
pushes, then builds. Nothing manual needed.

To cut an out-of-band release, push a no-op commit (e.g. `git commit
--allow-empty -m ":bookmark: chore: trigger release"` followed by `git
push`). Manually-pushed tags are not used by this workflow.

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
