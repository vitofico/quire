# Highlights and quotes

Highlights work offline with local EPUBs, OPDS downloads and books from a Quire
Server. They are stored on this device; they do not sync through Quire Server yet.

Tap the centre of a reading page to show the controls, then choose **Highlight**.
The title changes to **Highlight mode** and **Done** stays available. Long-press
some text, adjust the selection handles and choose **Highlight** in the selection
menu. Choose Yellow, Green or Pink to save the passage.

In Highlight mode, tap a saved highlight to change its colour or remove it.
Swiping still turns pages. Page taps belong to the book instead of turning pages
or hiding controls. Choose **Done** to return to normal reading. Back first closes
the colour picker or active text selection, then exits Highlight mode before
leaving the book. Outside this mode, highlights remain visible and page taps
behave as usual.

For **Copy as quote**, long-press text and choose that action in the selection
menu. It works in normal reading mode too, and copies the selected passage with
the title and author, when available. The ordinary Copy and Select all actions
also remain available. Copying a quote does not save a highlight or contact a server.

## Browse saved passages

Choose **More → Highlights** in the reader controls to see this book's saved passages.
The list follows reading order and shows each passage's colour and chapter when
available. Tap a passage to return to the page containing its start. **Back**
returns to reading without choosing a passage and remains available while a jump
is in progress.

Jump-to-highlight supports horizontal text in reflowable EPUBs; quotations from
fixed-layout EPUBs or other writing modes remain readable and selectable in the list.

The list shows the saved quotation independently of its location. Long quotations
can be expanded with **Show full quote**, and the text can be selected and copied.
If a location is unavailable in this edition, the quotation stays readable in the
list. Quire does not guess a different occurrence of repeated text.

Removing a downloaded book, including **Remove all books**, keeps its highlights.
Downloading or importing the same book again restores them through its library
identity. If an edition's chapter content changes, Quire keeps the saved quotation
but does not guess a new location. A message explains when highlights cannot be
shown in that edition. The list remains local to the current book; cross-book
search and syncing highlights are separate follow-up work.

## Local storage and rendering

Room schema 10 adds the `highlights` table. Its device-generated UUIDs, separate
selected-text and locator columns, book metadata snapshots, update timestamps and
soft-deletion tombstones prepare it for a later sync protocol. It has no foreign
key to `documents`: deleting a download cannot cascade into annotation deletion.
Lookup uses the existing normalized metadata identifier or content hash.

The reader captures exact DOM endpoints and chapter content fingerprints in the
locator JSON before closing the native selection menu. The Readium 3.0 adapter
consumes those endpoints instead of matching repeated text by quote context.
Missing, malformed or stale ranges fail closed, preserving the stored quote.
This rendering adapter depends on the pinned toolkit's decoration-group shape;
toolkit upgrades must rerun the structural-range regression tests.

Restoration waits for the current resource to be ready and serializes decoration
updates. UUID-based additions and removals are idempotent. Only the prepared
current chapter is submitted; entering another chapter restores its own saved
annotations. The reader's existing resize anchoring and page margins remain
responsible for reading position.

## Browser regression tests

With Node.js 20 or newer, run the production adapter's DOM regressions from the
repository root:

```sh
npm ci --prefix scripts
scripts/node_modules/.bin/playwright install chromium
npm test --prefix scripts
```

Android CI runs these tests with the locked Playwright version and installs
Chromium's Linux dependencies using `playwright install --with-deps chromium`.
To use an existing compatible Chromium locally, set `QUIRE_CHROMIUM_EXECUTABLE`
to its executable path when running the tests. These npm dependencies only serve
the tests and are not included in the Android app.
