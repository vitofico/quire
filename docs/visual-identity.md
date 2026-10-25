# Quire visual identity

Marbled ink is the decorative motif. The canonical alpha mask lives in
`branding/marbled-ink.webp`; `python3 scripts/sync-brand-artwork.py` copies it to
Android and the server package. Use `--check` to detect stale exports.

Android tints the mask with the active Material theme's primary colour, including
Sage and Midnight. Welcome and empty-library screens give it space. The library
header gives a recognisable crop its own band above the controls, with stronger
ink on light paper and softer ink on dark surfaces. A surface-coloured fade
protects the title. Artwork is decorative, has no accessibility action, and never
covers book prose or changes EPUB reading colours.

The server status page embeds the same mask and tints it with its current accent.
Its warm paper and burgundy palette follows the browser's light or dark appearance.
The page remains self-contained, without scripts or remote assets. Its content
security policy permits only embedded image data for the artwork.

Native screenshots with a synthetic shelf: [before](assets/library-header-before-paper.png),
[Paper](assets/library-header-paper.png) and [Ink](assets/library-header-ink.png).
