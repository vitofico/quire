# Changelogs

One file per release. **Filename must be the integer `versionCode`** —
not the human-readable `versionName`. F-Droid maps the changelog to the
build by versionCode.

CI sets each release's versionCode to:

    versionCode = max(previous versionCode + 1, yyMMdd * 1000)

So a first release on 2026-09-24 has versionCode `260924000` and its
changelog file is `260924000.txt`; a second release that day gets
`260924001`. Older releases used `yyMMdd * 100 + (CI_run_number % 100)`,
eight digits, such as `26050830.txt`.

Each release tag gets a corresponding `<versionCode>.txt` here, named
after the `VERSION_CODE` in `gradle.properties` at that tag.

Keep entries terse — F-Droid truncates long changelogs in the listing.
