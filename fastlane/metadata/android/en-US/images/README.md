# F-Droid metadata images

These directories must contain real assets before submitting to F-Droid.
Until then, the listing has no graphics — F-Droid will accept the
submission but the page looks barren.

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

## featureGraphic.png — optional but improves listing

1024×500 PNG. Shown at the top of the F-Droid page; gives the app a
visual identity beyond the icon. Place at:

    fastlane/metadata/android/en-US/images/featureGraphic.png

## How F-Droid finds these

F-Droid's metadata scraper reads `fastlane/metadata/android/<locale>/`
on the configured branch / tag. Once you submit to fdroiddata, every
release tag picks up the latest assets in that directory.
