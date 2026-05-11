# Reader & Library feature pack

A bundle of five reader and library improvements shipped on a single branch
(`feat/reader-library-pack`), grouped into ordered, reviewable phases. Each
phase ends in a green test suite and is intended to land as one logical commit
so the branch is bisectable.

## Goals

1. **Fix the 99% bug.** A finished book must reach a finished state and stop
   appearing in Continue Reading. Today `percent` plateaus below `1.0` for
   many EPUBs, so books are never marked done.
2. **Keep the screen on while reading.** No setting; the reader screen
   suppresses dim/sleep for as long as it is foregrounded.
3. **Tap-to-page in the reader.** Left/right tap zones flip pages; center
   tap continues to toggle chrome. Toggleable in reader settings.
4. **Sort library.** Recently read / Recently added / Title / Author. User
   choice persists.
5. **Search library.** Local-only filter on title and author over the
   downloaded shelf.

## Non-goals

- No new server endpoints. The existing `POST/GET /progress` API is
  extended in-place with a nullable `finished_at` field.
- No "Finished" archive view, no Read/Unread filter, no bulk operations.
  Finished books stay inline in the Library with a small badge.
- No catalog-side changes. OPDS search already exists; library search is a
  separate, local-only surface.
- No new "completed" tombstone or restart-resurrects-finished logic
  beyond what `ProgressRepository.resetForDocument` already does.
- No animation or page-flip transition for tap-to-page; we delegate to
  Readium `goForward()` / `goBackward()`.

## Phasing

| Phase | Scope | Why this order |
|---|---|---|
| 1 | "Finished" concept — schema (Android + server) + tracker + sync DTO | Foundational; phase 4 depends on `finishedAt`. Touching DB first avoids re-migrating later. |
| 2 | Keep screen on | Tiny, isolated, no risk. Free win between heavier phases. |
| 3 | Tap zones + reader setting | Reader-side. Independent of library work. |
| 4 | Library sort + finished badge | Needs phase 1 (`finishedAt`). Pure UI/VM. |
| 5 | Library search | Independent. Last because most exploratory UX-wise. |

Each phase is one commit (or two — feat + test) with the test suite green
at every phase boundary.

---

## Phase 1 — "Finished" concept

### Schema

**Android (Room).** `ProgressEntity` and `core.model.Progress` add
`finishedAt: Long?` (nullable epoch ms). Database version `3 → 4`:

```kotlin
internal val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE progress ADD COLUMN finishedAt INTEGER")
    }
}
```

`exportSchema = true` is on, so the new schema JSON is regenerated and
checked in.

**Server (Postgres + SQLAlchemy).** `db.models.Progress` adds
`finished_at: Mapped[datetime | None]`. Alembic migration
`0002_progress_finished_at` adds a nullable `TIMESTAMP WITH TIME ZONE`
column. No backfill — `NULL` means "we never observed it finished."

### Sync DTOs

`ProgressItem` (push) and `ProgressPullItem` (pull) gain
`finished_at: datetime | None = None`. Default `None` keeps the wire
format backwards compatible: older clients omit the field, server
treats as `None`; older servers ignore it. Push handler stores the
incoming value as-is. Conflict resolution stays last-writer-wins by
`client_updated_at` — we do **not** add a per-field merge.

Implication: if a device pushes `finished_at = null` with a newer
`client_updated_at` than a finished record on the server, the finished
state is overwritten. This is intentional and matches existing
behaviour for `percent`. The only realistic path that produces this is
**Restart**, which is exactly the desired clearing semantics.

### Detection in `ProgressTracker`

Today the tracker computes `percent` from the locator and saves on
debounce. We add the lastSpineHref + the existing `finishedAt` as
attach-time inputs and compute `finishedAt` per save:

```kotlin
private fun computeFinishedAt(
    locator: Locator,
    existing: Long?,
    nowMs: Long,
    lastSpineHref: Url?,
): Long? {
    if (existing != null) return existing                       // sticky once set
    val total = locator.locations.totalProgression
    if (total != null && total >= 0.98) return nowMs            // threshold
    val prog = locator.locations.progression
    if (lastSpineHref != null
        && locator.href == lastSpineHref
        && prog != null && prog >= 0.99) return nowMs           // last-resource end
    return null
}
```

`attach(...)` signature becomes:

```kotlin
fun attach(
    documentId: Long,
    locatorUpdates: Flow<Locator>,
    lastSpineHref: Url?,
    initialFinishedAt: Long?,
)
```

`ReaderViewModel` resolves both at load: `lastSpineHref` from
`publication.readingOrder.lastOrNull()?.href`, `initialFinishedAt` from
the saved `Progress` row. Sticky semantics mean a single observation
across the threshold "wins" for life and resists later locator wobble.

### Clearing on restart

`ProgressRepository.resetForDocument(...)` already writes
`percent = 0.0, locator = ""`. Update it to also write
`finishedAt = null`. The next sync push then carries the cleared value
upstream via the LWW path described above.

### Library impact (preview, fully built in phase 4)

`LibraryRow.finishedAt` is exposed. `LibraryViewModel.continueReading`
filter changes from
`it.percent in 0.0001..0.9999` to
`it.percent > 0.0001 && it.finishedAt == null`.
This is the user-visible payoff of the bug fix.

### Tests (phase 1)

- `ProgressTrackerTest`: threshold trigger; last-spine trigger;
  pre-threshold no-trigger; sticky once set (later locator below
  threshold does not clear); restart clears via repo.
- Server `tests/unit/test_progress.py` (extend existing): round-trip
  `finished_at`; default `None`; LWW overwrites finished with
  unfinished when `client_updated_at` is newer.
- Android migration test: open a v3 database, run `MIGRATION_3_4`,
  assert `finishedAt` column exists with NULL default.

### Why threshold = 0.98

Readium reports `totalProgression` based on resource byte ratios. On
EPUBs whose last resource is a few percent of the total bytes, this
plateaus anywhere from 0.97–0.995. 0.98 is conservative enough not to
fire mid-book (a 98% read book is, in practice, finished) and
generous enough to fire even when the last spine item is a short
acknowledgments page. Combined with the sticky last-resource trigger
this covers both common failure modes.

---

## Phase 2 — Keep screen on

`ReaderScreen` adds a `DisposableEffect` that adds
`WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON` to the activity
window on enter and clears it on dispose:

```kotlin
val activity = LocalContext.current as Activity
DisposableEffect(activity) {
    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
}
```

No setting, no schema, no DataStore key. The flag is scoped to
`ReaderScreen`'s composition, so navigating to Library or Catalog
clears it and the system timeout resumes.

### Tests

No automated test. The visible behaviour ("screen does not dim while
reading") is verified by hand.

---

## Phase 3 — Tap zones for page turn

### Layout

Three full-height zones over the navigator:

| Zone | Width | Action |
|---|---|---|
| Left | 33% | `nav.goBackward()` |
| Center | 34% | `viewModel.toggleChrome()` (existing) |
| Right | 33% | `nav.goForward()` |

Swipe still works — Readium handles it inside the navigator and our
zones are pointer-input siblings, not consumers of the underlying
gesture.

### Wiring

The `EpubNavigatorFragment` is local to `ReaderContent` today. We lift
it into a `MutableState<EpubNavigatorFragment?>` remembered in
`ReaderScreen` and pass `onPrev` / `onNext` callbacks down. Calls go
through `ReaderViewModel.viewModelScope`:

```kotlin
fun pageForward() = viewModelScope.launch { fragment?.goForward() }
fun pageBackward() = viewModelScope.launch { fragment?.goBackward() }
```

### Setting

`ReaderPreferences` gains `tapNavigationEnabled: Boolean = true`.
Persisted via existing `ReaderPreferencesStore` (DataStore-backed).
Toggle lives in `FontSettingsSheet` as a `Switch` row beneath the
existing font controls. When `false`, only the center chrome zone is
hit-tested; left/right pass through to the navigator (so swipe still
works).

### Edge cases

- First page going back / last page going forward: navigator returns
  `false`, no-op. No toast — matches swipe behaviour.
- Chrome visible: tap zones still work. Left/right taps do **not**
  show or hide chrome — they just turn pages. Cleaner than coupling
  the two.

### Tests

- `ReaderPreferencesTest` (extend): round-trip `tapNavigationEnabled`.
- No Compose UI test — the navigator dependency would dominate the
  test setup. Verify by hand.

---

## Phase 4 — Library sort + finished badge

### Sort options

`enum class LibrarySort { RECENTLY_READ, RECENTLY_ADDED, TITLE, AUTHOR }`.

| Key | Order |
|---|---|
| `RECENTLY_READ` (default) | `progressUpdatedAt DESC`, then `title ASC` for never-read books |
| `RECENTLY_ADDED` | `Document.id DESC` (autoincrement = insertion order) |
| `TITLE` | `title ASC`, locale-aware |
| `AUTHOR` | `author ASC NULLS LAST`, then `title ASC` |

### Persistence

New `LibraryPreferencesStore` (DataStore-backed sibling of
`ReaderPreferencesStore`), single key `librarySort` with default
`RECENTLY_READ`. Kept separate from reader prefs so the two scopes
stay clean.

### Where the sort runs

In-memory inside `LibraryViewModel.rows`, after the `combine` that
builds the `LibraryRow` list. Library size is small (single-user
personal collection), so a Kotlin sort per change is cheap and avoids
SQL changes.

### UI surface

A small icon row sits above the grid (between the "Quire" title and
the grid content), containing two `IconButton`s: Sort and Search.

- **Sort icon** opens a `DropdownMenu` with the four options; the
  current one is shown checked.
- **Search icon** is wired in phase 5.

### Finished badge

- `LibraryRow.finishedAt: Long?` is exposed.
- The book cell renders a small checkmark `Icon` overlaid on the
  cover (top-right corner, 6dp inset, `tertiaryContainer` background
  circle). Checkmark only — no text — to keep dense covers readable.

### Continue Reading filter

Changes from `it.percent in 0.0001..0.9999` to
`it.percent > 0.0001 && it.finishedAt == null`. Books with progress
but no finishedAt continue to behave as before; finished books drop
out of Continue Reading entirely.

### Tests

`LibraryViewModelTest`:
- Each sort produces expected order.
- Finished books excluded from Continue Reading.
- Continue Reading prefers the most-recently-updated unfinished book.

---

## Phase 5 — Library search

### Surface

Tapping the Search icon (next to Sort, see phase 4) replaces the
"Library · N" section label with an inline `OutlinedTextField` and a
clear/close affordance. Closing restores the label.

### State

`LibraryViewModel` adds `query: MutableStateFlow<String>`. The
visible `items` becomes a three-way `combine(rows, sort, query)`:
sort applied first, then a case-insensitive `contains` filter on
`title` and `author`. Empty query = no filter.

### Behaviour

- Local-only. No catalog fallthrough, no "search catalog instead"
  affordance.
- Continue Reading is **not** filtered by the query — it is a
  separate UI element above the grid; filtering it would feel jarring
  while the user types.
- Empty filter result shows a small inline hint ("No matches in your
  library") inside the grid area; this is not a full empty-state
  takeover.
- Search state is in-memory only and does not persist across app
  restarts; users expect a clean shelf on cold launch.

### Tests

`LibraryViewModelTest`:
- Filters by title; by author; case-insensitive.
- Combined with each sort produces expected order.
- Empty result returns an empty list.
- Clearing the query restores the full sorted list.

---

## Phase 6 — Catalog refresh on credential change

### Problem

`CatalogViewModel.loadRoot()` calls `credentialStore.get()` once per
invocation. When the user fixes a wrong base URL in Settings and saves,
the Catalog VM is not notified — it keeps the previous (failed) state
until the process is killed and restarted.

### Approach

Two changes, layered:

1. **Observable credentials.** `CalibreCredentialStore` exposes a
   `flow: StateFlow<CalibreCredentials?>` alongside `get()`. An
   internal `MutableStateFlow` is updated on `put(...)` and `clear()`
   so subscribers see fresh values immediately. `get()` continues to
   work for synchronous read sites.
2. **Catalog auto-refresh.** `CatalogViewModel` subscribes to the
   flow in `init {}` and calls `loadRoot()` when the credentials
   change (with a `distinctUntilChanged`-on-baseUrl guard so saving
   the same URL twice doesn't trigger a redundant fetch).

### Pull-to-refresh

Added to the Catalog list as a manual escape hatch independent of the
credential change. Material 3 `PullToRefreshBox` wraps the lazy column
of OPDS entries; `onRefresh` calls `viewModel.refresh()`. This also
handles the orthogonal case of a flaky calibre-web instance where the
URL is fine but a single fetch failed.

### Tests

- `CalibreCredentialStoreTest` (new): the flow emits the current
  value on subscription, emits a new value after `put(...)`, emits
  null after `clear()`.
- `CatalogViewModelTest` (new or extend existing): when the store's
  flow emits a new baseUrl, the VM re-fetches.

### Out of scope

- No retry-with-backoff on auto-refresh failures (manual pull-to-refresh
  covers this).
- No multi-server account picker.

---

## Phase 7 — Sync re-attach for newly-downloaded books

### Problem

`SyncOrchestrator.applyPulled` silently drops progress rows whose
`DocumentIdentity` does not match a local `documents` row, then
advances the high-water mark anyway. When the user later downloads
that book, its server-side progress is unreachable without resetting
the sync state manually (Settings → Reset sync).

### Approach (simplest viable)

When a book download completes successfully — the moment a new
`documents` row is inserted via the catalog download flow — clear the
`progress` sync cursor by calling `syncStateDao.clearAll()` and
enqueue an expedited sync. The next pull starts from epoch 0 and
returns every progress row the user has, including the one belonging
to the newly-downloaded doc.

Cost is `O(server_progress_rows)` per download. For personal-scale
libraries this is dozens to low hundreds of rows — trivial. The
existing pull path already handles "doc not found" gracefully (early
return) so other rows in the same response are no-ops.

### Why not a per-doc server endpoint

A targeted `GET /progress/by-identity?metadata_id=...&content_hash=...`
would be more surgical, but it requires a server change, a new client
HTTP path, and conflict-free ordering with the existing pull cursor.
The reset-cursor approach is one line in the download path and
exercises the existing pull machinery.

### Why not a pending-progress side table

Storing dropped progress rows in a `pending_progress` table keyed by
identity, then materialising them when the matching doc is later
inserted, is more elegant — but it introduces a new table, a
migration, and cleanup semantics. Not worth it for the size of the
problem.

### Implementation point

The hook lives in `CatalogViewModel.download(...)` after the
`documents.upsert(...)` succeeds (the `runCatching { … }.onSuccess`
branch). Reset and trigger inside the same `viewModelScope.launch` so
the success state is only marked after the cursor is cleared.

### Tests

- `CatalogViewModelTest` (new or extend): on successful download, the
  sync state for `progress` is cleared.
- Integration-style test in `LibraryViewModelTest` style: server has
  a progress row for an identity, no local doc exists, download
  inserts the doc, `SyncOrchestrator.runOnce()` then attaches the
  progress to the new local row.

### Out of scope

- Cleanup of orphan progress rows on the server (a separate concern).
- Auto-reset on push failures (current LWW semantics handle this).

---

## Risks and trade-offs

- **Threshold tuning.** 0.98 is a guess informed by Readium's
  per-resource progression accounting. If real EPUBs in the user's
  library plateau lower, threshold becomes a one-line config; we'll
  fold any adjustment into the same phase.
- **Sticky finishedAt vs. accidental triggers.** Once set, the value
  doesn't clear from observation alone. The only path back to
  unfinished is Restart. This is the desired UX: a finished book
  doesn't un-finish if the user accidentally taps backward at the end.
- **Sync LWW for `finished_at`.** Last-writer-wins per row means a
  briefly-offline device pushing an older unfinished record could in
  theory overwrite finish state from another device. In practice
  finishedAt is set during a read session that produces newer
  `client_updated_at` values, so the stale-detection in the existing
  push handler covers this.
- **No animated page turn.** Tap-to-page calls Readium's go methods
  directly; visual transition is whatever Readium does, which is fine
  for an MVP.

## Out of scope (deferred)

- A "Finished" filter chip / archive view in the Library.
- Sort by progress percent.
- Search across catalog from the Library tab.
- Configurable tap zone widths or RTL inversion (Readium handles RTL
  page direction; tap geometry stays absolute for simplicity).
- Battery-aware automatic disable of "keep screen on."
