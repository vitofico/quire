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
