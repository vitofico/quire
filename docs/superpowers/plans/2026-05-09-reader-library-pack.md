# Reader & Library feature pack — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship five reader and library improvements (finished-state bug fix, keep-screen-on, tap-to-page, library sort, library search) on a single branch in five reviewable phases.

**Architecture:** Phase 1 introduces a nullable `finishedAt` field through Android Room, the sync DTOs, the FastAPI server, and the Postgres schema; Phase 2 is a one-flag screen-wakelock; Phase 3 lifts the Readium navigator fragment reference into `ReaderScreen` and adds tap zones + a `ReaderPreferences` toggle; Phase 4 adds a `LibrarySort` enum, a sibling `LibraryPreferencesStore`, and a finished-checkmark overlay; Phase 5 adds in-memory query filtering. All work lands as five logical commits on `feat/reader-library-pack` (already created), tests green at every boundary.

**Tech Stack:** Kotlin / Jetpack Compose / Room / DataStore-ish SharedPreferences / kotlinx.serialization / Readium 3.x / FastAPI / SQLAlchemy 2.x / Alembic / pytest / Robolectric / Truth.

**Spec:** `docs/superpowers/specs/2026-05-09-reader-library-pack-design.md`.

---

## Conventions used in this plan

- Build commands assume CWD = repo root.
- Android tests run with `./gradlew :<module>:testDebugUnitTest --tests <FQN>`.
- Server tests run with `cd server && uv run pytest <path> -v` (see `server/README.md`); fall back to `pytest` if `uv` is not available.
- Each phase ends in a single commit named with the existing gitmoji style. Phases use these prefixes:

| Phase | Commit prefix |
|---|---|
| 1 | `:bug: fix(reader): track finished books and stop the 99% Continue Reading loop` |
| 2 | `:sparkles: feat(reader): keep screen on while reading` |
| 3 | `:sparkles: feat(reader): tap left/right to turn pages (toggleable)` |
| 4 | `:sparkles: feat(library): sort options and finished badge` |
| 5 | `:sparkles: feat(library): local search by title and author` |

- TDD: each task starts with a failing test, runs it red, makes it pass, runs it green. Compile-time errors after a refactor count as a failing red — note them as "Expected: build fails with …" rather than running the test.
- Don't combine commits across phases; each phase is its own commit so the branch is bisectable.

---

# Phase 1 — "Finished" concept

Files touched in this phase:

- Create: server `migrations/versions/0002_progress_finished_at.py`
- Modify (Android core/model): `core/model/src/main/java/io/theficos/ereader/core/model/Progress.kt`
- Modify (Android local DB): `data/local/src/main/java/io/theficos/ereader/data/local/db/ProgressEntity.kt`, `data/local/src/main/java/io/theficos/ereader/data/local/db/EReaderDatabase.kt`, `data/local/src/main/java/io/theficos/ereader/data/local/ProgressRepository.kt`
- Schema export: `data/local/schemas/io.theficos.ereader.data.local.db.EReaderDatabase/4.json` (auto-generated on build)
- Modify (Android reader): `reader/src/main/java/io/theficos/ereader/reader/ProgressTracker.kt`
- Modify (Android app): `app/src/main/java/io/theficos/ereader/ui/reader/ReaderViewModel.kt`
- Modify (Android sync): `data/sync/src/main/java/io/theficos/ereader/data/sync/ProgressDtos.kt`, `data/sync/src/main/java/io/theficos/ereader/data/sync/SyncOrchestrator.kt`
- Modify (server): `server/opds_sync/db/models.py`, `server/opds_sync/api/progress.py`
- Modify tests: `data/local/src/test/java/io/theficos/ereader/data/local/db/ProgressDaoTest.kt`, `data/local/src/test/java/io/theficos/ereader/data/local/ProgressRepositoryTest.kt`, `reader/src/test/java/io/theficos/ereader/reader/ProgressTrackerTest.kt`, `data/sync/src/test/java/io/theficos/ereader/data/sync/ProgressDtosTest.kt`, `server/tests/integration/test_progress.py`

---

## Task 1.1 — Add `finishedAt` to the domain model

**Files:**
- Modify: `core/model/src/main/java/io/theficos/ereader/core/model/Progress.kt`

- [ ] **Step 1: Update `Progress` data class**

Replace the entire file content with:

```kotlin
package io.theficos.ereader.core.model

data class Progress(
    val documentId: Long,
    val locator: String,
    val percent: Double,
    val updatedAt: Long,
    val finishedAt: Long? = null,
) {
    init {
        require(percent in 0.0..1.0) { "percent must be in [0,1]" }
    }
}
```

Default `null` keeps existing call sites compiling.

- [ ] **Step 2: Verify the module still builds**

Run: `./gradlew :core:model:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Do not commit yet** — Task 1.2 extends the schema and the commit is created at the end of the phase.

---

## Task 1.2 — Room migration to v4 (`finishedAt` column)

**Files:**
- Modify: `data/local/src/main/java/io/theficos/ereader/data/local/db/ProgressEntity.kt`
- Modify: `data/local/src/main/java/io/theficos/ereader/data/local/db/EReaderDatabase.kt`
- Test: `data/local/src/test/java/io/theficos/ereader/data/local/db/ProgressDaoTest.kt`

- [ ] **Step 1: Write the failing test for the new column**

Append this test to `ProgressDaoTest.kt`:

```kotlin
    @Test fun `upsert round-trips finishedAt`() = runTest {
        val docId = newDoc()
        dao.upsert(ProgressEntity(
            documentId = docId, locator = "x", percent = 0.99,
            updatedAt = 1, localUpdatedAt = 1, syncedAt = 0,
            finishedAt = 1234L,
        ))
        val found = dao.findByDocument(docId)
        assertThat(found?.finishedAt).isEqualTo(1234L)
    }

    @Test fun `upsert allows null finishedAt`() = runTest {
        val docId = newDoc()
        dao.upsert(ProgressEntity(
            documentId = docId, locator = "x", percent = 0.5,
            updatedAt = 1, localUpdatedAt = 1, syncedAt = 0,
            finishedAt = null,
        ))
        val found = dao.findByDocument(docId)
        assertThat(found?.finishedAt).isNull()
    }
```

- [ ] **Step 2: Run the new test and confirm it fails**

Run: `./gradlew :data:local:testDebugUnitTest --tests "io.theficos.ereader.data.local.db.ProgressDaoTest.upsert round-trips finishedAt"`
Expected: compile error — `finishedAt` is not a parameter of `ProgressEntity`.

- [ ] **Step 3: Add `finishedAt` to `ProgressEntity`**

Replace the data class declaration in `ProgressEntity.kt` with:

```kotlin
data class ProgressEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val locator: String,
    val percent: Double,
    val updatedAt: Long,
    val localUpdatedAt: Long,
    val syncedAt: Long,
    val finishedAt: Long? = null,
)
```

Keep imports and annotations untouched.

- [ ] **Step 4: Bump database version and add `MIGRATION_3_4`**

In `EReaderDatabase.kt`, change `version = 3` to `version = 4` and add the migration to the companion object:

```kotlin
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE progress ADD COLUMN finishedAt INTEGER")
            }
        }
```

…and register it in the `build` factory:

```kotlin
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
```

- [ ] **Step 5: Run the new test and confirm it passes**

Run: `./gradlew :data:local:testDebugUnitTest --tests "io.theficos.ereader.data.local.db.ProgressDaoTest"`
Expected: all tests pass, including the two new ones.

A `4.json` file should appear in `data/local/schemas/io.theficos.ereader.data.local.db.EReaderDatabase/`. Leave it untouched; it gets committed at the end of the phase.

- [ ] **Step 6: Add a migration test**

Append this lightweight migration test to `ProgressDaoTest.kt`. It opens a SQLite database with the v3 progress schema, runs the migration's SQL, and asserts the new column exists with NULL accepted as a value. It deliberately avoids Room's `MigrationTestHelper` (which requires an `androidTest` source set and `androidx.room:room-testing` on `androidTestImplementation`) — for an `ALTER TABLE ADD COLUMN` migration, the SQL itself is the entire risk surface and Room's happiness with the resulting schema is exercised by every other test in this class:

```kotlin
    @Test fun `MIGRATION_3_4 sql adds nullable finishedAt column`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "migration-3to4-test.db"
        ctx.deleteDatabase(dbName)
        val sqlite = ctx.openOrCreateDatabase(dbName, android.content.Context.MODE_PRIVATE, null)
        try {
            sqlite.execSQL(
                "CREATE TABLE progress (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "documentId INTEGER NOT NULL, " +
                    "locator TEXT NOT NULL, " +
                    "percent REAL NOT NULL, " +
                    "updatedAt INTEGER NOT NULL, " +
                    "localUpdatedAt INTEGER NOT NULL DEFAULT 0, " +
                    "syncedAt INTEGER NOT NULL DEFAULT 0)"
            )
            sqlite.execSQL("INSERT INTO progress (documentId, locator, percent, updatedAt, localUpdatedAt, syncedAt) VALUES (1, '', 0.0, 0, 0, 0)")

            // Apply the same SQL the production migration runs.
            sqlite.execSQL("ALTER TABLE progress ADD COLUMN finishedAt INTEGER")

            sqlite.rawQuery("PRAGMA table_info(progress)", null).use { c ->
                val cols = mutableListOf<String>()
                while (c.moveToNext()) cols += c.getString(c.getColumnIndexOrThrow("name"))
                assertThat(cols).contains("finishedAt")
            }
            // Pre-existing rows tolerate NULL for the new column.
            sqlite.rawQuery("SELECT finishedAt FROM progress", null).use { c ->
                assertThat(c.moveToNext()).isTrue()
                assertThat(c.isNull(0)).isTrue()
            }
        } finally {
            sqlite.close()
            ctx.deleteDatabase(dbName)
        }
    }
```

This test depends only on `android.content.Context` (already accessible through `ApplicationProvider`) and `assertThat` (already imported). No new module imports required.

- [ ] **Step 7: Run the migration test**

Run: `./gradlew :data:local:testDebugUnitTest --tests "io.theficos.ereader.data.local.db.ProgressDaoTest"`
Expected: PASS.

---

## Task 1.3 — Map `finishedAt` through `ProgressRepository`

**Files:**
- Modify: `data/local/src/main/java/io/theficos/ereader/data/local/ProgressRepository.kt`
- Test: `data/local/src/test/java/io/theficos/ereader/data/local/ProgressRepositoryTest.kt`

- [ ] **Step 1: Write the failing tests**

Append to `ProgressRepositoryTest.kt`:

```kotlin
    @Test fun `save persists finishedAt`() = runTest {
        val docId = seedDoc()
        repo.save(io.theficos.ereader.core.model.Progress(
            documentId = docId, locator = "loc", percent = 0.99,
            updatedAt = 100L, finishedAt = 200L,
        ))
        val row = db.progressDao().findByDocument(docId)!!
        assertThat(row.finishedAt).isEqualTo(200L)
    }

    @Test fun `resetForDocument clears finishedAt`() = runTest {
        val docId = seedDoc()
        db.progressDao().upsert(ProgressEntity(
            documentId = docId, locator = "x", percent = 0.99,
            updatedAt = 1L, localUpdatedAt = 1L, syncedAt = 1L,
            finishedAt = 999L,
        ))
        repo.resetForDocument(docId, now = 50L)
        val row = db.progressDao().findByDocument(docId)!!
        assertThat(row.finishedAt).isNull()
    }

    @Test fun `get returns finishedAt when present`() = runTest {
        val docId = seedDoc()
        db.progressDao().upsert(ProgressEntity(
            documentId = docId, locator = "x", percent = 0.99,
            updatedAt = 1L, localUpdatedAt = 1L, syncedAt = 1L,
            finishedAt = 7L,
        ))
        val p = repo.get(docId)
        assertThat(p?.finishedAt).isEqualTo(7L)
    }
```

- [ ] **Step 2: Run them and confirm they fail**

Run: `./gradlew :data:local:testDebugUnitTest --tests "io.theficos.ereader.data.local.ProgressRepositoryTest"`
Expected: compile error — `Progress` does not have `finishedAt` parameter, and the entity round-trip fails. (Step 1.1 already added `finishedAt` to `Progress`, so it's actually only the `save`/`reset`/`toDomain` paths that are wrong; the test should fail with assertions, not compile errors. Either is acceptable.)

- [ ] **Step 3: Update `ProgressRepository.kt`**

Replace the file content with:

```kotlin
package io.theficos.ereader.data.local

import io.theficos.ereader.core.model.Progress
import io.theficos.ereader.data.local.db.ProgressDao
import io.theficos.ereader.data.local.db.ProgressEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ProgressRepository(private val dao: ProgressDao) {
    suspend fun get(documentId: Long): Progress? =
        dao.findByDocument(documentId)?.toDomain()

    fun observe(documentId: Long): Flow<Progress?> =
        dao.observeByDocument(documentId).map { it?.toDomain() }

    suspend fun save(progress: Progress) {
        val now = System.currentTimeMillis()
        dao.upsert(ProgressEntity(
            documentId = progress.documentId,
            locator = progress.locator,
            percent = progress.percent,
            updatedAt = progress.updatedAt,
            localUpdatedAt = now,
            syncedAt = 0L,
            finishedAt = progress.finishedAt,
        ))
    }

    suspend fun dirty(): List<Progress> = dao.dirty().map { it.toDomain() }

    suspend fun markSynced(documentId: Long, syncedAt: Long) =
        dao.markSynced(documentId, syncedAt)

    suspend fun resetForDocument(documentId: Long, now: Long) {
        dao.upsert(ProgressEntity(
            documentId = documentId,
            locator = "",
            percent = 0.0,
            updatedAt = now,
            localUpdatedAt = now,
            syncedAt = 0L,
            finishedAt = null,
        ))
    }

    private fun ProgressEntity.toDomain(): Progress =
        Progress(
            documentId = documentId,
            locator = locator,
            percent = percent,
            updatedAt = updatedAt,
            finishedAt = finishedAt,
        )
}
```

- [ ] **Step 4: Run tests and confirm they pass**

Run: `./gradlew :data:local:testDebugUnitTest`
Expected: all tests pass.

---

## Task 1.4 — Detect "finished" inside `ProgressTracker`

**Files:**
- Modify: `reader/src/main/java/io/theficos/ereader/reader/ProgressTracker.kt`
- Test: `reader/src/test/java/io/theficos/ereader/reader/ProgressTrackerTest.kt`

The new `attach` signature takes the EPUB's last-spine href and the existing `finishedAt` (read from the saved progress). The tracker computes `finishedAt` per save according to:

1. Sticky: if `existingFinishedAt != null`, keep it forever.
2. Threshold: `totalProgression >= 0.98` ⇒ now.
3. End-of-last-resource: `locator.href == lastSpineHref` and `progression >= 0.99` ⇒ now.
4. Else: `null`.

- [ ] **Step 1: Write the failing tests**

Open `ProgressTrackerTest.kt`. Update the existing helper and existing tests to keep compiling under the new `attach` signature (passing `lastSpineHref = null, initialFinishedAt = null` is the safe default), then append the new behaviour tests. The full file should look like this — overwrite `ProgressTrackerTest.kt` with:

```kotlin
package io.theficos.ereader.reader

import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.core.model.Progress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProgressTrackerTest {

    private fun locatorAt(href: String, totalProgression: Double, progression: Double = totalProgression): Locator =
        Locator(
            href = Url(href)!!,
            mediaType = MediaType.XHTML,
            locations = Locator.Locations(
                progression = progression,
                totalProgression = totalProgression,
            ),
        )

    private fun newTracker(
        saved: MutableList<Progress>,
        scope: kotlinx.coroutines.CoroutineScope,
        nowProvider: () -> Long,
    ) = ProgressTracker(
        save = { saved += it },
        scope = scope,
        nowMs = nowProvider,
    )

    @Test fun `debounces saves to one per second`() = runTest(UnconfinedTestDispatcher()) {
        val saved = mutableListOf<Progress>()
        val locators = MutableSharedFlow<Locator>(extraBufferCapacity = 16)
        val tracker = newTracker(saved, backgroundScope) { testScheduler.currentTime }
        tracker.attach(documentId = 1L, locatorUpdates = locators, lastSpineHref = null, initialFinishedAt = null)

        repeat(5) { locators.tryEmit(locatorAt("/ch1", 0.10 + it * 0.01)) }
        runCurrent()
        advanceTimeBy(50)
        assertThat(saved).isEmpty()

        advanceTimeBy(1_000)
        assertThat(saved).hasSize(1)
        assertThat(saved.last().percent).isWithin(0.001).of(0.14)
    }

    @Test fun `flushes immediately on detach`() = runTest(UnconfinedTestDispatcher()) {
        val saved = mutableListOf<Progress>()
        val locators = MutableSharedFlow<Locator>(extraBufferCapacity = 16)
        val tracker = newTracker(saved, backgroundScope) { testScheduler.currentTime }
        tracker.attach(documentId = 1L, locatorUpdates = locators, lastSpineHref = null, initialFinishedAt = null)
        locators.tryEmit(locatorAt("/ch1", 0.5))
        runCurrent()
        tracker.detach()
        assertThat(saved).hasSize(1)
        assertThat(saved.first().locator).contains("/ch1")
        assertThat(saved.first().percent).isEqualTo(0.5)
        assertThat(saved.first().finishedAt).isNull()
    }

    @Test fun `marks finished when totalProgression crosses 0_98`() = runTest(UnconfinedTestDispatcher()) {
        val saved = mutableListOf<Progress>()
        val locators = MutableSharedFlow<Locator>(extraBufferCapacity = 16)
        val tracker = newTracker(saved, backgroundScope) { testScheduler.currentTime }
        tracker.attach(documentId = 1L, locatorUpdates = locators, lastSpineHref = null, initialFinishedAt = null)
        locators.tryEmit(locatorAt("/ch9", 0.985))
        runCurrent()
        advanceTimeBy(1_100)
        assertThat(saved).hasSize(1)
        assertThat(saved.last().finishedAt).isNotNull()
    }

    @Test fun `does not mark finished below threshold and away from last spine`() = runTest(UnconfinedTestDispatcher()) {
        val saved = mutableListOf<Progress>()
        val locators = MutableSharedFlow<Locator>(extraBufferCapacity = 16)
        val tracker = newTracker(saved, backgroundScope) { testScheduler.currentTime }
        tracker.attach(documentId = 1L, locatorUpdates = locators, lastSpineHref = Url("/ch9")!!, initialFinishedAt = null)
        locators.tryEmit(locatorAt("/ch5", 0.40))
        runCurrent()
        advanceTimeBy(1_100)
        assertThat(saved).hasSize(1)
        assertThat(saved.last().finishedAt).isNull()
    }

    @Test fun `marks finished when at last spine and progression at end`() = runTest(UnconfinedTestDispatcher()) {
        val saved = mutableListOf<Progress>()
        val locators = MutableSharedFlow<Locator>(extraBufferCapacity = 16)
        val tracker = newTracker(saved, backgroundScope) { testScheduler.currentTime }
        tracker.attach(documentId = 1L, locatorUpdates = locators, lastSpineHref = Url("/ch9")!!, initialFinishedAt = null)
        // totalProgression below threshold but we're at last spine and end-of-resource
        locators.tryEmit(locatorAt("/ch9", totalProgression = 0.92, progression = 0.995))
        runCurrent()
        advanceTimeBy(1_100)
        assertThat(saved).hasSize(1)
        assertThat(saved.last().finishedAt).isNotNull()
    }

    @Test fun `finishedAt is sticky once set`() = runTest(UnconfinedTestDispatcher()) {
        val saved = mutableListOf<Progress>()
        val locators = MutableSharedFlow<Locator>(extraBufferCapacity = 16)
        val tracker = newTracker(saved, backgroundScope) { testScheduler.currentTime }
        tracker.attach(documentId = 1L, locatorUpdates = locators, lastSpineHref = null, initialFinishedAt = 4242L)
        locators.tryEmit(locatorAt("/ch1", 0.10))
        runCurrent()
        advanceTimeBy(1_100)
        assertThat(saved).hasSize(1)
        assertThat(saved.last().finishedAt).isEqualTo(4242L)
    }
}
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `./gradlew :reader:testDebugUnitTest`
Expected: compile error — `attach` does not accept `lastSpineHref`/`initialFinishedAt`.

- [ ] **Step 3: Update `ProgressTracker.kt`**

Replace the file content with:

```kotlin
package io.theficos.ereader.reader

import io.theficos.ereader.core.model.Progress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url

class ProgressTracker(
    private val save: suspend (Progress) -> Unit,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val debounceMs: Long = 1_000L,
) {
    private val pending = MutableStateFlow<Pending?>(null)
    private var collectJob: Job? = null
    private var debounceJob: Job? = null
    private var documentId: Long = -1L
    private var lastSpineHref: Url? = null
    private var stickyFinishedAt: Long? = null

    fun attach(
        documentId: Long,
        locatorUpdates: Flow<Locator>,
        lastSpineHref: Url?,
        initialFinishedAt: Long?,
    ) {
        this.documentId = documentId
        this.lastSpineHref = lastSpineHref
        this.stickyFinishedAt = initialFinishedAt
        collectJob = scope.launch {
            locatorUpdates.collect { locator ->
                pending.value = Pending(locator, nowMs())
                debounceJob?.cancel()
                debounceJob = scope.launch {
                    delay(debounceMs)
                    flushOnce()
                }
            }
        }
    }

    fun detach() {
        debounceJob?.cancel()
        runBlocking { flushOnce() }
        collectJob?.cancel()
    }

    private suspend fun flushOnce() {
        val p = pending.value ?: return
        pending.value = null
        val finishedAt = computeFinishedAt(p.locator, p.timestampMs)
        stickyFinishedAt = finishedAt
        save(Progress(
            documentId = documentId,
            locator = serialize(p.locator),
            percent = (p.locator.locations.totalProgression
                ?: p.locator.locations.progression
                ?: 0.0).coerceIn(0.0, 1.0),
            updatedAt = p.timestampMs,
            finishedAt = finishedAt,
        ))
    }

    private fun computeFinishedAt(locator: Locator, nowMs: Long): Long? {
        stickyFinishedAt?.let { return it }
        val total = locator.locations.totalProgression
        if (total != null && total >= FINISHED_TOTAL_THRESHOLD) return nowMs
        val prog = locator.locations.progression
        val last = lastSpineHref
        if (last != null && locator.href == last && prog != null && prog >= FINISHED_LAST_RESOURCE_THRESHOLD) {
            return nowMs
        }
        return null
    }

    private data class Pending(val locator: Locator, val timestampMs: Long)

    companion object {
        private const val FINISHED_TOTAL_THRESHOLD = 0.98
        private const val FINISHED_LAST_RESOURCE_THRESHOLD = 0.99

        /** Encodes a Readium [Locator] as a JSON string for persistence and (Phase 2) sync. */
        fun serialize(locator: Locator): String =
            locator.toJSON().toString()

        /**
         * Returns a [Locator] reconstituted from a previously-[serialize]d string, or `null` if
         * the input is the Phase 1 legacy format, malformed JSON, or otherwise un-parseable.
         */
        fun parseOrNull(raw: String): Locator? = try {
            val json = JSONObject(raw)
            // Legacy Phase 1 stub wrote {"href":..., "percent":...} — no "locations" object.
            if (json.has("percent") && !json.has("locations")) null
            else Locator.fromJSON(json)
        } catch (_: Throwable) {
            null
        }
    }
}
```

- [ ] **Step 4: Run tests and confirm they pass**

Run: `./gradlew :reader:testDebugUnitTest`
Expected: all `ProgressTrackerTest` cases pass.

---

## Task 1.5 — Wire the new `attach` signature in `ReaderViewModel`

**Files:**
- Modify: `app/src/main/java/io/theficos/ereader/ui/reader/ReaderViewModel.kt`

- [ ] **Step 1: Update `load()` to read `lastSpineHref` and `initialFinishedAt`**

Replace the `load()` body (and only the `load()` body) with:

```kotlin
    fun load() {
        viewModelScope.launch {
            val doc = docs.findById(documentId) ?: run {
                _state.value = ReaderUiState.Error("Document not found")
                return@launch
            }
            val publication = runCatching {
                readium.open(EpubAsset(doc.id, File(doc.localPath), doc.title))
            }.getOrElse {
                _state.value = ReaderUiState.Error(it.message ?: "Failed to open book")
                return@launch
            }
            val savedProgress = progress.get(doc.id)
            val initialLocator = savedProgress?.locator?.let { ProgressTracker.parseOrNull(it) }
            val lastSpineHref = publication.readingOrder.lastOrNull()?.href
            _state.value = ReaderUiState.Open(doc, publication, initialLocator, savedProgress)
            tracker.attach(
                documentId = doc.id,
                locatorUpdates = locatorUpdates,
                lastSpineHref = lastSpineHref,
                initialFinishedAt = savedProgress?.finishedAt,
            )
        }
    }
```

- [ ] **Step 2: Build the app module to confirm it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

---

## Task 1.6 — Add `finishedAt` to the sync DTOs

**Files:**
- Modify: `data/sync/src/main/java/io/theficos/ereader/data/sync/ProgressDtos.kt`
- Test: `data/sync/src/test/java/io/theficos/ereader/data/sync/ProgressDtosTest.kt`

- [ ] **Step 1: Write the failing test**

Append to `ProgressDtosTest.kt`:

```kotlin
    @Test fun `push body round-trips finishedAt`() {
        val body = ProgressPushBody(
            items = listOf(
                ProgressItemDto(
                    document = DocumentIdDto(metadataId = "m1", contentHash = "h1"),
                    locator = "loc",
                    percent = 0.99,
                    clientUpdatedAt = "2026-05-09T12:00:00+00:00",
                    finishedAt = "2026-05-09T12:00:00+00:00",
                )
            )
        )
        val encoded = json.encodeToString(ProgressPushBody.serializer(), body)
        assertThat(encoded).contains("\"finished_at\":\"2026-05-09T12:00:00+00:00\"")
        val decoded = json.decodeFromString(ProgressPushBody.serializer(), encoded)
        assertThat(decoded).isEqualTo(body)
    }

    @Test fun `null finishedAt is omitted on the wire`() {
        val body = ProgressPushBody(
            items = listOf(
                ProgressItemDto(
                    document = DocumentIdDto(metadataId = "m1", contentHash = "h1"),
                    locator = "loc",
                    percent = 0.5,
                    clientUpdatedAt = "2026-05-09T12:00:00+00:00",
                    finishedAt = null,
                )
            )
        )
        val encoded = json.encodeToString(ProgressPushBody.serializer(), body)
        assertThat(encoded).doesNotContain("finished_at")
    }

    @Test fun `pull response decodes with optional finishedAt`() {
        val raw = """{"items":[{"document":{"metadata_id":null,"content_hash":"h"},"locator":"l","percent":0.99,"client_updated_at":"2026-05-09T12:00:00+00:00","finished_at":"2026-05-09T12:00:00+00:00"}],"server_time":"2026-05-09T12:00:01+00:00"}"""
        val r = json.decodeFromString(ProgressPullResponse.serializer(), raw)
        assertThat(r.items.first().finishedAt).isEqualTo("2026-05-09T12:00:00+00:00")
    }
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `./gradlew :data:sync:testDebugUnitTest`
Expected: compile error — `ProgressItemDto` has no `finishedAt`.

- [ ] **Step 3: Add the field to `ProgressItemDto`**

Open `ProgressDtos.kt`. Replace the `ProgressItemDto` declaration with:

```kotlin
@Serializable
data class ProgressItemDto(
    val document: DocumentIdDto,
    val locator: String,
    val percent: Double,
    @SerialName("client_updated_at") val clientUpdatedAt: String,
    @SerialName("finished_at") val finishedAt: String? = null,
)
```

`Json { ignoreUnknownKeys = true }` defaults to `encodeDefaults = false`, so a `null` `finishedAt` is omitted on the wire — keeping the request format backwards compatible with older servers.

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `./gradlew :data:sync:testDebugUnitTest`
Expected: all tests pass.

---

## Task 1.7 — Carry `finishedAt` through `SyncOrchestrator`

**Files:**
- Modify: `data/sync/src/main/java/io/theficos/ereader/data/sync/SyncOrchestrator.kt`

- [ ] **Step 1: Update push — include `finishedAt`**

In the `runOnce()` push block, change the `ProgressItemDto(...)` construction to include `finishedAt`:

```kotlin
                ProgressItemDto(
                    document = DocumentIdDto(metadataId = doc.identity.metadataId, contentHash = doc.identity.contentHash),
                    locator = progress.locator,
                    percent = progress.percent,
                    clientUpdatedAt = Instant.ofEpochMilli(progress.updatedAt).toString(),
                    finishedAt = progress.finishedAt?.let { Instant.ofEpochMilli(it).toString() },
                )
```

- [ ] **Step 2: Update pull — persist `finishedAt`**

Replace `applyPulled` in `SyncOrchestrator.kt` with:

```kotlin
    private suspend fun applyPulled(item: ProgressItemDto) {
        val identity = DocumentIdentity(metadataId = item.document.metadataId, contentHash = item.document.contentHash)
        val doc = documentRepo.findByIdentity(identity) ?: return
        val incomingUpdatedAt = Instant.parse(item.clientUpdatedAt).toEpochMilli()
        val incomingFinishedAt = item.finishedAt?.let { Instant.parse(it).toEpochMilli() }
        val existing = progressDao.findByDocument(doc.id)
        if (existing != null && existing.localUpdatedAt >= incomingUpdatedAt) {
            return
        }
        progressDao.upsert(
            ProgressEntity(
                id = existing?.id ?: 0L,
                documentId = doc.id,
                locator = item.locator,
                percent = item.percent,
                updatedAt = incomingUpdatedAt,
                localUpdatedAt = incomingUpdatedAt,
                syncedAt = incomingUpdatedAt,
                finishedAt = incomingFinishedAt,
            )
        )
    }
```

- [ ] **Step 3: Build to confirm**

Run: `./gradlew :data:sync:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

The existing `SyncOrchestratorTest` does not exercise `finishedAt` directly; round-trip is covered by `ProgressDtosTest` (Task 1.6) and the server tests (Task 1.10).

---

## Task 1.8 — Server: add `finished_at` column and SQLAlchemy mapping

**Files:**
- Modify: `server/opds_sync/db/models.py`
- Create: `server/migrations/versions/0002_progress_finished_at.py`

- [ ] **Step 1: Add `finished_at` to the SQLAlchemy model**

Open `server/opds_sync/db/models.py`. Edit the `Progress` class to add the column directly after `percent`:

```python
class Progress(Base):
    __tablename__ = "progress"
    __table_args__ = (
        CheckConstraint("percent >= 0 AND percent <= 1", name="ck_progress_percent_range"),
        Index("ix_progress_document_client_updated_at", "document_pk", "client_updated_at"),
    )

    document_pk: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("documents.pk", ondelete="CASCADE"), primary_key=True
    )
    locator: Mapped[str] = mapped_column(String, nullable=False)
    percent: Mapped[float] = mapped_column(Float, nullable=False)
    finished_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    client_updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    received_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )

    document: Mapped[Document] = relationship(back_populates="progress")
```

- [ ] **Step 2: Create the Alembic migration**

Create `server/migrations/versions/0002_progress_finished_at.py`:

```python
"""progress: add nullable finished_at

Revision ID: 0002
Revises: 0001
Create Date: 2026-05-09 00:00:00.000000
"""

import sqlalchemy as sa
from alembic import op

revision = "0002"
down_revision = "0001"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "progress",
        sa.Column("finished_at", sa.DateTime(timezone=True), nullable=True),
    )


def downgrade() -> None:
    op.drop_column("progress", "finished_at")
```

- [ ] **Step 3: Verify the migration is picked up**

Run: `cd server && uv run alembic heads` (or `alembic heads`).
Expected: `0002 (head)`.

- [ ] **Step 4: No commit yet** — Tasks 1.9 / 1.10 close out the phase.

---

## Task 1.9 — Server: thread `finished_at` through the API

**Files:**
- Modify: `server/opds_sync/api/progress.py`

- [ ] **Step 1: Update Pydantic models and handlers**

Replace the entire content of `server/opds_sync/api/progress.py` with:

```python
from datetime import UTC, datetime
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, Query
from pydantic import BaseModel, field_serializer
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from opds_sync.core.auth import current_user_id
from opds_sync.db.models import Document, Progress
from opds_sync.db.session import get_session

router = APIRouter(tags=["progress"])


class DocumentIdentity(BaseModel):
    metadata_id: str | None = None
    content_hash: str


class ProgressItem(BaseModel):
    document: DocumentIdentity
    locator: str
    percent: float
    client_updated_at: datetime
    finished_at: datetime | None = None


class ProgressPushBody(BaseModel):
    items: list[ProgressItem]


class ProgressPushResult(BaseModel):
    document: DocumentIdentity
    status: Literal["accepted", "stale"]
    server_client_updated_at: datetime

    @field_serializer("server_client_updated_at")
    def _serialize_dt(self, v: datetime) -> str:
        if v.tzinfo is None:
            v = v.replace(tzinfo=UTC)
        return v.isoformat()


class ProgressPushResponse(BaseModel):
    results: list[ProgressPushResult]


class ProgressPullItem(BaseModel):
    document: DocumentIdentity
    locator: str
    percent: float
    client_updated_at: datetime
    finished_at: datetime | None = None


class ProgressPullResponse(BaseModel):
    items: list[ProgressPullItem]
    server_time: datetime


async def _resolve_or_create_document(
    session: AsyncSession, user_id: str, ident: DocumentIdentity
) -> Document:
    """Per spec §5.4: metadata_id first, then content_hash, else create."""
    if ident.metadata_id:
        existing = (
            await session.execute(
                select(Document).where(
                    Document.user_id == user_id, Document.metadata_id == ident.metadata_id
                )
            )
        ).scalar_one_or_none()
        if existing:
            return existing
    existing = (
        await session.execute(
            select(Document).where(
                Document.user_id == user_id, Document.content_hash == ident.content_hash
            )
        )
    ).scalar_one_or_none()
    if existing:
        if ident.metadata_id and existing.metadata_id is None:
            existing.metadata_id = ident.metadata_id
        return existing
    doc = Document(user_id=user_id, metadata_id=ident.metadata_id, content_hash=ident.content_hash)
    session.add(doc)
    await session.flush()
    return doc


@router.post("/progress", response_model=ProgressPushResponse)
async def push_progress(
    body: ProgressPushBody,
    user_id: Annotated[str, Depends(current_user_id)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> ProgressPushResponse:
    results: list[ProgressPushResult] = []
    for item in body.items:
        doc = await _resolve_or_create_document(session, user_id, item.document)
        existing = (
            await session.execute(select(Progress).where(Progress.document_pk == doc.pk))
        ).scalar_one_or_none()
        if existing is None:
            session.add(
                Progress(
                    document_pk=doc.pk,
                    locator=item.locator,
                    percent=item.percent,
                    client_updated_at=item.client_updated_at,
                    finished_at=item.finished_at,
                )
            )
            results.append(
                ProgressPushResult(
                    document=item.document,
                    status="accepted",
                    server_client_updated_at=item.client_updated_at,
                )
            )
            continue
        if item.client_updated_at > existing.client_updated_at:
            existing.locator = item.locator
            existing.percent = item.percent
            existing.client_updated_at = item.client_updated_at
            existing.finished_at = item.finished_at
            results.append(
                ProgressPushResult(
                    document=item.document,
                    status="accepted",
                    server_client_updated_at=item.client_updated_at,
                )
            )
        else:
            results.append(
                ProgressPushResult(
                    document=item.document,
                    status="stale",
                    server_client_updated_at=existing.client_updated_at,
                )
            )
    await session.commit()
    return ProgressPushResponse(results=results)


@router.get("/progress", response_model=ProgressPullResponse)
async def pull_progress(
    since: Annotated[str, Query()],
    user_id: Annotated[str, Depends(current_user_id)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> ProgressPullResponse:
    since_dt = datetime.fromisoformat(since.replace(" ", "+"))
    rows = (
        await session.execute(
            select(Progress, Document)
            .join(Document, Document.pk == Progress.document_pk)
            .where(Document.user_id == user_id, Progress.client_updated_at > since_dt)
            .order_by(Progress.client_updated_at)
        )
    ).all()
    items = [
        ProgressPullItem(
            document=DocumentIdentity(metadata_id=d.metadata_id, content_hash=d.content_hash),
            locator=p.locator,
            percent=p.percent,
            client_updated_at=p.client_updated_at,
            finished_at=p.finished_at,
        )
        for p, d in rows
    ]
    server_time = datetime.now().astimezone()
    return ProgressPullResponse(items=items, server_time=server_time)
```

The two material changes are: `ProgressItem`/`ProgressPullItem` get `finished_at`, and the push handler stores/overwrites it on the `Progress` row.

- [ ] **Step 2: Confirm the file compiles cleanly**

Run: `cd server && uv run python -c "from opds_sync.api import progress as p; print(p.ProgressItem.model_json_schema())"`
Expected: prints a JSON schema that includes `finished_at`.

---

## Task 1.10 — Server tests for `finished_at`

**Files:**
- Modify: `server/tests/integration/test_progress.py`

- [ ] **Step 1: Write the failing tests**

Append to `server/tests/integration/test_progress.py`:

```python
async def test_post_progress_round_trips_finished_at(app_under_test):
    transport = ASGITransport(app=app_under_test)
    headers = _basic("alice", "alicepass")
    body = {
        "items": [
            {
                "document": {"metadata_id": "fa1", "content_hash": "fa1"},
                "locator": "loc",
                "percent": 0.99,
                "client_updated_at": "2026-05-09T12:00:00+00:00",
                "finished_at": "2026-05-09T12:00:00+00:00",
            }
        ]
    }
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        r = await c.post("/sync/v1/progress", json=body, headers=headers)
        assert r.status_code == 200, r.text
        r2 = await c.get(
            "/sync/v1/progress?since=2026-01-01T00:00:00+00:00", headers=headers
        )
    items = r2.json()["items"]
    assert len(items) == 1
    assert items[0]["finished_at"] == "2026-05-09T12:00:00+00:00"


async def test_post_progress_omits_finished_at_when_absent(app_under_test):
    transport = ASGITransport(app=app_under_test)
    headers = _basic("alice", "alicepass")
    body = {
        "items": [
            {
                "document": {"metadata_id": "noFa", "content_hash": "noFa"},
                "locator": "loc",
                "percent": 0.5,
                "client_updated_at": "2026-05-09T12:00:00+00:00",
            }
        ]
    }
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        r = await c.post("/sync/v1/progress", json=body, headers=headers)
        assert r.status_code == 200
        r2 = await c.get(
            "/sync/v1/progress?since=2026-01-01T00:00:00+00:00", headers=headers
        )
    items = r2.json()["items"]
    pulled = next(i for i in items if i["document"]["content_hash"] == "noFa")
    assert pulled["finished_at"] is None


async def test_post_progress_lww_overwrites_finished_with_unfinished(app_under_test):
    """Restart on a newer client must clear server-side finished_at."""
    transport = ASGITransport(app=app_under_test)
    headers = _basic("alice", "alicepass")
    base_doc = {"metadata_id": "lww", "content_hash": "lww"}
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        # finished
        await c.post(
            "/sync/v1/progress",
            json={
                "items": [
                    {
                        "document": base_doc,
                        "locator": "end",
                        "percent": 0.99,
                        "client_updated_at": "2026-05-09T12:00:00+00:00",
                        "finished_at": "2026-05-09T12:00:00+00:00",
                    }
                ]
            },
            headers=headers,
        )
        # restart pushes newer unfinished
        r = await c.post(
            "/sync/v1/progress",
            json={
                "items": [
                    {
                        "document": base_doc,
                        "locator": "",
                        "percent": 0.0,
                        "client_updated_at": "2026-05-09T13:00:00+00:00",
                    }
                ]
            },
            headers=headers,
        )
        assert r.status_code == 200
        assert r.json()["results"][0]["status"] == "accepted"
        r2 = await c.get(
            "/sync/v1/progress?since=2026-01-01T00:00:00+00:00", headers=headers
        )
    items = [i for i in r2.json()["items"] if i["document"]["content_hash"] == "lww"]
    assert len(items) == 1
    assert items[0]["percent"] == 0.0
    assert items[0]["finished_at"] is None
```

- [ ] **Step 2: Run server tests**

Run: `cd server && uv run pytest tests/integration/test_progress.py -v`
Expected: all tests pass, including the three new ones.

If `app_under_test` is a session-scoped fixture that does not auto-run migrations, you may need to start with a fresh test DB. Verify by checking `conftest.py`; if fixture re-creates schema each session, no extra step is needed.

---

## Task 1.11 — Phase 1 commit

- [ ] **Step 1: Run the full Android test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL with all module tests green.

- [ ] **Step 2: Run the full server test suite**

Run: `cd server && uv run pytest -v`
Expected: all tests pass.

- [ ] **Step 3: Commit**

```bash
git add core/model data/local data/sync reader app server/opds_sync server/migrations server/tests data/local/schemas
git status   # confirm only intended paths
git commit -m "$(cat <<'EOF'
:bug: fix(reader): track finished books and stop the 99% Continue Reading loop

Adds a nullable finishedAt timestamp to Progress, persisted through Room
(v3->v4 migration), the sync DTOs, and the Postgres schema (Alembic 0002).
ProgressTracker stamps finishedAt when totalProgression crosses 0.98 or
when at the last spine resource with progression >= 0.99; the value is
sticky once set and is cleared by Restart. LibraryViewModel will switch
its Continue Reading filter in phase 4.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

Confirm the commit is on `feat/reader-library-pack`.

---

# Phase 2 — Keep screen on

Files touched:
- Modify: `app/src/main/java/io/theficos/ereader/ui/reader/ReaderScreen.kt`

---

## Task 2.1 — Add `FLAG_KEEP_SCREEN_ON` for the reader

**Files:**
- Modify: `app/src/main/java/io/theficos/ereader/ui/reader/ReaderScreen.kt`

- [ ] **Step 1: Add the imports and effect**

In `ReaderScreen.kt`, add these imports next to the existing ones:

```kotlin
import android.app.Activity
import android.view.WindowManager
```

Inside the `ReaderScreen` composable, immediately before the existing `LaunchedEffect(Unit) { viewModel.load() }`, insert:

```kotlin
    val activity = LocalContext.current as Activity
    DisposableEffect(activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
```

- [ ] **Step 2: Build the app**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Sanity check by hand on a device or emulator**

Run the app, open a book, leave the device idle for ~30 s, and confirm the screen does not dim. Navigate back to Library and confirm the system timeout resumes.

(Skip this step if running unattended — record "verified by hand" in the PR description before merge.)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/theficos/ereader/ui/reader/ReaderScreen.kt
git commit -m "$(cat <<'EOF'
:sparkles: feat(reader): keep screen on while reading

ReaderScreen sets FLAG_KEEP_SCREEN_ON on enter and clears it on dispose,
scoped to its composition so leaving the reader restores the system
timeout.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Phase 3 — Tap zones and reader-side toggle

Files touched:
- Modify: `reader/src/main/java/io/theficos/ereader/reader/ReaderPreferences.kt`
- Modify: `reader/src/main/java/io/theficos/ereader/reader/ReaderPreferencesStore.kt`
- Create: `reader/src/test/java/io/theficos/ereader/reader/ReaderPreferencesStoreTest.kt`
- Modify: `app/src/main/java/io/theficos/ereader/ui/reader/ReaderViewModel.kt`
- Modify: `app/src/main/java/io/theficos/ereader/ui/reader/ReaderScreen.kt`
- Modify: `app/src/main/java/io/theficos/ereader/ui/reader/FontSettingsSheet.kt`

---

## Task 3.1 — Add `tapNavigationEnabled` to `ReaderPreferences`

**Files:**
- Modify: `reader/src/main/java/io/theficos/ereader/reader/ReaderPreferences.kt`
- Modify: `reader/src/main/java/io/theficos/ereader/reader/ReaderPreferencesStore.kt`
- Create: `reader/src/test/java/io/theficos/ereader/reader/ReaderPreferencesStoreTest.kt`

- [ ] **Step 1: Write the failing test**

Create `reader/src/test/java/io/theficos/ereader/reader/ReaderPreferencesStoreTest.kt`:

```kotlin
package io.theficos.ereader.reader

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReaderPreferencesStoreTest {

    private fun freshStore() = ReaderPreferencesStore(ApplicationProvider.getApplicationContext()).also {
        it.update { ReaderPreferences() }
    }

    @Test fun `default tapNavigationEnabled is true`() {
        val store = freshStore()
        assertThat(store.flow.value.tapNavigationEnabled).isTrue()
    }

    @Test fun `tapNavigationEnabled round-trips through update and reload`() {
        val store1 = freshStore()
        store1.update { it.copy(tapNavigationEnabled = false) }
        assertThat(store1.flow.value.tapNavigationEnabled).isFalse()

        val store2 = ReaderPreferencesStore(ApplicationProvider.getApplicationContext())
        assertThat(store2.flow.value.tapNavigationEnabled).isFalse()
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `./gradlew :reader:testDebugUnitTest --tests "io.theficos.ereader.reader.ReaderPreferencesStoreTest"`
Expected: compile error — `tapNavigationEnabled` is not a property of `ReaderPreferences`.

- [ ] **Step 3: Add the property to `ReaderPreferences`**

In `ReaderPreferences.kt`, replace the `data class ReaderPreferences(...)` block with:

```kotlin
data class ReaderPreferences(
    val fontScale: Double = 1.0,
    val theme: ReaderTheme = ReaderTheme.LIGHT,
    val fontFamily: ReaderFontFamily = ReaderFontFamily.SYSTEM,
    val lineSpacing: Double = 1.4,
    val tapNavigationEnabled: Boolean = true,
) {
    init {
        require(fontScale in 0.5..2.0) { "fontScale out of range: $fontScale" }
        require(lineSpacing in 1.0..1.8) { "lineSpacing out of range: $lineSpacing" }
    }
}
```

- [ ] **Step 4: Persist it in `ReaderPreferencesStore`**

In `ReaderPreferencesStore.kt`:

1. Add a new key constant inside the `companion object`:

```kotlin
        const val KEY_TAP_NAVIGATION = "tap_navigation_enabled"
```

2. Replace the `update` body with:

```kotlin
    fun update(transform: (ReaderPreferences) -> ReaderPreferences) {
        val next = transform(_flow.value)
        prefs.edit()
            .putFloat(KEY_FONT_SCALE, next.fontScale.toFloat())
            .putString(KEY_THEME, next.theme.name)
            .putString(KEY_FONT_FAMILY, next.fontFamily.name)
            .putFloat(KEY_LINE_SPACING, next.lineSpacing.toFloat())
            .putBoolean(KEY_TAP_NAVIGATION, next.tapNavigationEnabled)
            .apply()
        _flow.value = next
    }
```

3. Replace the `load()` body with:

```kotlin
    private fun load(): ReaderPreferences {
        val fontScale = prefs.getFloat(KEY_FONT_SCALE, 1.0f).toDouble().coerceIn(0.5, 2.0)
        val themeName = prefs.getString(KEY_THEME, ReaderTheme.LIGHT.name) ?: ReaderTheme.LIGHT.name
        val theme = runCatching { ReaderTheme.valueOf(themeName) }.getOrDefault(ReaderTheme.LIGHT)
        val familyName = prefs.getString(KEY_FONT_FAMILY, ReaderFontFamily.SYSTEM.name)
            ?: ReaderFontFamily.SYSTEM.name
        val family = runCatching { ReaderFontFamily.valueOf(familyName) }
            .getOrDefault(ReaderFontFamily.SYSTEM)
        val lineSpacing = prefs.getFloat(KEY_LINE_SPACING, 1.4f).toDouble().coerceIn(1.0, 1.8)
        val tap = prefs.getBoolean(KEY_TAP_NAVIGATION, true)
        return ReaderPreferences(
            fontScale = fontScale,
            theme = theme,
            fontFamily = family,
            lineSpacing = lineSpacing,
            tapNavigationEnabled = tap,
        )
    }
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew :reader:testDebugUnitTest`
Expected: all tests pass.

---

## Task 3.2 — Lift the navigator fragment ref into `ReaderViewModel`

The `EpubNavigatorFragment` is currently local to `ReaderContent`. We move the reference up so `ReaderViewModel` can issue `goForward` / `goBackward` from tap callbacks.

**Files:**
- Modify: `app/src/main/java/io/theficos/ereader/ui/reader/ReaderViewModel.kt`

- [ ] **Step 1: Add navigator fields and page commands**

In `ReaderViewModel.kt`, add these imports next to the existing ones:

```kotlin
import org.readium.r2.navigator.epub.EpubNavigatorFragment
```

Inside the `ReaderViewModel` class, after the `tracker` field, add:

```kotlin
    private var navigator: EpubNavigatorFragment? = null

    fun bindNavigator(nav: EpubNavigatorFragment?) {
        navigator = nav
    }

    fun pageForward() {
        viewModelScope.launch { navigator?.goForward() }
    }

    fun pageBackward() {
        viewModelScope.launch { navigator?.goBackward() }
    }
```

- [ ] **Step 2: Build to confirm**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. The methods are unused at this step; they get wired in Task 3.3.

---

## Task 3.3 — Add tap zones to `ReaderScreen` and bind the navigator

**Files:**
- Modify: `app/src/main/java/io/theficos/ereader/ui/reader/ReaderScreen.kt`

- [ ] **Step 1: Bind the navigator and add left/right zones**

In `ReaderScreen.kt`:

1. Find `ReaderContent(...)` inside the `is ReaderUiState.Open` branch. Replace the call with one that passes a callback up to bind the navigator (full call shown below):

```kotlin
                ReaderContent(
                    publication = s.publication,
                    initialLocator = s.initialLocator,
                    preferences = preferences,
                    onLocator = viewModel::publishLocator,
                    onNavigatorReady = viewModel::bindNavigator,
                )
```

2. Replace the existing `Box(modifier = Modifier.align(Alignment.Center).fillMaxHeight().fillMaxWidth(0.34f).pointerInput(Unit) { detectTapGestures(onTap = { viewModel.toggleChrome() }) })` block with a `Row` that hosts three zones, gated by the preference:

```kotlin
                if (preferences.tapNavigationEnabled) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .weight(0.33f)
                                .fillMaxHeight()
                                .pointerInput(Unit) {
                                    detectTapGestures(onTap = { viewModel.pageBackward() })
                                }
                        )
                        Box(
                            modifier = Modifier
                                .weight(0.34f)
                                .fillMaxHeight()
                                .pointerInput(Unit) {
                                    detectTapGestures(onTap = { viewModel.toggleChrome() })
                                }
                        )
                        Box(
                            modifier = Modifier
                                .weight(0.33f)
                                .fillMaxHeight()
                                .pointerInput(Unit) {
                                    detectTapGestures(onTap = { viewModel.pageForward() })
                                }
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxHeight()
                            .fillMaxWidth(0.34f)
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = { viewModel.toggleChrome() })
                            }
                    )
                }
```

3. Add `import androidx.compose.foundation.layout.Row` to the imports.

- [ ] **Step 2: Update `ReaderContent` to surface the navigator**

Replace the entire `ReaderContent` composable in `ReaderScreen.kt` with:

```kotlin
@Composable
private fun ReaderContent(
    publication: Publication,
    initialLocator: Locator?,
    preferences: ReaderPreferences,
    onLocator: (Locator) -> Unit,
    onNavigatorReady: (EpubNavigatorFragment?) -> Unit,
) {
    val activity = LocalContext.current as FragmentActivity
    val containerId = rememberSaveable { View.generateViewId() }
    val tag = "reader-${publication.metadata.identifier ?: containerId}"
    var fragment by remember { mutableStateOf<EpubNavigatorFragment?>(null) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            FragmentContainerView(ctx).apply {
                id = containerId
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
        },
    )

    DisposableEffect(publication) {
        val fm = activity.supportFragmentManager
        val factory = EpubNavigatorFactory(publication)
        fm.fragmentFactory = factory.createFragmentFactory(
            initialLocator = initialLocator,
            initialPreferences = preferences.toEpubPreferences(),
        )
        val nav = (fm.fragmentFactory.instantiate(
            activity.classLoader,
            EpubNavigatorFragment::class.java.name,
        ) as EpubNavigatorFragment)
        fm.beginTransaction()
            .replace(containerId, nav, tag)
            .commitNow()
        fragment = nav
        onNavigatorReady(nav)

        val job = activity.lifecycleScope.launch {
            nav.currentLocator.collect { onLocator(it) }
        }

        onDispose {
            job.cancel()
            fragment = null
            onNavigatorReady(null)
            fm.beginTransaction()
                .remove(nav)
                .commitNowAllowingStateLoss()
        }
    }

    LaunchedEffect(preferences) {
        fragment?.submitPreferences(preferences.toEpubPreferences())
    }
}
```

- [ ] **Step 3: Build the app**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Sanity check by hand**

Open a book. Tap on the right third — page advances. Tap on the left third — page goes back. Tap center — chrome toggles. Try at the very first page (back tap is a no-op) and very last page (forward tap is a no-op).

---

## Task 3.4 — Add the toggle row to `FontSettingsSheet`

**Files:**
- Modify: `app/src/main/java/io/theficos/ereader/ui/reader/FontSettingsSheet.kt`

- [ ] **Step 1: Inspect the existing sheet**

Run: `cat app/src/main/java/io/theficos/ereader/ui/reader/FontSettingsSheet.kt`

Identify where preferences are mutated (look for an `onChange(prefs.copy(...))` pattern). The new row mirrors that style.

- [ ] **Step 2: Add the toggle row**

At the bottom of the sheet content (before the final closing brace of the `Column` that holds the existing controls), add:

```kotlin
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Tap to turn pages", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Tap left or right edges to flip pages",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = prefs.tapNavigationEnabled,
                    onCheckedChange = { onChange(prefs.copy(tapNavigationEnabled = it)) },
                )
            }
```

Add these imports next to the existing ones:

```kotlin
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
```

If any of those are already imported, skip them — duplicates fail to compile.

- [ ] **Step 3: Build to confirm**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Sanity check**

Open the font sheet from the reader, toggle "Tap to turn pages" off, and verify left/right tap zones become passive (only center toggles chrome). Toggle on, verify they reactivate. Restart the app, verify the preference persisted.

- [ ] **Step 5: Commit phase 3**

```bash
git add reader/src/main/java/io/theficos/ereader/reader app/src/main/java/io/theficos/ereader/ui/reader reader/src/test/java/io/theficos/ereader/reader/ReaderPreferencesStoreTest.kt
git status
git commit -m "$(cat <<'EOF'
:sparkles: feat(reader): tap left/right to turn pages (toggleable)

Three full-height tap zones over the navigator: left previous, center
chrome (existing), right next. The navigator fragment ref is lifted
into ReaderViewModel so taps drive goForward/goBackward. A new
ReaderPreferences.tapNavigationEnabled flag (default on) gates the
behaviour and lives next to the font controls in FontSettingsSheet.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Phase 4 — Library sort + finished badge

Files touched:
- Create: `app/src/main/java/io/theficos/ereader/ui/library/LibrarySort.kt`
- Create: `app/src/main/java/io/theficos/ereader/ui/library/LibraryPreferencesStore.kt`
- Create: `app/src/test/java/io/theficos/ereader/ui/library/LibraryPreferencesStoreTest.kt`
- Modify: `app/src/main/java/io/theficos/ereader/di/AppContainer.kt`
- Modify: `app/src/main/java/io/theficos/ereader/ui/library/LibraryViewModel.kt`
- Modify: `app/src/main/java/io/theficos/ereader/ui/library/LibraryScreen.kt`
- Modify: `app/src/test/java/io/theficos/ereader/ui/library/LibraryViewModelTest.kt`

---

## Task 4.1 — Define `LibrarySort` and `LibraryPreferencesStore`

**Files:**
- Create: `app/src/main/java/io/theficos/ereader/ui/library/LibrarySort.kt`
- Create: `app/src/main/java/io/theficos/ereader/ui/library/LibraryPreferencesStore.kt`
- Create: `app/src/test/java/io/theficos/ereader/ui/library/LibraryPreferencesStoreTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/io/theficos/ereader/ui/library/LibraryPreferencesStoreTest.kt`:

```kotlin
package io.theficos.ereader.ui.library

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class LibraryPreferencesStoreTest {

    private fun fresh() = LibraryPreferencesStore(ApplicationProvider.getApplicationContext()).also {
        it.update(LibrarySort.RECENTLY_READ)
    }

    @Test fun `default sort is RECENTLY_READ`() {
        val store = fresh()
        assertThat(store.flow.value).isEqualTo(LibrarySort.RECENTLY_READ)
    }

    @Test fun `sort round-trips through update and reload`() {
        val store1 = fresh()
        store1.update(LibrarySort.AUTHOR)
        assertThat(store1.flow.value).isEqualTo(LibrarySort.AUTHOR)

        val store2 = LibraryPreferencesStore(ApplicationProvider.getApplicationContext())
        assertThat(store2.flow.value).isEqualTo(LibrarySort.AUTHOR)
    }

    @Test fun `unknown stored value falls back to default`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.getSharedPreferences("library_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putString("library_sort", "NONSENSE").apply()
        val store = LibraryPreferencesStore(ctx)
        assertThat(store.flow.value).isEqualTo(LibrarySort.RECENTLY_READ)
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.theficos.ereader.ui.library.LibraryPreferencesStoreTest"`
Expected: compile error — `LibrarySort` and `LibraryPreferencesStore` do not exist.

- [ ] **Step 3: Create `LibrarySort.kt`**

```kotlin
package io.theficos.ereader.ui.library

enum class LibrarySort { RECENTLY_READ, RECENTLY_ADDED, TITLE, AUTHOR }
```

- [ ] **Step 4: Create `LibraryPreferencesStore.kt`**

```kotlin
package io.theficos.ereader.ui.library

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LibraryPreferencesStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("library_prefs", Context.MODE_PRIVATE)

    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<LibrarySort> = _flow.asStateFlow()

    fun update(sort: LibrarySort) {
        prefs.edit().putString(KEY_SORT, sort.name).apply()
        _flow.value = sort
    }

    private fun load(): LibrarySort {
        val raw = prefs.getString(KEY_SORT, LibrarySort.RECENTLY_READ.name)
            ?: LibrarySort.RECENTLY_READ.name
        return runCatching { LibrarySort.valueOf(raw) }.getOrDefault(LibrarySort.RECENTLY_READ)
    }

    private companion object {
        const val KEY_SORT = "library_sort"
    }
}
```

- [ ] **Step 5: Run the test and confirm it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.theficos.ereader.ui.library.LibraryPreferencesStoreTest"`
Expected: PASS.

---

## Task 4.2 — Wire `LibraryPreferencesStore` into the DI container

**Files:**
- Modify: `app/src/main/java/io/theficos/ereader/di/AppContainer.kt`

- [ ] **Step 1: Add the field**

Add this import:

```kotlin
import io.theficos.ereader.ui.library.LibraryPreferencesStore
```

…and add this field next to `readerPreferencesStore`:

```kotlin
    val libraryPreferencesStore = LibraryPreferencesStore(appContext)
```

- [ ] **Step 2: Build to confirm**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

(The store is wired into `LibraryViewModel` at the call site in MainActivity / NavGraph / wherever the VM is instantiated — covered in Task 4.4 along with the UI work, after the VM signature changes.)

---

## Task 4.3 — Sort, finished filter, and `finishedAt` exposure in `LibraryViewModel`

**Files:**
- Modify: `app/src/main/java/io/theficos/ereader/ui/library/LibraryViewModel.kt`
- Modify: `app/src/test/java/io/theficos/ereader/ui/library/LibraryViewModelTest.kt`

- [ ] **Step 1: Write failing tests**

Append to `LibraryViewModelTest.kt` (note: the existing setUp does **not** pass `LibraryPreferencesStore` yet — the new constructor parameter from Step 3 below is what causes the existing tests to also need updating; do that in this step):

First, change the existing setUp's `LibraryViewModel(...)` construction to pass a `LibraryPreferencesStore`:

```kotlin
        vm = LibraryViewModel(
            docs = docs,
            progress = progress,
            syncOrchestrator = orchestrator,
            booksDir = File("/dev/null"),
            libraryPreferencesStore = LibraryPreferencesStore(ApplicationProvider.getApplicationContext()),
            nowMillis = { 999L },
        )
```

…and add this import at the top of the file:

```kotlin
import io.theficos.ereader.core.model.Progress as DomainProgress
```

Then append these tests:

```kotlin
    private suspend fun seed(
        contentHash: String, title: String, author: String?,
        percent: Double = 0.0, updatedAt: Long = 0L, finishedAt: Long? = null,
    ): Long {
        val docId = db.documentDao().insert(DocumentEntity(
            metadataId = contentHash, contentHash = contentHash, title = title, author = author,
            downloadUrl = "u", localPath = "p", coverPath = null, downloadedAt = 0,
        ))
        if (percent > 0.0 || finishedAt != null) {
            progress.save(DomainProgress(
                documentId = docId, locator = "loc", percent = percent,
                updatedAt = updatedAt, finishedAt = finishedAt,
            ))
        }
        return docId
    }

    @Test fun `default sort is RECENTLY_READ ordering by progressUpdatedAt desc`() = runTest {
        seed("h1", "Alpha", "Auth", percent = 0.2, updatedAt = 100L)
        seed("h2", "Bravo", "Auth", percent = 0.4, updatedAt = 300L)
        seed("h3", "Charlie", "Auth", percent = 0.1, updatedAt = 200L)
        vm.items.test {
            val list = awaitItem().filter { it.document.title in setOf("Alpha", "Bravo", "Charlie") }
            // Skip empty initial emission if any
            val final = if (list.isEmpty()) awaitItem() else list
            assertThat(final.map { it.document.title }).containsExactly("Bravo", "Charlie", "Alpha").inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `TITLE sort orders alphabetically`() = runTest {
        seed("h1", "Charlie", null)
        seed("h2", "Alpha", null)
        seed("h3", "Bravo", null)
        vm.setSort(LibrarySort.TITLE)
        vm.items.test {
            val final = awaitItem().also { if (it.size < 3) awaitItem() }
            val titles = final.map { it.document.title }
            assertThat(titles).containsExactly("Alpha", "Bravo", "Charlie").inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `finished books are excluded from continueReading`() = runTest {
        seed("h1", "InProgress", null, percent = 0.5, updatedAt = 100L)
        seed("h2", "Finished", null, percent = 0.99, updatedAt = 200L, finishedAt = 200L)
        vm.continueReading.test {
            // skip nulls until we get a value or stable null
            var emission = awaitItem()
            // Wait one more tick if needed
            if (emission?.document?.title != "InProgress") emission = awaitItem()
            assertThat(emission?.document?.title).isEqualTo("InProgress")
            cancelAndIgnoreRemainingEvents()
        }
    }
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "io.theficos.ereader.ui.library.LibraryViewModelTest"`
Expected: compile error — `LibraryViewModel` does not accept `libraryPreferencesStore`, and there is no `setSort` method.

- [ ] **Step 3: Update `LibraryViewModel`**

Replace the entire content of `LibraryViewModel.kt` with:

```kotlin
package io.theficos.ereader.ui.library

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.ereader.core.model.Document
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.local.ProgressRepository
import io.theficos.ereader.data.sync.SyncEnqueuer
import io.theficos.ereader.data.sync.SyncOrchestrator
import io.theficos.ereader.data.sync.SyncResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

sealed interface LibraryEvent {
    data object RestartFailed : LibraryEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val docs: DocumentRepository,
    private val progress: ProgressRepository,
    private val syncOrchestrator: SyncOrchestrator,
    private val booksDir: File,
    private val libraryPreferencesStore: LibraryPreferencesStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val syncEnqueuer: (Context) -> Unit = { SyncEnqueuer.enqueue(it, expedited = true, replaceExisting = true) },
) : ViewModel() {

    val sort: StateFlow<LibrarySort> = libraryPreferencesStore.flow

    fun setSort(next: LibrarySort) = libraryPreferencesStore.update(next)

    private val rows: StateFlow<List<LibraryRow>> =
        docs.observeLibrary()
            .flatMapLatest { docList ->
                if (docList.isEmpty()) flowOf(emptyList())
                else combine(docList.map { d -> progress.observe(d.id).map { d to it } }) { it.toList() }
            }
            .map { pairs ->
                pairs.map { (d, p) ->
                    LibraryRow(
                        document = d,
                        percent = p?.percent ?: 0.0,
                        progressUpdatedAt = p?.updatedAt ?: 0L,
                        finishedAt = p?.finishedAt,
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val items: StateFlow<List<LibraryRow>> = combine(rows, sort) { list, by ->
        applySort(list, by)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val continueReading: StateFlow<LibraryRow?> = rows
        .map { list ->
            list
                .filter { it.percent > 0.0001 && it.finishedAt == null }
                .maxByOrNull { it.progressUpdatedAt }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _events = MutableSharedFlow<LibraryEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<LibraryEvent> = _events.asSharedFlow()

    fun delete(document: Document) {
        viewModelScope.launch { docs.delete(document) }
    }

    suspend fun restart(document: Document, alsoDeleteFile: Boolean): Boolean {
        progress.resetForDocument(document.id, now = nowMillis())
        val pushed = syncOrchestrator.runOnce()
        return when (pushed) {
            is SyncResult.Success -> {
                if (alsoDeleteFile) docs.delete(document)
                true
            }
            else -> {
                _events.tryEmit(LibraryEvent.RestartFailed)
                false
            }
        }
    }

    fun restartFromUi(document: Document, alsoDeleteFile: Boolean, context: Context) {
        viewModelScope.launch {
            if (!restart(document, alsoDeleteFile)) {
                syncEnqueuer(context)
            }
        }
    }

    private fun applySort(list: List<LibraryRow>, by: LibrarySort): List<LibraryRow> = when (by) {
        LibrarySort.RECENTLY_READ -> list.sortedWith(
            compareByDescending<LibraryRow> { it.progressUpdatedAt }
                .thenBy { it.document.title.lowercase() }
        )
        LibrarySort.RECENTLY_ADDED -> list.sortedByDescending { it.document.id }
        LibrarySort.TITLE -> list.sortedBy { it.document.title.lowercase() }
        LibrarySort.AUTHOR -> list.sortedWith(
            compareBy<LibraryRow> { it.document.author?.lowercase() ?: "￿" }
                .thenBy { it.document.title.lowercase() }
        )
    }
}

data class LibraryRow(
    val document: Document,
    val percent: Double,
    val progressUpdatedAt: Long,
    val finishedAt: Long? = null,
)
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: all `LibraryViewModelTest` cases pass.

---

## Task 4.4 — UI: sort dropdown, finished badge, VM construction at the call site

**Files:**
- Modify: `app/src/main/java/io/theficos/ereader/ui/library/LibraryScreen.kt`
- Modify: wherever `LibraryViewModel` is constructed (search and update)

- [ ] **Step 1: Find where the VM is constructed**

Run: `grep -rn "LibraryViewModel(" app/src/main 2>/dev/null`
Expected: at least one call site outside of tests. It's likely in a `MainActivity.kt` or `NavGraph.kt`. Open that file.

- [ ] **Step 2: Pass the new dependency**

Add the constructor argument:

```kotlin
                libraryPreferencesStore = container.libraryPreferencesStore,
```

…where `container` is the existing `AppContainer` reference at that call site (the variable name may differ — match the local scope).

- [ ] **Step 3: Build to confirm**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Add the sort row to `LibraryScreen.kt`**

In `LibraryScreen.kt`:

1. Add these imports:

```kotlin
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
```

2. Replace the `item(span = { GridItemSpan(maxLineSpan) }) { Text(text = "Quire", ... ) }` block with this header item that hosts the title + sort dropdown:

```kotlin
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Quire",
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    var sortMenuOpen by remember { mutableStateOf(false) }
                    val currentSort by viewModel.sort.collectAsState()
                    Box {
                        IconButton(onClick = { sortMenuOpen = true }) {
                            Icon(Icons.Filled.Sort, contentDescription = "Sort")
                        }
                        DropdownMenu(
                            expanded = sortMenuOpen,
                            onDismissRequest = { sortMenuOpen = false },
                        ) {
                            sortLabels.forEach { (key, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    leadingIcon = if (currentSort == key) {
                                        { Icon(Icons.Filled.Check, contentDescription = null) }
                                    } else null,
                                    onClick = {
                                        viewModel.setSort(key)
                                        sortMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
```

3. At the file-level (outside any composable, near the top), add the sort labels list:

```kotlin
private val sortLabels: List<Pair<LibrarySort, String>> = listOf(
    LibrarySort.RECENTLY_READ to "Recently read",
    LibrarySort.RECENTLY_ADDED to "Recently added",
    LibrarySort.TITLE to "Title",
    LibrarySort.AUTHOR to "Author",
)
```

…and import `LibrarySort` if not already imported:

```kotlin
import io.theficos.ereader.ui.library.LibrarySort
```

4. Add the finished badge to each book cell. Find the `Column { CoverImage(...) Text(...) }` rendering the book cell. Wrap the `CoverImage` in a `Box` and overlay the badge when `row.finishedAt != null`:

```kotlin
                Column(
                    modifier = Modifier.combinedClickable(
                        onClick = { onOpenBook(row.document.id) },
                        onLongClick = { menuFor = row.document },
                    ),
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        CoverImage(
                            source = row.document.coverPath,
                            title = row.document.title,
                            author = row.document.author,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(2f / 3f),
                        )
                        if (row.finishedAt != null) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp)
                                    .size(24.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = "Finished",
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        text = row.document.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
```

- [ ] **Step 5: Build and sanity check**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

By hand: open the Library, tap the Sort icon, switch sort orders, confirm grid order changes. Finish a book (or seed a `finishedAt` via the reader); confirm the checkmark badge appears on its cover and it leaves Continue Reading.

- [ ] **Step 6: Commit phase 4**

```bash
git add app/src/main/java/io/theficos/ereader/ui/library app/src/test/java/io/theficos/ereader/ui/library app/src/main/java/io/theficos/ereader/di/AppContainer.kt
# Also add the call site that constructs LibraryViewModel
git add $(grep -rl "LibraryViewModel(" app/src/main | tr '\n' ' ')
git status
git commit -m "$(cat <<'EOF'
:sparkles: feat(library): sort options and finished badge

LibrarySort (Recently read / Recently added / Title / Author) persists
through a new sibling LibraryPreferencesStore; sort applies in-memory
inside LibraryViewModel. LibraryRow now exposes finishedAt; book cells
render a small checkmark badge on finished covers and Continue Reading
filters out finished books — the user-visible payoff of phase 1.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Phase 5 — Library search

Files touched:
- Modify: `app/src/main/java/io/theficos/ereader/ui/library/LibraryViewModel.kt`
- Modify: `app/src/main/java/io/theficos/ereader/ui/library/LibraryScreen.kt`
- Modify: `app/src/test/java/io/theficos/ereader/ui/library/LibraryViewModelTest.kt`

---

## Task 5.1 — Add `query` state and filtering to `LibraryViewModel`

**Files:**
- Modify: `app/src/main/java/io/theficos/ereader/ui/library/LibraryViewModel.kt`
- Modify: `app/src/test/java/io/theficos/ereader/ui/library/LibraryViewModelTest.kt`

- [ ] **Step 1: Write the failing tests**

Append to `LibraryViewModelTest.kt`:

```kotlin
    @Test fun `query filters by title case-insensitively`() = runTest {
        seed("h1", "Alpha", "Auth")
        seed("h2", "BRAVO", "Auth")
        seed("h3", "Charlie", "Auth")
        vm.setSort(LibrarySort.TITLE)
        vm.setQuery("bra")
        vm.items.test {
            val final = awaitItem().also { if (it.size != 1) awaitItem() }
            assertThat(final.map { it.document.title }).containsExactly("BRAVO")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `query filters by author`() = runTest {
        seed("h1", "Alpha", "King")
        seed("h2", "Bravo", "Tolkien")
        vm.setSort(LibrarySort.TITLE)
        vm.setQuery("tolk")
        vm.items.test {
            val final = awaitItem().also { if (it.size != 1) awaitItem() }
            assertThat(final.map { it.document.title }).containsExactly("Bravo")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `clearing query restores full list`() = runTest {
        seed("h1", "Alpha", null)
        seed("h2", "Bravo", null)
        vm.setSort(LibrarySort.TITLE)
        vm.setQuery("alpha")
        // wait for filter to apply
        vm.items.test {
            awaitItem()
            vm.setQuery("")
            val final = awaitItem().also { if (it.size != 2) awaitItem() }
            assertThat(final).hasSize(2)
            cancelAndIgnoreRemainingEvents()
        }
    }
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "io.theficos.ereader.ui.library.LibraryViewModelTest"`
Expected: compile error — `setQuery` does not exist.

- [ ] **Step 3: Add the query state and filter pipeline**

Open `LibraryViewModel.kt`. Make these targeted edits:

1. Add the new state field after `sort`:

```kotlin
    private val _query = kotlinx.coroutines.flow.MutableStateFlow("")
    val query: StateFlow<String> = _query

    fun setQuery(next: String) { _query.value = next }
```

2. Replace the `items` declaration with a three-way combine:

```kotlin
    val items: StateFlow<List<LibraryRow>> = combine(rows, sort, _query) { list, by, q ->
        val sorted = applySort(list, by)
        if (q.isBlank()) sorted else {
            val needle = q.trim().lowercase()
            sorted.filter { row ->
                row.document.title.lowercase().contains(needle) ||
                    (row.document.author?.lowercase()?.contains(needle) == true)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
```

`continueReading` is intentionally **not** filtered by `_query` (per spec).

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.theficos.ereader.ui.library.LibraryViewModelTest"`
Expected: all tests pass.

---

## Task 5.2 — Add the search field to `LibraryScreen`

**Files:**
- Modify: `app/src/main/java/io/theficos/ereader/ui/library/LibraryScreen.kt`

- [ ] **Step 1: Add the search button and inline field**

In `LibraryScreen.kt`:

1. Add these imports:

```kotlin
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
```

2. In `LibraryScreen`, add a remembered "search active" flag near the existing `var menuFor by remember ...`:

```kotlin
    var searchActive by remember { mutableStateOf(false) }
    val query by viewModel.query.collectAsState()
```

3. In the header `Row` from Task 4.4, add a Search icon button right before the Sort icon button:

```kotlin
                    IconButton(onClick = { searchActive = true }) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
```

4. Replace the `SectionLabel("Library · ${items.size}")` item with a conditional that swaps in an `OutlinedTextField` while search is active:

```kotlin
            item(span = { GridItemSpan(maxLineSpan) }) {
                if (searchActive) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { viewModel.setQuery(it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search library") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            imeAction = ImeAction.Search,
                        ),
                        trailingIcon = {
                            IconButton(onClick = {
                                viewModel.setQuery("")
                                searchActive = false
                            }) {
                                Icon(Icons.Filled.Close, contentDescription = "Close search")
                            }
                        },
                    )
                } else {
                    SectionLabel("Library · ${items.size}")
                }
            }
```

5. Add an empty-result hint right after the `itemsIndexed(...)` block, but before the `LazyVerticalGrid`'s closing brace, so it shows only when there's a query and no rows:

```kotlin
            if (searchActive && query.isNotBlank() && items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = "No matches in your library",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
```

- [ ] **Step 2: Build and sanity check**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

By hand: open Library, tap the Search icon, type a partial title, confirm filtering. Type something with no matches, confirm the inline hint. Tap the close icon, confirm the field collapses and the full list returns. Continue Reading should remain visible and unfiltered.

- [ ] **Step 3: Commit phase 5**

```bash
git add app/src/main/java/io/theficos/ereader/ui/library app/src/test/java/io/theficos/ereader/ui/library
git status
git commit -m "$(cat <<'EOF'
:sparkles: feat(library): local search by title and author

Inline search field replaces the section label while active; query is
combined with sort in LibraryViewModel and filters case-insensitively
on title and author. Continue Reading is unaffected.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Phase 6 — Catalog refresh on credential change

Files touched in this phase:

- Modify: `auth/src/main/java/io/theficos/ereader/auth/CalibreCredentialStore.kt` — add observable `flow: StateFlow<CalibreCredentials?>`.
- Modify: `app/src/main/java/io/theficos/ereader/ui/catalog/CatalogViewModel.kt` — observe credential flow; re-run `loadRoot()` on baseUrl change. Expose `refresh()` for pull-to-refresh.
- Modify: `app/src/main/java/io/theficos/ereader/ui/catalog/CatalogScreen.kt` — wrap the catalog list in a Material 3 `PullToRefreshBox`.
- Create: `auth/src/test/java/io/theficos/ereader/auth/CalibreCredentialStoreTest.kt` — flow round-trip tests.
- Modify: `app/src/test/java/io/theficos/ereader/ui/catalog/CatalogViewModelTest.kt` if it exists (else create) — credential-change triggers refetch.

Phase ends with one commit:
`:sparkles: feat(catalog): refresh on credential change and pull-to-refresh`

---

## Task 6.1 — Make `CalibreCredentialStore` observable

**Files:**
- Modify: `auth/src/main/java/io/theficos/ereader/auth/CalibreCredentialStore.kt`
- Create: `auth/src/test/java/io/theficos/ereader/auth/CalibreCredentialStoreTest.kt`

- [ ] **Step 1: Write the failing test**

Create `CalibreCredentialStoreTest.kt`:

```kotlin
package io.theficos.ereader.auth

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CalibreCredentialStoreTest {

    @Test fun `flow emits null when nothing stored`() {
        val store = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        store.clear()
        assertThat(store.flow.value).isNull()
    }

    @Test fun `put updates flow synchronously`() {
        val store = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        store.clear()
        store.put(CalibreCredentials("https://example", "u", "p"))
        assertThat(store.flow.value).isEqualTo(CalibreCredentials("https://example", "u", "p"))
    }

    @Test fun `clear emits null`() {
        val store = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        store.put(CalibreCredentials("https://example", "u", "p"))
        store.clear()
        assertThat(store.flow.value).isNull()
    }

    @Test fun `flow value matches get`() {
        val store = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        store.put(CalibreCredentials("https://example", "u", "p"))
        assertThat(store.flow.value).isEqualTo(store.get())
    }
}
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `./gradlew :auth:testDebugUnitTest --tests "io.theficos.ereader.auth.CalibreCredentialStoreTest"`
Expected: compile error — `CalibreCredentialStore` has no `flow` member.

- [ ] **Step 3: Add the flow to `CalibreCredentialStore`**

Replace the file with:

```kotlin
package io.theficos.ereader.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CalibreCredentialStore(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "calibre_creds",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<CalibreCredentials?> = _flow.asStateFlow()

    fun get(): CalibreCredentials? = _flow.value

    fun put(creds: CalibreCredentials) {
        prefs.edit()
            .putString(KEY_BASE_URL, creds.baseUrl)
            .putString(KEY_USER, creds.username)
            .putString(KEY_PASS, creds.password)
            .apply()
        _flow.value = creds
    }

    fun clear() {
        prefs.edit().clear().commit()
        _flow.value = null
    }

    private fun load(): CalibreCredentials? {
        val baseUrl = prefs.getString(KEY_BASE_URL, null) ?: return null
        val user = prefs.getString(KEY_USER, null) ?: return null
        val pass = prefs.getString(KEY_PASS, null) ?: return null
        return CalibreCredentials(baseUrl, user, pass)
    }

    private companion object {
        const val KEY_BASE_URL = "base_url"
        const val KEY_USER = "username"
        const val KEY_PASS = "password"
    }
}
```

`get()` now reads from the flow value (which is hydrated from disk on construction), so existing call sites continue to work without change.

- [ ] **Step 4: Run tests**

Run: `./gradlew :auth:testDebugUnitTest`
Expected: all tests pass.

---

## Task 6.2 — `CatalogViewModel` observes credential changes; expose `refresh()`

**Files:**
- Modify: `app/src/main/java/io/theficos/ereader/ui/catalog/CatalogViewModel.kt`

- [ ] **Step 1: Subscribe to credential flow in `init {}`**

Add this block after the existing `downloadedUrls` declaration (or anywhere in the class body, before the existing functions):

```kotlin
    init {
        viewModelScope.launch {
            credentialStore.flow
                .map { it?.baseUrl }
                .distinctUntilChanged()
                .collect { baseUrl ->
                    if (!baseUrl.isNullOrBlank()) loadRoot()
                }
        }
    }
```

Add imports:

```kotlin
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
```

(The first may already be imported; only add what's missing.)

- [ ] **Step 2: Add a public `refresh()` for pull-to-refresh**

Add right after `loadRoot()`:

```kotlin
    fun refresh() {
        val current = _state.value as? CatalogUiState.Loaded ?: return loadRoot()
        load(current.url)
    }
```

`refresh()` re-fetches the URL the user is currently viewing (so it
respects the breadcrumb back stack); falls back to `loadRoot()` if no
feed is loaded.

- [ ] **Step 3: Build and confirm**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

---

## Task 6.3 — Wrap CatalogScreen list in `PullToRefreshBox`

**Files:**
- Modify: `app/src/main/java/io/theficos/ereader/ui/catalog/CatalogScreen.kt`

- [ ] **Step 1: Read the file to find the lazy column**

Run: `grep -n "LazyColumn\|LazyVerticalGrid\|CatalogUiState.Loaded" app/src/main/java/io/theficos/ereader/ui/catalog/CatalogScreen.kt`

Identify the composable that renders the loaded feed list — wrap that in `PullToRefreshBox`.

- [ ] **Step 2: Wrap the loaded-state content with `PullToRefreshBox`**

Add imports:

```kotlin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
```

Surround the content rendered for `CatalogUiState.Loaded` with:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
PullToRefreshBox(
    isRefreshing = state is CatalogUiState.Loading,
    onRefresh = { viewModel.refresh() },
    modifier = Modifier.fillMaxSize(),
) {
    // existing list content here, unchanged
}
```

If the screen already has an `@OptIn(ExperimentalMaterial3Api::class)` at the file or function level, don't duplicate it.

- [ ] **Step 3: Build and sanity check**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

By hand: open Catalog, pull down — should show the spinner and refetch the current feed. Save bad creds in Settings, see the Catalog show an error; correct the creds and save — the Catalog should refetch automatically without a process restart.

- [ ] **Step 4: Commit Phase 6**

```bash
git add auth/src/main/java/io/theficos/ereader/auth/CalibreCredentialStore.kt auth/src/test/java/io/theficos/ereader/auth/CalibreCredentialStoreTest.kt app/src/main/java/io/theficos/ereader/ui/catalog/CatalogViewModel.kt app/src/main/java/io/theficos/ereader/ui/catalog/CatalogScreen.kt
git status
git commit -m "$(cat <<'EOF'
:sparkles: feat(catalog): refresh on credential change and pull-to-refresh

CalibreCredentialStore now exposes a StateFlow that emits on put()
and clear(). CatalogViewModel subscribes in init and re-runs
loadRoot() when the baseUrl changes, so saving corrected credentials
in Settings refreshes the Catalog without restarting the app. A
Material 3 PullToRefreshBox in CatalogScreen gives users a manual
escape hatch for transient fetch failures.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Phase 7 — Sync re-attach for newly-downloaded books

Files touched in this phase:

- Modify: `app/src/main/java/io/theficos/ereader/ui/catalog/CatalogViewModel.kt` — after a successful download, clear the progress sync cursor and enqueue a sync.
- Modify: `app/src/test/java/io/theficos/ereader/ui/catalog/CatalogViewModelTest.kt` if it exists (else create) — verify the cursor is cleared on success.

Phase ends with one commit:
`:bug: fix(sync): re-pull progress for newly-downloaded books`

---

## Task 7.1 — Reset progress sync cursor on download success

**Files:**
- Modify: `app/src/main/java/io/theficos/ereader/ui/catalog/CatalogViewModel.kt`

- [ ] **Step 1: Inject the sync state DAO and the sync enqueuer**

Open `CatalogViewModel.kt`. The constructor currently takes
`(client, downloader, docs, credentialStore)`. Extend it:

```kotlin
class CatalogViewModel(
    private val client: OpdsClient,
    private val downloader: BookDownloader,
    private val docs: DocumentRepository,
    private val credentialStore: CalibreCredentialStore,
    private val syncStateDao: io.theficos.ereader.data.local.db.SyncStateDao,
    private val syncEnqueuer: (android.content.Context) -> Unit =
        { ctx -> io.theficos.ereader.data.sync.SyncEnqueuer.enqueue(ctx, expedited = true, replaceExisting = true) },
)
```

(Resolve the imports at the top of the file rather than fully-qualified
names if cleaner — both forms compile.)

- [ ] **Step 2: Wire the new dependencies in `AppContainer`**

Open `app/src/main/java/io/theficos/ereader/di/AppContainer.kt` and update
the `CatalogViewModel` construction (search for `CatalogViewModel(`).
Pass `syncStateDao = syncStateDao` and leave `syncEnqueuer` as default.

If the VM is constructed in `AppNavGraph.kt` instead (or elsewhere),
update that call site. Run `grep -rn "CatalogViewModel(" app/src/main`
to find every site.

- [ ] **Step 3: Reset cursor in the download success path**

In `CatalogViewModel.download(...)`, locate the `runCatching { ... }
.onSuccess { ... }` block (or whatever pattern handles success). After
`docs.upsert(...)` (or whichever call inserts the downloaded `documents`
row) and BEFORE the state mutation that marks `lastDownloaded`, add:

```kotlin
                    // The book that just landed may have server-side progress that an
                    // earlier pull silently dropped (no local doc to attach to). Reset the
                    // progress sync cursor so the next pull re-fetches every row from
                    // epoch 0; the now-present local doc lets it attach.
                    syncStateDao.clearAll()
                    syncEnqueuer(context)
```

`context` is required for `SyncEnqueuer.enqueue`. The download call
already takes a `Context` from the calling site (see the Library's
`restartFromUi(... context: Context)` for the established pattern).
If `download()` does not currently accept a `Context` parameter, add
one and update its call sites in `CatalogScreen` to pass
`LocalContext.current`.

- [ ] **Step 4: Verify build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

---

## Task 7.2 — Test: cursor cleared and sync enqueued on download success

**Files:**
- Create or modify: `app/src/test/java/io/theficos/ereader/ui/catalog/CatalogViewModelTest.kt`

- [ ] **Step 1: Write the test**

If the test file does not yet exist, create one with the standard
Robolectric+Truth setup (mirror the structure of `LibraryViewModelTest`).
Either way, add:

```kotlin
@Test fun `successful download clears progress sync cursor and enqueues sync`() = runTest {
    var enqueueCount = 0
    // Construct the VM with a stub `syncEnqueuer` that increments the counter.
    // Seed `sync_state` with a non-zero cursor.
    db.syncStateDao().set(SyncStateEntity("progress", lastPulledAt = 12345L))

    // Stub the downloader so that `download(...)` returns a fake File and
    // `downloadCover(...)` is a no-op. (Use a temp file like the existing
    // LibraryViewModelTest does.)

    // Simulate a successful download by calling vm.download(...) with a
    // fixture OpdsPublication.
    vm.download(/* publication fixture */)
    advanceUntilIdle()

    assertThat(db.syncStateDao().lastPulled("progress")).isNull()
    assertThat(enqueueCount).isEqualTo(1)
}
```

The exact construction of the test fixture (downloader stub, OpdsPublication
fixture) follows the patterns already in this codebase. If those patterns
are absent for CatalogViewModel, prefer the simpler integration form:

```kotlin
@Test fun `successful download clears progress sync cursor`() = runTest {
    db.syncStateDao().set(SyncStateEntity("progress", lastPulledAt = 12345L))
    // Manually invoke the same mutation the download success branch makes.
    // (If the VM has an internal helper, call it; otherwise, exercise it
    // through the public download() path with a stub downloader returning
    // a temp file.)
    ...
    assertThat(db.syncStateDao().lastPulled("progress")).isNull()
}
```

If a full VM-level integration test is too heavy here, the production
behaviour is also covered by reading the diff and confirming the
two-line addition to the success branch. In that case, document the
gap explicitly in the commit message and proceed.

- [ ] **Step 2: Run tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: all tests pass.

- [ ] **Step 3: Commit Phase 7**

```bash
git add app/src/main/java/io/theficos/ereader/ui/catalog/CatalogViewModel.kt app/src/main/java/io/theficos/ereader/di/AppContainer.kt
# Plus any test files that changed
git status
git commit -m "$(cat <<'EOF'
:bug: fix(sync): re-pull progress for newly-downloaded books

When a download completes, clear the progress sync cursor and enqueue
an expedited sync. The next pull starts from epoch 0 so any
server-side progress for the newly-downloaded book attaches to the
just-inserted local document. Without this, the pull's high-water
mark advances past the row when no local doc matched, leaving the
book stuck at chapter 1 even though the server has progress.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Final verification

- [ ] **Step 1: Run the full Android suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL across all modules.

- [ ] **Step 2: Run the full server suite**

Run: `cd server && uv run pytest -v`
Expected: all tests pass.

- [ ] **Step 3: Confirm the branch has seven logical commits**

Run: `git log --oneline main..feat/reader-library-pack`
Expected output (commit shas will differ):

```
<sha> :bug: fix(sync): re-pull progress for newly-downloaded books
<sha> :sparkles: feat(catalog): refresh on credential change and pull-to-refresh
<sha> :sparkles: feat(library): local search by title and author
<sha> :sparkles: feat(library): sort options and finished badge
<sha> :sparkles: feat(reader): tap left/right to turn pages (toggleable)
<sha> :sparkles: feat(reader): keep screen on while reading
<sha> :bug: fix(reader): track finished books and stop the 99% Continue Reading loop
<sha> :memo: docs: reader & library feature pack implementation plan
<sha> :memo: docs: reader & library feature pack spec
```

- [ ] **Step 4: Hand off**

Open the PR with the spec and plan paths in the description and request review.
