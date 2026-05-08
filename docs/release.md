# Release process

Tag-driven. The `build` job in `android-ci.yaml` already pushes a
`vYYYY.MM.DD.<run>` tag on every push to `main`. The tag push fires the
workflow again, and the `release` job builds and signs a release APK
and attaches it to a GitHub Release.

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

The `build` job pushes `vYYYY.MM.DD.<run>` tags on every push to
`main`. The tag-trigger run does the rest. Nothing manual needed.

To cut an out-of-band release, push a tag manually:

```sh
git tag v2026.05.07.0
git push origin v2026.05.07.0
```

## Local release builds

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
