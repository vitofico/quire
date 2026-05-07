# Changelogs

One file per release. **Filename must be the integer `versionCode`** —
not the human-readable `versionName`. F-Droid maps the changelog to the
build by versionCode.

The CalVer scheme this project uses produces:

    versionCode = (yyMMdd as int) * 100 + (CI_run_number % 100)

So a build on 2026-05-07 with CI run #42 has versionCode `26050742`,
and its changelog file is `26050742.txt`.

`26050700.txt` in this directory is a placeholder for the very first
public release. Rename or duplicate it with the actual versionCode the
release job produces.

Keep entries terse — F-Droid truncates long changelogs in the listing.
