<p align="center">
  <img src="docs/assets/readme-cover.svg" alt="Quire's italic q surrounded by marbled ink." width="100%">
</p>

<h1 align="center">Quire</h1>

<p align="center">
  <strong>Lose yourself. Keep your place.</strong>
</p>

<p align="center">
  An EPUB reader for Android and the web, for the library you keep.<br>
  Import your books, browse OPDS catalogs, and sync your place through a server you own.<br>
  No telemetry. Optional AI, off by default.
</p>

<p align="center">
  <a href="https://github.com/vitofico/quire/actions/workflows/android-ci.yaml"><img src="https://github.com/vitofico/quire/actions/workflows/android-ci.yaml/badge.svg" alt="Android CI"></a>
  <a href="https://github.com/vitofico/quire/actions/workflows/server-ci.yaml"><img src="https://github.com/vitofico/quire/actions/workflows/server-ci.yaml/badge.svg" alt="Server CI"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/app-Apache_2.0-blue.svg" alt="App license: Apache 2.0"></a>
  <a href="server/LICENSE"><img src="https://img.shields.io/badge/server-AGPL--3.0--only-blue.svg" alt="Server license: AGPL-3.0-only"></a>
</p>

<p align="center">
  <a href="#install">Install</a> ·
  <a href="#bring-your-books">Bring your books</a> ·
  <a href="server/README.md">Set up a server</a> ·
  <a href="#privacy">Privacy</a>
</p>

<p align="center">
  <a href="docs/assets/readme-library-sage.webp"><img src="docs/assets/readme-library-sage.webp" alt="Quire's Sage library with five real imported EPUB covers" width="260"></a>
  &nbsp;&nbsp;
  <a href="docs/assets/readme-library-midnight.webp"><img src="docs/assets/readme-library-midnight.webp" alt="The same Quire library in Midnight with a Plain background" width="260"></a>
  &nbsp;&nbsp;
  <a href="docs/assets/readme-reader.png"><img src="docs/assets/readme-reader.png" alt="Reading a real Sherlock Holmes EPUB in Quire, with the reader's separate page styling" width="260"></a>
</p>

<p align="center"><em>Your shelf, your palette, your reading page. Open a screenshot for the full-resolution view.</em></p>

The library and reader examples use actual EPUBs retrieved read-only from a CWA
library and imported on a disposable emulator. No book files, server address or
credentials are included. See the [screenshot notes](docs/assets/README.md).

## Made for reading

- **Read offline.** Import EPUBs from your device or download them from an
  OPDS catalog, then keep reading without a connection. Long-press a book or
  open **More** in the reader and choose **Save EPUB as…** to put a copy of the
  file anywhere Android's file picker can write, such as local storage or a
  cloud drive app.
- **Make the page comfortable.** Adjust the typeface, text size, line spacing
  and page theme. The reader's bars and menus take the page's colours.
- **Keep passages.** Enter Highlight mode to save coloured passages, change
  their colour or remove them. Open **More → Highlights** to browse the book's saved passages
  and return to their exact pages. From a text selection, Copy as quote includes
  the book title and author. [Highlights work offline and sync with Quire Server](docs/reader-highlights.md),
  including when you remove a download and later import the same book again.
  Quire Server also offers [read-only Agent/MCP access](docs/agent-access.md) to
  your synced highlights and library; device-local memory bookmarks are never exposed.
- **Leave yourself a way back.** Open **More → Pause & resume** in the reader
  to save a memory bookmark with an optional sentence or question and nearby
  passage context. Return explicitly, edit the note, replace its location or
  remove it. It stays on this device, scoped to the book and account/catalog;
  local-only reading has its own scope. Removing the download removes this
  bookmark, while highlights remain separate.
- **Find a place to stop.** The same sheet shows the approximate percentage of
  the whole book ahead to the next mapped section, chapter/resource or book end.
  It is a coarse distance, never a minutes or reading-pace estimate; insufficient
  publication structure produces an unavailable message. See
  [Pause and return](docs/reader-pause.md) for the boundaries and controls.
- **Keep a useful shelf.** Search, sort, continue reading, and filter finished
  or abandoned books.
- **See how you read.** Open **More → Reading statistics** in the library for
  reading time today, this week, this month and overall, streaks, books
  finished, a chart by day or month, the hours and weekdays you read most, your
  speed in words per minute with a weekly trend, and per book the sessions and
  roughly how long is left at your pace. With Quire Server the same screen
  shows your library's counts of books, finished, reading and abandoned, with
  top authors and themes. Recording is on by default and can be switched off
  in Settings. The [statistics](docs/reading-statistics.md) stay on the device
  and in your phone's own backup, never on Quire Server or with AI.
- **Know what is in a book.** Tap its info icon or long-press it for the
  description, series, publisher, language, subjects, ISBN, progress and file size.
  Imported EPUB metadata stays with the book. With AI on, **Improve details**
  proposes tidier details and a new cover for you to accept field by field
  (see [AI, on your terms](#ai-on-your-terms)).
- **Hear from Quire only if you ask.** Under **Settings → Notifications and
  quiet**, switch on a notification when a saved catalog has new books, a
  daily reading reminder at the time and on the days you choose, or Do Not
  Disturb (priority only) while a book is open. All three are off until you
  turn them on. See [Notifications and quiet](docs/notifications.md).

Under **Settings → Appearance**, follow the system or stay light or dark.
Choose Paper or Sage for light mode, and Ink or Midnight for dark mode.
Choose **Artwork** for the ink background or **Plain** for solid surfaces,
independently of the palette. These choices are saved for your next launch.
App palettes are separate from the page theme inside a book: tap the page and
open **Aa** to adjust the font, spacing, margins and page colours.

Open **Settings → What’s new** for recent reader features and where to find them.

<details>
<summary>See the reader, marbled loading and both library palettes</summary>

The same ink appears during real loading work, without an added waiting period.
The new library screenshots show five imported books. The reader shows an
actual Sherlock Holmes page in Sepia and Literata. Open a full-size example:
[Sage library](docs/assets/readme-library-sage.webp),
[Midnight library](docs/assets/readme-library-midnight.webp),
[Reader page](docs/assets/readme-reader.png),
[Paper loading](docs/assets/identity-refinement/loading-paper.png), or
[Ink loading](docs/assets/identity-refinement/loading-ink.png).

<p align="center">
  <img src="docs/assets/readme-reader.png" alt="An EPUB page in Quire, with adjustable typography and a separate reader page theme." width="260">
  &nbsp;
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/identity-refinement/loading-ink.png">
    <img src="docs/assets/identity-refinement/loading-paper.png" alt="A real Quire loading state: the italic q amid theme-tinted marbled ink, a progress indicator and loading status." width="260">
  </picture>
  &nbsp;
  <a href="docs/assets/readme-reader-preferences.png"><img src="docs/assets/readme-reader-preferences.png" alt="Reader preferences: separate Light, Dark, Sepia and Dark sepia page themes and font families." width="260"></a>
</p>

</details>

## Read in your browser

Open `/quire/` on an installation of Quire Server that includes the web client. A server with a hostname of its own can serve the reader at the root instead (`QUIRE_SERVER_WEB_AT_ROOT`), and old `/quire/` links keep working. Choose **Import EPUB** to read a local book (**Import EPUB or PDF** when the server converts PDFs), or connect an OPDS feed from **Catalog**. The library and reader work offline after the browser caches the app and the book. Browser storage belongs to the current browser profile.

Sign in under **Settings → Account** to sync your shelf, progress and highlighted passages with Android. Books imported on another device appear with **Open your copy**, and show their cover once you have signed in to the catalog they came from. Select the same EPUB or download it from the catalog to resume reading. The private pause bookmark stays on the device where you saved it. [Browser setup and deployment](server/README.md)

## Bring your books

- **On this device:** import EPUB files and keep your reading place locally.
  No server is required.
- **From an OPDS catalog:** browse, search and download from an OPDS 1.x feed,
  including [calibre-web] and [Kavita]. Give Quire the full catalog feed URL for
  a generic OPDS connection. This mode keeps reading state on the device;
  server sync, the library mirror and AI features stay off.
- **From a free catalog:** under **Open catalogs**, add a free, legal
  catalog of public-domain books in one tap, with those in your language
  first. The built-in list currently holds Spanish and French catalogs.
  Quire offers them in Settings → Catalogs, on an empty Catalog tab and in
  Quire Web. The device, or on the web your Quire server, then contacts that
  catalog's site.
- **Across devices:** add a compatible **Quire Server** to sync reading state
  and your per-user library mirror. The calibre-web integration keeps
  calibre-web as the source of books and Quire Server as the source of reading
  state. Onboarding supports calibre-web credentials and compatible Quire
  Server account sign-in, according to the server you run.
- **From a PDF:** share a PDF with Quire on Android, or add one with **+** in
  the library or in Quire Web, and a Quire Server with conversion turned on
  turns it into an EPUB in your library. Follow it under **More → PDF
  conversions** on Android. See [Turn a PDF into an EPUB](#turn-a-pdf-into-an-epub).

On first launch, **Skip — use offline only** opens the local library. Tap **+**
to import an EPUB with Android's file picker; opening an EPUB shared by another
app also imports it. **Connect to my server** sets up remote browsing and
compatible server sign-in. If your Quire sync server has a separate address,
enter its optional URL in Settings.

### One Quire account, on every device

A Quire account keeps sync working when the catalog is offline, and it is
separate from your Calibre-Web login.

- **Move an existing Calibre-Web login yourself.** Where the server allows it,
  the app offers **Create your Quire account** once: choose an email and a
  password, and your library, progress and highlights move with you. Quire Web
  has the same step under **Settings → Account**.
- **Add a device without typing a password.** On a signed-in phone open
  **Settings → Quire account → Add another device**, then scan the code from
  the other one with **Scan pairing code**.
- **Follow the server when it moves.** If the operator gives Quire Server a new
  address (`QUIRE_SERVER_CANONICAL_URL`), a signed-in app switches to it on its
  next launch and keeps its local bookmarks and highlights.

The [migration guide](docs/independent-login-migration.md) has the reader and
operator steps.

## Turn a PDF into an EPUB

`converter/` holds `quire-convert`, a command-line tool that turns a text PDF
into a reflowable EPUB and tells you how much to trust the result. It compares
the EPUB with the PDF word by word and region by region, so lost figures,
dropped notes, missing or reordered text and doubtful metadata end up in a
report instead of going unnoticed.

```bash
cd converter && uv sync
./scripts/fetch-epubcheck.sh
uv run quire-convert inspect book.pdf
uv run quire-convert convert book.pdf --out book.epub --report report.json --html-report report.html
```

The exit code is the verdict: `0` ready, `2` needs review, `3` unsupported
(scans, multi-column or right-to-left layouts, equations), `1` failed.
`quire-convert deliver` saves a converted book to Calibre-Web. The tool needs
Python 3.12, Calibre's `ebook-convert`, the Poppler utilities and Java 17 for
EPUBCheck.

The apps use the same converter through Quire Server. The server queues the
uploaded PDF and a separate worker container (`ghcr.io/vitofico/quire-convert`,
no network access) converts it; the app then downloads the EPUB and lists any
pages that need a look. No model is used and nothing leaves the server, so it
works with AI switched off. Conversion is off by default: the
[PDF conversion setup](docs/configuration.md#pdf-conversion) turns it on.

Quire is the native Kotlin / Compose / Readium client. Quire Server is an
optional FastAPI service backed by Postgres, available as
`ghcr.io/vitofico/quire-server`. The [server guide](server/README.md) covers
self-hosting; the [architecture](docs/architecture.md) covers how the parts fit.

## AI, on your terms

AI is **off by default** and each user **opts in**. A server administrator
chooses an OpenAI-compatible endpoint: a local model through Ollama,
llama.cpp or vLLM, or a provider such as OpenAI or OpenRouter. Choosing a
provider means requests go to that provider; self-hosting Quire Server does
not make a remote model local.

With AI enabled and opted into, Quire can provide:

- **Book and catalog insights:** author context, series placement, themes,
  craft notes, comparisons, discussion prompts and content advisories, with
  citations from Wikipedia and OpenLibrary. Preview a catalog book before
  downloading it; cached insights carry over to the owned book.
- **A reader profile:** an on-demand narrative based on finished books, with
  suggestions from your library, OpenLibrary discovery and the model.
- **Your preferred voice:** adjustable tone and language, plus regeneration
  with feedback. Shared instance caches avoid repeating the same generation.
- **Better book details and covers:** when a title is really a file name, or
  a book lacks a cover or description, the server looks it up on Open Library
  and the model tidies the title, authors, series and description. **Improve
  details** shows each proposed change beside the current one, so you keep
  only what you tick. For the cover, keep the book's own, find one (from Open
  Library when it has one, else drawn by Quire), use Quire's drawn cover, or
  ask for a painted one when the administrator has set up an image provider.
  On Android, books with missing details are also looked up in the
  background on Wi-Fi, up to 25 a day, and can go back to their original
  details.
- **Think through your highlights:** choose **Select** in Highlights (on
  Android, a book's **More → Highlights**; in Quire Web, across books), tick a
  few passages and ask a question about them. The short answer names the
  passages it used, takes a few follow-ups, and leaves as a plain-text note
  through Share or Copy. Nothing is saved, and only the selected passages, your
  notes on them (optional) and the books' titles and authors are sent.

ISBN scanning also looks up a physical book through OpenLibrary. A compatible
Quire Server can return an already-owned check and a deterministic reader-affinity
score based on your library, **without an LLM call**. A generated insight is
an optional follow-up. ISBN metadata and cover lookups are separate from AI opt-in.

You can also bring your own assistant. Quire Server's read-only MCP endpoint
(`/mcp`) gives an MCP client seven tools on your account: `list_highlights`,
`search_highlights`, `get_highlight`, `get_highlights`,
`list_highlighted_books`, `list_library` and `get_book`. They read highlights,
your shelf with reading progress and tidied book details; none can change
anything, and the server makes no model call for them. See
[Agent access](docs/agent-access.md).

Administrators can set per-user budgets and run sync-only, AI-only or full
server deployments. See [AI configuration](docs/configuration.md) and the
[server guide](server/README.md) for the supported setup and privacy boundaries.

## Privacy

**No telemetry, analytics or crash reporting.**

- Catalog and sync requests go to your configured catalog and Quire Server.
  Book downloads and covers can use the hosts linked by an OPDS feed.
- When you scan or enter an ISBN, the Android app contacts **OpenLibrary** for
  metadata and may fetch its cover from `covers.openlibrary.org`. These lookups
  can happen without AI opt-in.
- When AI is enabled and you opt in, Quire Server contacts the configured
  model endpoint and the public Wikipedia and OpenLibrary APIs for grounding
  and discovery. The endpoint may be local or a third-party provider chosen
  by your administrator. Improving book details sends the book's details to
  that endpoint; a painted cover sends the title, a few subjects and the start
  of the description to the image provider the administrator configured.
- PDF conversion happens on your Quire Server; the PDF goes nowhere else.
- Reading statistics stay on the device and in its own Android backup.
- Memory bookmarks and their notes stay in the device database, separate from
  reading-progress and highlight sync. They make no AI calls or book uploads.
- Stored credentials are encrypted with keys protected by **Android Keystore**,
  hardware-backed where the device supports it.

## Install

Quire runs on **Android 8.0 or newer**. Get it from [F-Droid], add the
[GitHub repository to Obtainium][Obtainium-add], or download an APK from
[Releases]. Obtainium follows GitHub Releases; F-Droid publishes on its own
schedule and may offer an earlier version. Its published developer-signed
releases are [verified against the source][F-Droid].

<a href="https://f-droid.org/packages/io.theficos.quire/">
  <img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" alt="Get it on F-Droid" height="80">
</a>
<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/vitofico/quire">
  <img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="80">
</a>

On first launch, connect a server or catalog, or continue with local EPUBs.
For historical Play Protect installation reports, see
[#108](https://github.com/vitofico/quire/issues/108).

To sync across devices, follow [`server/README.md`](server/README.md).
It includes Docker Compose setups for an existing reverse proxy or a full
Caddy-fronted stack with calibre-web, Quire Server and TLS. Ready-to-use
configuration examples live in [`docs/configuration.md`](docs/configuration.md).

## Why Quire

Quire started with a simple need: read an OPDS library on Android and sync
reading progress to a server under the reader's control. The app works on its
own; the companion server adds shared reading state and optional insights.

A *quire* /ˈkwaɪər/ is a gathering of folded leaves, bound into a book.

## Build from source

```sh
# Build the debug APK using the project's Docker-based Gradle wrapper.
# Host JDK and Android SDK are not required.
scripts/dgradle :app:assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

Sync server development:

```sh
cd server
uv venv && source .venv/bin/activate
uv pip install -e ".[dev]"
uv run pytest                                  # spins up Postgres in Docker
QUIRE_SERVER_CWA_BASE_URL=https://library.example.com \
  uv run uvicorn quire_server.main:app --reload   # http://localhost:8000
```

## Repo layout

```
app/                  Android entry point — Compose UI, navigation, DI wiring
auth/                 Encrypted credentials for catalog and server accounts
core/identity/        Document identity: hash + dc:identifier normalization
core/model/           Domain types (Document, Progress, Bookmark)
data/local/           Room database, DAOs
core/metadata/        EPUB metadata, cover and ISBN extraction
data/ai/              quire-server AI and book details client
data/opds/            OPDS 1.x catalog client
data/sync/            quire-server REST client + WorkManager job
data/library/         quire-server library mirror, stats and PDF conversion client
reader/               Readium navigator integration
server/               quire-server (Python / FastAPI)
web/                  Quire Web (React), shipped inside the server image
converter/            quire-convert: checked PDF to EPUB conversion (CLI and server worker)
catalogs/             Built-in list of open OPDS catalogs
docs/                 Architecture, development, sync API reference
scripts/dgradle       Gradle wrapper that runs inside the project's Docker image
Dockerfile            Reproducible Android build environment (linux/amd64)
```

## Documentation

- [Migrate from Calibre-Web login to a Quire account](docs/independent-login-migration.md): keep your reading history and continue downloading books from Calibre-Web.
- [`docs/architecture.md`](docs/architecture.md): components, document
  identity, sync model, conflict resolution, auth.
- [`docs/development.md`](docs/development.md): module layout, build
  commands, testing, CI, releases.
- [`docs/sync-api.md`](docs/sync-api.md): REST surface of Quire Server.
- [Reader highlights](docs/reader-highlights.md) and
  [Pause and return](docs/reader-pause.md): separate saved-passage workflows.
- [Agent access](docs/agent-access.md): read-only highlight and library MCP
  setup, plus the highlight REST API.
- [Reading statistics](docs/reading-statistics.md) and
  [Notifications and quiet](docs/notifications.md): what Android records and
  when it notifies.

## Make Quire speak your language

English is the default; Spanish, Italian, French and German are included.
Translations are complete Android resource folders, with automatic language
discovery and completeness checks. Adding a language needs only XML files.
The [translation guide](docs/translating.md) covers corrections, the
`scripts/new-translation` helper and the shared glossary. Native speakers are
welcome to contribute and review the wording.

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md). TL;DR: gitmoji + conventional
commits, `scripts/dgradle test` and `cd server && uv run pytest` must
pass, no telemetry / analytics PRs. External contributors also need to
sign the [Quire CLA](CLA.md) on the PR before it can be merged; see
CONTRIBUTING.md for the one-line comment.

## Security

If you find a vulnerability, please follow [`SECURITY.md`](SECURITY.md)
rather than opening a public issue.

## License

The Android app is licensed under Apache-2.0: see [`LICENSE`](LICENSE) and
[`NOTICE`](NOTICE). Quire Server, everything under [`server/`](server/), is
licensed under AGPL-3.0-only: see [`server/LICENSE`](server/LICENSE). Server
code published before 2026-10-20 remains available under Apache-2.0, the
license it was released with. Contributions are accepted under the
[contributor agreement](CLA.md).

## Support

If Quire is useful to you and you'd like to chip in, you can buy me a coffee:

[![Ko-fi](https://img.shields.io/badge/Ko--fi-Support-FF5E5B?logo=ko-fi&logoColor=white)](https://ko-fi.com/vito507767)

[calibre-web]: https://github.com/janeczku/calibre-web
[Releases]: https://github.com/vitofico/quire/releases
[F-Droid]: https://f-droid.org/packages/io.theficos.quire/
[Obtainium-add]: https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/vitofico/quire
[Kavita]: https://www.kavitareader.com/
