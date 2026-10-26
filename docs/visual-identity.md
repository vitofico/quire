# Quire visual identity

Marbled ink is the decorative motif. The canonical alpha mask lives in
`branding/marbled-ink.webp`; `python3 scripts/sync-brand-artwork.py` copies it to
Android and the server package. Use `--check` to detect stale exports.

Android tints the mask with the active Material theme's primary colour, including
Sage and Midnight. Welcome and empty-library screens give it space. On a populated
shelf, a cropped edge of the ink sits in the upper background and fades to the
plain surface at 128dp. It reserves no banner space and fades away as the header
scrolls off screen. The usual library header puts its title and actions in one 48dp row. At narrow widths or larger text,
measuring the title moves the actions below it, preserving all five 48dp targets.
Artwork is decorative, has no accessibility action, and never changes EPUB
reading colours.

Full-screen waits for the shelf, catalog, and publication opening share a small
marbled q, a progress indicator, and the existing translated loading label. They
use the app palette; opened publications keep their reader page colours. These
waits last only as long as the real work. Inline progress indicators stay compact.
Android's starting window also uses the canonical ink and q, contained within the
platform splash mask. On Android 12 and newer, saving the app mode also persists
its launch brightness with [`UiModeManager.setApplicationNightMode`](https://developer.android.com/develop/ui/views/theming/darktheme#change_themes_in-app);
System clears the per-app override.
The launch artwork uses a neutral ink, while saved Paper, Sage, Ink, or Midnight
takes over from the first Compose frame. An existing install needs to open once
to register this setting; Android 8 through 11 use the system launch brightness.
There is no minimum splash duration or startup hold. Changing the app mode may
recreate the Settings activity; the preferences are saved first.

The server status page embeds the same mask and tints it with its current accent.
Its warm paper and burgundy palette follows the browser's light or dark appearance.
The page remains self-contained, without scripts or remote assets. Its content
security policy permits only embedded image data for the artwork.

Native screenshots use a disposable emulator and synthetic books. These are
untouched PNG screenshots and frames extracted from native recordings, with no
added app delay.

- Paper shelf: [before](assets/identity-refinement/before-paper.png) and
  [after](assets/identity-refinement/after-paper.png).
- Ink shelf: [before](assets/identity-refinement/before-ink.png) and
  [after](assets/identity-refinement/after-ink.png).
- Publication loading: [Paper](assets/identity-refinement/loading-paper.png) and
  [Ink](assets/identity-refinement/loading-ink.png).
- Android launch: [light](assets/identity-refinement/launch-light.png) and
  [dark](assets/identity-refinement/launch-dark.png).

## Background style

Plain and Artwork are independent of the colour theme. Artwork is the initial
choice; either selection is saved locally and survives palette changes and restart.
Android exposes two previews in Settings. The server status page offers the same
choice with a local appearance cookie, retaining its script-free security policy.

`branding/background-ink.webp` is the canonical ambient ink artwork. The brand
sync script exports it alongside the existing welcome and empty-state motif.
It is a static, theme-tinted edge crop behind library, catalog and settings chrome,
at eleven percent intensity. Cards and navigation retain opaque surfaces. Plain
removes this ambient layer while keeping the existing welcome and empty artwork.
Neither style decorates EPUB prose or changes the reader's separate page colours.
