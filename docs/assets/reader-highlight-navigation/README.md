# Highlights list screenshots

Captured on the task's Android API 36 emulator using the original synthetic EPUB
at `reader/src/test/resources/highlights/selection.epub`.

- `before-reader.png`: PR 1 reader with controls hidden and the existing Pink
  Copper highlight. The installed baseline APK SHA-256 was
  `a3921e8c1c2a37c111552290db22cc333b1b587d74c5e7109598070c10f8a955`.
- `reader-menu.png`: PR 2 reader More menu, reached with physical native taps.
- `highlights-list.png`: the resulting current-book list, showing the original
  native Copper annotation and excluding its two deleted peers. System Back and
  tapping the Copper quote were verified against the same mounted reader/page.

The PR 2 candidate APK SHA-256 was
`70d7e70dfff6f43351d151445eedb59e6f5ff8b5289f06f564d872287a4ec046`.
These screenshots contain no seeded quote rows, personal books or credentials.
Later programmatic fixture cases are separate QA evidence.

These are historical highlight-feature screenshots. The integrated reader now
adds **Pause & resume** to this existing More menu, with a filled bookmark icon
when a device-local memory bookmark exists; the direct Highlight/Done and type
controls retain their positions. Integrated native acceptance must capture the
updated menu and exercise both kinds of saved-location return before these
historical images are treated as evidence for the combined reader.
