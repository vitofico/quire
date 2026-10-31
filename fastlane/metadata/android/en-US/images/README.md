# F-Droid metadata images

This directory contains the icon, feature graphic and phone screenshots used
by F-Droid metadata. Keep replacements as real app captures and review them
against the current interface; historical images do not verify newer features.

## icon.png — required

512×512 PNG, 32-bit RGBA, max 1 MB. The app launcher icon at high
resolution. Place at:

    fastlane/metadata/android/en-US/images/icon.png

## phoneScreenshots/ — strongly recommended

3–8 PNGs, portrait orientation, typical sizes 1080×1920 or 1080×2400.
At minimum: catalog browse, reader page, settings. Ideally also: book
details, font/theme controls, the in-app licenses screen.

Filenames are lexicographic-sorted; prefix with a number to control
order:

    01_catalog.png
    02_reader.png
    03_reader-night-mode.png
    04_settings.png
    05_licenses.png

## tenInchScreenshots/ — optional

Tablet screenshots (1600×2560 or similar) for users browsing F-Droid on
a tablet. Same naming convention.

## featureGraphic.png — shipped

1024×500 PNG at `featureGraphic.png` in this directory. Shown at the
top of the F-Droid page; gives the app a visual identity beyond the
icon. Regenerate with the ImageMagick recipe documented in the PR
that introduced it (paper-tone bg `#F2EBDD`, icon left-thirds, wordmark
"Quire" + tagline in Helvetica).

## How F-Droid finds these

F-Droid's metadata scraper reads `fastlane/metadata/android/<locale>/`
on the configured branch / tag. Once you submit to fdroiddata, every
release tag picks up the latest assets in that directory.
