# PR8 — Series continuity shelf (Android-only)

**Date:** 2026-05-17
**Status:** Spec for implementation; design locked in `.claude/local/quire-ai/2026-05-16-next-deliverables.md` §"PR8".

## Problem

Quire users often acquire a series in chunks (book 1 first, then 2–4 once they like the first). Today the library home is a flat grid sorted by recent read / added. A reader who just finished book 2 of *Foundation* sees no signal that book 3 is sitting unread one row down — the title doesn't even hint at series membership unless the publisher embedded it in the EPUB title string.

A dedicated **"Continue your series"** horizontal shelf at the top of the library fixes that. It is purely deterministic, computed locally, and shows only books whose sibling-in-series the user has actually finished.

## Non-goals

- Server-side series logic. PR1 added a server-side `library_items.series_name`, but PR8 reads only local Room data — the shelf must work offline and must not depend on the server having upgraded `library_items` ingestion for every book.
- AI-derived series detection. We trust EPUB OPF `calibre:series` (already extracted by `:core:metadata`).
- Cross-author series ("read everything by this author"). That's a different feature.
- Reading time / progress estimates per book. PR9 owns library stats.
- Reordering the main library grid. The shelf appears *above* the existing grid, no other changes.
- Empty-state UX. When the shelf is empty (no finished sibling-in-series), it renders nothing — no "no series yet" placeholder.

## Schema

PR1 (`4a79c7c`) promised to add `series_name` + `series_index` to the local Room `documents` table in the same PR, but only the server-side migration shipped. The Android side was never landed.

**This PR therefore adds the Room migration as well**, bumping the local DB from version 4 → 5. The schema additions are:

```sql
ALTER TABLE documents ADD COLUMN seriesName  TEXT;
ALTER TABLE documents ADD COLUMN seriesIndex REAL;
CREATE INDEX index_documents_seriesName_seriesIndex
  ON documents(seriesName, seriesIndex);
```

- `seriesName` is nullable text. Most books have no series.
- `seriesIndex` is `REAL` (matches Room's mapping for `Double?`). The server-side type is `numeric` because OPF allows half-book entries like `2.5`; SQLite's `REAL` round-trips that.
- Composite index on `(seriesName, seriesIndex)`, declared via Room's `@Index` annotation so the Room schema validator stays happy. We do **not** mark the index `COLLATE NOCASE` — Room doesn't surface per-index collation, and at personal-library scale (<500 books, <50 with non-null seriesName) the case-insensitive `COLLATE NOCASE` predicate used in the query falls back to a scan over a tiny set. Adding a second NOCASE index via raw SQL would speed up worst-case cardinality but complicate schema validation; trade rejected.
- The query uses `COLLATE NOCASE` rather than `LOWER()` — equivalent semantics, slightly cheaper, and a no-op behaviour change if the index ever gains NOCASE collation.
- Columns are backfilled to NULL on the migration — the existing reconcile loop in `DocumentRepository` (PR1's Android side) will populate them on the next OPF extraction. Books downloaded before PR8 keep working; they just don't appear on the shelf until their OPF is re-read.

The columns are written by `DocumentRepository.insert(..., seriesName, seriesIndex, ...)` callers. Today the only caller is `BookDownloader` → `DocumentRepository`. PR8 widens the `insert()` signature with two defaulted-null parameters so existing call sites compile unchanged. **The download path itself is updated in this PR** to pass through `MetadataBundle.seriesName` and `MetadataBundle.seriesPosition` from the OPF extractor, so newly-downloaded books on a PR8 build populate the columns immediately.

The deletion of `seriesIndex` from a `Numeric` server type to `Double` on the Android side is acceptable: even at the absurd upper bound of a `Long.MAX_VALUE` series position, `Double`'s 53-bit mantissa handles it. Real-world series indices are ≤ 100.

## Query

The load-bearing piece. Single SQL, run from `DocumentDao.observeSeriesContinuationCandidates()`, returning `Flow<List<DocumentEntity>>`:

```sql
WITH finished_series AS (
    SELECT sibling.seriesName     AS seriesName,
           MAX(sp.finishedAt)     AS lastFinishedAt
    FROM documents AS sibling
    JOIN progress AS sp ON sp.documentId = sibling.id
    WHERE sibling.seriesName IS NOT NULL
      AND sibling.seriesName != ''
      AND sp.finishedAt IS NOT NULL
    GROUP BY sibling.seriesName COLLATE NOCASE
)
SELECT d.*
FROM documents AS d
JOIN finished_series fs
  ON fs.seriesName = d.seriesName COLLATE NOCASE
WHERE d.seriesName IS NOT NULL
  AND d.seriesName != ''
  AND NOT EXISTS (
      SELECT 1 FROM progress AS p
      WHERE p.documentId = d.id
        AND (p.finishedAt IS NOT NULL OR p.percent >= :startedThreshold)
  )
ORDER BY
  fs.lastFinishedAt DESC,
  (d.seriesIndex IS NULL) ASC,
  d.seriesIndex ASC,
  d.title COLLATE NOCASE ASC,
  d.id ASC
LIMIT :maxItems
```

**Parameter values used by the repository:**

- `startedThreshold = 0.05` (5%). A book the user briefly tapped into but didn't really start is treated as "still continuation-worthy". This matches the brief's suggested default and gives noise tolerance against accidental opens. Books with no `progress` row pass the `NOT EXISTS` filter trivially.
- `maxItems = 12`. Cap on shelf length; avoids unbounded `LazyRow`.

**Semantics, line by line:**

1. **`finished_series` CTE** — collapses all "books the user has finished" to one row per case-folded `seriesName`, recording the most-recent finish per series. Computed once per query rather than per candidate (avoids the correlated-subquery shape and makes the planner's job trivial).
2. **`JOIN finished_series fs ON ... COLLATE NOCASE`** — only books whose series actually has a finished sibling reach the candidate set. The `COLLATE NOCASE` matches the index, so the join is index-driven.
3. **`d.seriesName IS NOT NULL AND d.seriesName != ''`** — defensive; the CTE already filters nulls/empty, but explicit on `d` keeps the planner honest.
4. **`NOT EXISTS (... finishedAt IS NOT NULL OR percent >= 0.05)`** — the candidate book itself must be unread (or barely-touched). Finished books are out; in-progress books past 5% are out (they show up under "Continue reading" instead). This also implicitly excludes "the only finished book in a one-book series" — that book is finished, so it self-excludes here.
5. **`ORDER BY fs.lastFinishedAt DESC`** — series whose latest read happened most recently bubble to the front. If a user finished *Foundation* book 2 yesterday and *Dune* book 1 last year, *Foundation* book 3 sits to the left of *Dune* book 2.
6. **`(d.seriesIndex IS NULL) ASC`** — NULL-handling: SQLite sorts NULL before all values in ASC order. Without this term, an unread book with no `seriesIndex` would sort before book 1 of its own series. Putting null-indices last per series is the correct UX.
7. **`d.seriesIndex ASC`** — within a series, lowest unread index first.
8. **`d.title COLLATE NOCASE ASC`** — deterministic tiebreaker when `seriesIndex` is null or duplicated.
9. **`d.id ASC`** — final deterministic tiebreaker.
10. **`LIMIT 12`** — cap.

**Performance:**

- The candidate set is bounded by "books with a non-null `seriesName`" — typically << 100 for a personal library, often < 20. The new index on `seriesName` keeps the EXISTS subqueries small.
- The correlated subquery in `ORDER BY` could be cached as a CTE, but Room/SQLite handles ~12 outer rows × ~5 siblings per series at sub-millisecond cost. Not worth the complexity.
- The query is re-run via the Flow whenever **any** row in `documents` or `progress` changes, by virtue of Room's reactive `@Query` semantics. The brief explicitly asks for this.

**Case-sensitivity decision:** case-insensitive match on `seriesName`. EPUB metadata is inconsistent across publishers and re-imports; insisting on exact-case match would break the shelf for any library with mixed-case duplicates. The cost is potential collision between two genuinely different series that happen to share a case-normalised name — vanishingly rare for a personal library.

## Repository

`DocumentRepository` gains:

```kotlin
fun observeSeriesContinuationCandidates(): Flow<List<Document>> =
    dao.observeSeriesContinuationCandidates(
        startedThreshold = 0.05,
        maxItems = 12,
    ).map { rows -> rows.map { it.toDomain() } }
```

Mapping rules unchanged from the rest of the file. The threshold and cap are constants on the repository (private vals) so callers don't drift.

## ViewModel

`LibraryViewModel` gains:

```kotlin
val seriesContinuationCandidates: StateFlow<List<Document>> =
    docs.observeSeriesContinuationCandidates()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
```

No interaction with `sort` or `query`. The shelf shows the deterministic set regardless of the main-grid sort or search filter — its purpose is to be a constant nudge.

## UI

New `SeriesContinuationShelf.kt` in `ui/library/`:

```kotlin
@Composable
fun SeriesContinuationShelf(
    books: List<Document>,
    onBookClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
)
```

- `LazyRow` of items, each ~96 dp wide.
- Per-item layout: `CoverImage` (reuses existing component), then title (1 line, ellipsis), then `seriesName · Book N` (1 line, smaller, onSurfaceVariant). If `seriesIndex` is null, the second line is just `seriesName`.
- Section header above the row: `SectionLabel("Continue your series")`.
- When `books` is empty, the composable renders nothing (no `Spacer`, no header). The caller can include it unconditionally and it'll disappear from layout.
- Tapping a cover invokes `onBookClick(book.id)`. Same target as a main-grid tap → opens the reader.

Integration in `LibraryScreen`:

- Insert the shelf as a full-span `item` in the `LazyVerticalGrid`, between the existing `Quire` title row and the `ContinueReadingCard` row (or wherever fits best aesthetically — above the grid contents but below the toolbar). Hide via `if (candidates.isNotEmpty())` to avoid an empty span eating padding.

## Tests

Three test layers; matches the brief.

1. **`DocumentDaoTest` (Room, Robolectric)** — add tests for the new query:
   - Finished sibling + unread candidate in same series → candidate appears.
   - No finished books at all → result empty.
   - User started AND finished the next-in-series → it does not appear (own `finishedAt` excludes it).
   - User started next-in-series past 5% but not finished → it does not appear (started filter).
   - Multi-series: finished book in two distinct series → both unread continuations appear, ordered by most-recent-finished sibling DESC.
   - Within one series, multiple unread later books → ordered by `seriesIndex ASC`.
   - Case-insensitive match: sibling `seriesName = "Foundation"`, candidate `seriesName = "foundation"` → matches.
   - Cap: > 12 candidates → exactly 12 returned.
   - Books with NULL `seriesName` → never appear, even if user has finished other books.

2. **Room `MigrationTest`** — add a 4 → 5 migration test that:
   - Creates DB at version 4 with one `documents` row.
   - Runs `MIGRATION_4_5`.
   - Asserts the row survives.
   - Asserts the `seriesName` and `seriesIndex` columns now exist.
   - Asserts the new `index_documents_seriesName` index exists.

3. **`LibraryViewModelTest`** — add a test that exercises the new StateFlow:
   - Seed: two books in same series; sibling finished, candidate unread.
   - Assert `seriesContinuationCandidates` eventually emits a list of size 1 containing the candidate.
   - Mutate: mark the candidate as finished; assert flow emits empty list (reactivity).

4. **Compose UI test for `SeriesContinuationShelf`** — Robolectric Compose:
   - Empty list → no `SectionLabel("Continue your series")` in the tree.
   - Non-empty list → header present, all book titles rendered.
   - Click on first item → `onBookClick` invoked with that book's id.

## Wiring

- `AppNavGraph.kt`: no change needed. `LibraryScreen` reads the new flow off `LibraryViewModel` it already constructs.
- `AppContainer.kt`: no change needed. `DocumentRepository` is constructed once and the new method is on the same class.
- F-Droid posture: pure UI + Room work; no new destinations, no server calls, no new third-party dependencies.

## Cache-version checklist

N/A — no AI prompt or schema bumped.

## Risks

- **Stale `seriesName` on pre-PR8 downloads.** Books already in the library on upgrade have NULL `seriesName` until something re-runs OPF extraction. This is acceptable: the shelf will populate over time as users re-download or as a future PR adds a background reconcile. Users see no error, just a slowly-populating shelf.
- **MIGRATION_4_5 risk on production DBs.** Pure `ALTER TABLE ADD COLUMN` + `CREATE INDEX` — SQLite handles both online. Tested via `MigrationTest`.
- **Series detection false positives.** If two unrelated books share a normalised `seriesName` ("Untitled"), they'll cross-pollinate the shelf. Trivial cost; revisit only if reports come in.
- **Performance on huge libraries (10k books).** The candidate-set pre-filter on `seriesName IS NOT NULL` with the new index keeps the EXISTS subqueries bounded. Even pathological cases are far inside a single SQLite tick.

## Hand-off to PR9 (library stats)

PR9 will want some of the same primitives:

- The `(documents.seriesName, progress.finishedAt)` join pattern is reusable as-is for "finished books per series" counters.
- The `LOWER()` case-insensitive series grouping should be lifted to PR9's stats query for consistency.
- The `0.05` "started threshold" is a useful constant for "in-progress vs not-started" stats; PR9 should pull it into a shared `LibraryThresholds` object if it ends up reusing it.
- The reactive `Flow<List<...>>` shape on `DocumentRepository` is the right model for PR9's stats; mimic it.

No code is shared today (premature abstraction), but the patterns are documented here so PR9's spec can lift them verbatim.
