# Highlight regression EPUB

`selection.epub` is an original synthetic two-chapter EPUB used for native reader
regressions. It contains no private books, accounts, or credentials. It preserves
the validated selection fixture from commit `ab30e81672447a28629cac1e29d5bd60b4fc7a9e`.

Targets cover repeated quotes with different context, identical paragraphs,
identical sibling paragraph pairs under one body, multiline selections, and
cross-chapter restoration. Paragraph IDs identify expected rendered ranges for
test inspection; production selectors are generated structurally from the DOM.

This file is a test resource and is not packaged in the application. Its SHA-256 is
`b3b5a5cb218d1e858faf988db9d097749988befc763699b5b9a89ed5e65105a4`.

## Related pause and return fixture

The separate `app/src/test/resources/reader-pause.epub` exercises memory bookmarks,
nested stopping boundaries and font/viewport restoration. Regenerate it from the
repository root with:

```sh
python3 scripts/make-reader-pause-fixture.py app/src/test/resources/reader-pause.epub
```

Use the [pause acceptance steps](../../../../../docs/reader-pause.md) to check
bookmark return alongside highlight-list return and Copy as quote. Memory notes
remain device-local; the highlight sync fixture and contracts are separate.
The [development guide](../../../../../docs/development.md) describes the
structural-anchor browser regressions and Android checks.
