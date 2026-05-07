# Reset sync, restart book, remove all local books

Three small, related operations that together let the user recover from sync
weirdness, free up space, and reread a book without fighting the LWW model.

## Goals

1. **Reset sync cursor.** Clear the local pull high-water mark so the next sync
   re-pulls everything from the server. Local books and progress are kept.
2. **Restart book.** Per-book "mark as unread / read again" that propagates
   correctly across devices via the existing sync model. Optionally delete the
   downloaded EPUB at the same time.
3. **Remove all downloaded books.** Wipe the local library — `documents` rows
   and EPUB files on disk. Server-side progress is preserved and re-applies if
   the user re-downloads.

## Non-goals

- No new sync-server endpoints. All three operations work with the existing
  POST/GET progress API.
- No tombstones for progress. (Restart writes `percent=0`; it does not delete.)
- No bulk "restart all" — only per-book and "wipe local library."
- No credential clearing — Settings already handles calibre-web creds.

## Architecture summary

| Op | Touches | Server-visible? |
|---|---|---|
| Reset sync | `sync_state` table | No (next sync just pulls from epoch 0) |
| Restart book | `progress` row (push reset), optionally `documents` + file | Yes — pushes a `percent=0, locator=""` row, LWW propagates |
| Remove all local books | `documents` (cascades `progress`), `filesDir/books/` | No |

All three are local-only operations except for the push that Restart triggers
through the normal `SyncOrchestrator` path. No new wire format, no schema
migration.

## 1. Reset sync

### UI

Settings → new section **"Storage & sync"**, placed below the existing **Sync**
card. One row:

- Title: **Reset sync**
- Subtitle: "Re-pull everything on the next sync. Your books and progress are
  kept."
- Tap → confirmation dialog with the same copy + Cancel / Reset buttons.

### Behavior

On confirm:

1. `SyncStateDao.clearAll()` — `DELETE FROM sync_state`.
2. Trigger an expedited sync via `SyncEnqueuer.enqueue(context, expedited = true, replaceExisting = true)`.

The next pull starts from `since = 0`, so the server returns every progress row
the user has. `applyPulled` already handles "doc not found locally" gracefully
(early return).

### Code

- `data:local`
  - `SyncStateDao.clearAll()` — `@Query("DELETE FROM sync_state")`.
- `app`
  - `SettingsViewModel.resetSync(context)` — calls the DAO, then enqueues sync.
  - `SettingsScreen` — new card with the button + `AlertDialog`.

## 2. Restart book

### UI

Library card already has a long-press handler that opens a "Delete" dialog
(`LibraryScreen.kt:86,108`). Replace it with a **bottom-sheet menu** offering
two actions:

- **Restart book** — opens the restart confirmation dialog.
- **Delete from library** — opens the existing delete confirmation dialog (no
  change to its behavior).

The restart confirmation dialog:

- Title: "Restart book?"
- Body: "\"<title>\" will be marked as unread and synced to your other devices."
- Checkbox: **Also delete the downloaded copy** (default: off)
- Buttons: Cancel / Restart

### Behavior

The naive ordering ("delete file, then push reset") doesn't work: the FK
cascade from `documents` → `progress` removes the dirty row before the worker
can pick it up, so the server keeps the user's old position. We push
synchronously first, then delete the file.

On confirm, in this order:

1. Upsert a reset progress row for the document (dirty):
   - `locator = ""`
   - `percent = 0.0`
   - `updatedAt = now`
   - `localUpdatedAt = now`
   - `syncedAt = 0`
2. Call `SyncOrchestrator.runOnce()` inline (not via WorkManager) so the push
   completes before we touch the file.
3. **On push success** (`SyncResult.Success`):
   - If "delete downloaded" was checked: `documentRepo.delete(doc)`.
   - Else: nothing else to do; the reset is already on the server.
4. **On push failure** (network, 401, HTTP error):
   - File is **not** deleted, even if the checkbox was on.
   - The dirty row stays in place; `SyncEnqueuer.enqueue(...)` is called so
     WorkManager retries on reconnect.
   - Snackbar: "Couldn't sync restart — will retry."

`SyncOrchestrator` is reachable from the library ViewModel via DI — today
it's only invoked from `SyncWorker`, so we add it as a constructor dependency
on `LibraryViewModel` and wire it in `AppContainer`.

### Code

- `data:local`
  - `ProgressRepository.resetForDocument(documentId, now)` — upserts the reset
    row with the dirty markers above.
- `data:sync`
  - No new code. `SyncOrchestrator.runOnce()` is already public.
- `app`
  - `LibraryViewModel.restart(doc, alsoDeleteFile)` — orchestrates the four
    steps, exposes a `Snackbar` event flow for failures.
  - `LibraryScreen` — bottom-sheet menu, restart dialog with checkbox.
  - DI: pass `SyncOrchestrator` into `LibraryViewModel` (already constructed
    in `AppContainer`).

### What other devices see

After a successful push, the server stores `percent=0, locator=""` for that
document with the new `updatedAt`. The next pull on any device:

- Local progress with `localUpdatedAt < incoming.updatedAt` → upsert wins.
- The reader on that device opens the book → `parseOrNull("")` returns null →
  Readium starts at the spine's first item. Exactly the desired UX.

### What about the file?

Leaving the EPUB on device is the default and is the cheap path — the user
just wants to reread, the file is fine. The "also delete" option is for when
the user wants to free space and re-download fresh; we honor it after a
confirmed successful push.

## 3. Remove all downloaded books

### UI

Same Settings section as Reset sync. Second row, styled destructive (error
color):

- Title: **Remove all downloaded books**
- Subtitle: "Delete all EPUB files from this device. Reading progress is kept
  on the server."
- Tap → confirmation dialog: "Delete all downloaded books from this device?
  Reading progress is preserved on the server and will sync back if you
  re-download." Cancel / Remove all.

### Behavior

On confirm:

1. `DocumentRepository.deleteAll()` — `DELETE FROM documents`. FK cascade
   removes the `progress` rows.
2. Recursively delete the contents of `filesDir/books/` (the directory itself
   stays). Best-effort, same pattern as the existing per-doc delete.
3. Do **not** clear `sync_state`. The cursor stays where it is. The next pull
   returns rows whose documents no longer exist locally; `applyPulled` already
   skips those.

### Code

- `data:local`
  - `DocumentDao.deleteAll()` — `@Query("DELETE FROM documents")`.
  - `DocumentRepository.deleteAll(booksDir: File)` — calls the DAO, then
     wipes the directory contents (`booksDir.listFiles()?.forEach { it.deleteRecursively() }`).
- `app`
  - `SettingsViewModel.removeAllBooks()` — calls the repo. Needs `booksDir`
    injected (already configured in `AppContainer`).
  - `SettingsScreen` — second button in the new section.

## Error handling

| Op | Failure mode | Behavior |
|---|---|---|
| Reset sync | DB write fails | Snackbar: "Couldn't reset sync." Cursor unchanged. |
| Restart book — push | Network/401 | Snackbar: "Couldn't sync restart — will retry." Dirty row stays; WorkManager retries on reconnect. File **not** deleted even if checkbox was on. |
| Restart book — file delete | I/O failure | Already best-effort in `DocumentRepository.delete` — DB is source of truth. |
| Remove all | Partial DB / file delete | DB delete is the source of truth. File-system errors are ignored (matches existing per-doc delete pattern). |

## Testing

Unit tests, in `data:local` and `app`:

- `SyncStateDao.clearAll` removes all rows.
- `DocumentDao.deleteAll` removes documents and cascades progress.
- `ProgressRepository.resetForDocument` writes the expected dirty row.
- `LibraryViewModel.restart`:
  - Push success + checkbox off → progress reset, file kept.
  - Push success + checkbox on → progress reset, file deleted.
  - Push failure + checkbox on → progress stays dirty, file kept, snackbar
    emitted.

No new instrumentation tests required; the existing reader test fixture covers
"empty locator opens book at start."

## Out of scope (deferred)

- Bookmarks restart — the bookmarks feature is itself unbuilt.
- Server-side `DELETE /progress/{doc}` — would need tombstones; not worth it
  while LWW + reset push works.
- "Restart" entry inside the reader's overflow — feature creep; revisit if
  users ask for it.
