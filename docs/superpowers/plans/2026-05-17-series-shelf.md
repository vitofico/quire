# PR8 — Series continuity shelf: implementation plan

> Shipped in 275e921 on 2026-05-17 as PR #22.

**Date:** 2026-05-17
**Spec:** `docs/superpowers/specs/2026-05-17-series-shelf-design.md`
**Branch:** `feat/series-shelf` off `main`.

## Overview

Android-only PR. Adds a horizontal "Continue your series" shelf above the main library grid that lists books the user has not started, whose series has a finished sibling. Pure Room query; no server work; no AI.

Note: the brief assumed PR1 had landed the Room migration adding `series_name` + `series_index` to the local `documents` table. It hadn't — only the server-side `library_items` table shipped in PR1. **PR8 lands the Android-side Room migration (4 → 5) as well**, documented in the PR body.

## Sequence

### Phase 1 — Room schema

1. Extend `DocumentEntity` with `seriesName: String?` and `seriesIndex: Double?`. Add an `Index("seriesName")` (non-unique) in `@Entity(indices = ...)`.
2. Bump `@Database(version = 5)` and add `MIGRATION_4_5` to `EReaderDatabase` companion. Migration SQL:
   ```sql
   ALTER TABLE documents ADD COLUMN seriesName TEXT;
   ALTER TABLE documents ADD COLUMN seriesIndex REAL;
   CREATE INDEX IF NOT EXISTS index_documents_seriesName ON documents(seriesName);
   ```
3. Register `MIGRATION_4_5` in `addMigrations(...)`.
4. Run `scripts/dgradle :data:local:kspDebugKotlin` to regenerate the Room schema JSON at `data/local/schemas/.../5.json`. Commit the new schema file.

**TDD checkpoint:** add `MigrationTest`'s `migrate 4 to 5` case BEFORE touching `EReaderDatabase`. It must fail (no MIGRATION_4_5 exists), then pass once the migration lands.

### Phase 2 — DAO query

5. Add `DocumentDao.observeSeriesContinuationCandidates(startedThreshold: Double, maxItems: Int): Flow<List<DocumentEntity>>` with the SQL from the spec.
6. Add the DAO tests listed in the spec (`DocumentDaoTest`):
   - Finished sibling + unread candidate → returned.
   - No finished books → empty.
   - User finished the candidate too → excluded.
   - Started past threshold but not finished → excluded.
   - Case-insensitive match.
   - Multi-series fan-out.
   - Order within series by `seriesIndex ASC`.
   - Cap of 12.
   - NULL seriesName never appears.

Write the failing tests first, then implement the query body until they pass.

### Phase 3 — Repository + insert plumbing

7. Widen `DocumentRepository.insert(...)` with `seriesName: String? = null` and `seriesIndex: Double? = null` parameters (defaulted to null so existing callers compile).
8. Add `DocumentRepository.observeSeriesContinuationCandidates(): Flow<List<Document>>`, wiring through the DAO with `startedThreshold = 0.05`, `maxItems = 12` as private companion constants.
9. Update `Document` (`core/model`) to carry `seriesName: String?` and `seriesIndex: Double?`. Update the `toDomain()` mapper.
10. Update download-path call sites (find via `grep -rn "documentRepository.insert\|DocumentRepository.insert\|\.insert("` under `app/src/main`) to thread `MetadataBundle.seriesName` and `seriesPosition?.toDouble()` through to the insert. Most importantly: `BookDownloader` or whoever creates a `Document` from the OPF.

### Phase 4 — ViewModel

11. Add `seriesContinuationCandidates: StateFlow<List<Document>>` to `LibraryViewModel`, sourced from `docs.observeSeriesContinuationCandidates()`, `stateIn(...)` with the same `WhileSubscribed(5000)` cadence the rest of the file uses.
12. Add the `LibraryViewModelTest` cases:
    - Sibling finished + candidate unread → flow emits list of 1.
    - Mark candidate finished → flow emits empty list.

### Phase 5 — UI

13. Add `SeriesContinuationShelf.kt` composable per spec.
14. Wire it into `LibraryScreen` as a full-span grid item, above `ContinueReadingCard`. Render only when the list is non-empty.
15. Add Compose UI test for the shelf (Robolectric): renders nothing on empty list, renders titles + section label on non-empty, click invokes callback.

### Phase 6 — Verify, commit, PR

16. Run:
    ```bash
    scripts/dgradle :data:local:testDebugUnitTest
    scripts/dgradle :app:testDebugUnitTest
    scripts/dgradle :app:lintDebug
    ```
    All must be green.
17. Commit. Single commit, message `:sparkles: feat: series continuity shelf on library home`. NO Claude attribution.
18. `git push -u origin feat/series-shelf`.
19. `gh pr create --base main --head feat/series-shelf` with the body called out in the brief: summary, the SQL query verbatim, test plan, GPT verdict, explicit note "deterministic — no AI calls", explicit note about the unexpected Room migration.

## Files touched

**Modified:**

- `core/model/src/main/java/io/theficos/ereader/core/model/Document.kt` — add two fields.
- `data/local/src/main/java/io/theficos/ereader/data/local/db/DocumentEntity.kt` — add two fields + index.
- `data/local/src/main/java/io/theficos/ereader/data/local/db/DocumentDao.kt` — add `observeSeriesContinuationCandidates`.
- `data/local/src/main/java/io/theficos/ereader/data/local/db/EReaderDatabase.kt` — version bump + MIGRATION_4_5.
- `data/local/src/main/java/io/theficos/ereader/data/local/DocumentRepository.kt` — widen `insert`, add new observer.
- `app/src/main/java/io/theficos/ereader/ui/library/LibraryViewModel.kt` — add new StateFlow.
- `app/src/main/java/io/theficos/ereader/ui/library/LibraryScreen.kt` — render the shelf.
- Any download-path call site of `DocumentRepository.insert` (TBD by grep in Phase 3 step 10).
- `data/local/src/test/java/io/theficos/ereader/data/local/db/DocumentDaoTest.kt` — new tests.
- `data/local/src/test/java/io/theficos/ereader/data/local/db/MigrationTest.kt` — new 4→5 test.
- `app/src/test/java/io/theficos/ereader/ui/library/LibraryViewModelTest.kt` — new tests.

**New:**

- `app/src/main/java/io/theficos/ereader/ui/library/SeriesContinuationShelf.kt`
- `app/src/test/java/io/theficos/ereader/ui/library/SeriesContinuationShelfTest.kt`
- `data/local/schemas/io.theficos.ereader.data.local.db.EReaderDatabase/5.json` (generated)
- `docs/superpowers/specs/2026-05-17-series-shelf-design.md` (this PR)
- `docs/superpowers/plans/2026-05-17-series-shelf.md` (this PR)

## Risks during execution

- **Generated schema JSON.** If the KSP build doesn't run, the `5.json` schema file won't appear and the migration test will fail at runtime (Room sanity-check). Fix: run `:data:local:kspDebugKotlin` explicitly before testing.
- **Existing test fragility.** `DocumentDaoTest` and `MigrationTest` assume version-4 column shape. New tests for the version-5 schema must coexist with the older fixtures. The older `migrate 2 to 3` test uses a hand-written `documents` table SQL and is unaffected.
- **`Document` data class fan-out.** Adding two fields to `core.model.Document` will surface compile errors at every constructor call site. Each site needs to be checked for whether passing `null` is correct (it almost always will be).
- **Compose test runner setup.** If no Compose UI tests exist in the `app` module yet, configure `androidx.compose.ui.test.junit4` deps. (Spot-check `app/build.gradle.kts` first; if Compose test runner isn't there, fall back to a simpler `runComposeUiTest` block from `androidx.compose.ui.test.runComposeUiTest`.)

## Definition of done

- Three test commands above all green.
- Spec + plan committed.
- PR open on GitHub against `main`.
- GPT architect verdict captured in PR body.
- No Claude attribution anywhere.
