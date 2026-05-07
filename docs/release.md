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
