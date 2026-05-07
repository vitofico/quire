# Reset sync, restart book, clean local books — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add three local-state operations (reset sync cursor, restart a book, remove all downloaded books) without changing the sync wire format or DB schema.

**Architecture:** Two new Settings actions clear local state only. Per-book "Restart" writes a `percent=0, locator=""` row (already-supported by Readium's load path) and pushes synchronously through the existing `SyncOrchestrator` before optionally deleting the file — so the LWW push wins on other devices and the FK cascade can't eat the dirty row.

**Tech Stack:** Kotlin, Room, Jetpack Compose, kotlinx.coroutines, JUnit + Robolectric + Truth + Turbine. Build runs in Docker via `scripts/dgradle`.

**Spec:** [`docs/superpowers/specs/2026-05-07-reset-and-clean-design.md`](../specs/2026-05-07-reset-and-clean-design.md)

---

## File Structure

| Module | Path | Change |
|---|---|---|
| `data:local` | `data/local/src/main/java/.../db/SyncStateDao.kt` | Add `clearAll()` |
| `data:local` | `data/local/src/main/java/.../db/DocumentDao.kt` | Add `deleteAll()` |
| `data:local` | `data/local/src/main/java/.../DocumentRepository.kt` | Add `deleteAll()` |
| `data:local` | `data/local/src/main/java/.../ProgressRepository.kt` | Add `resetForDocument(documentId, now)` |
| `data:local` | `data/local/src/test/...` | Tests for the four additions |
| `app` | `app/src/main/java/.../ui/settings/SettingsViewModel.kt` | New `resetSync()` and `removeAllBooks()` + DI |
| `app` | `app/src/main/java/.../ui/settings/SettingsScreen.kt` | New "Storage & sync" section |
| `app` | `app/src/main/java/.../ui/library/LibraryViewModel.kt` | New `restart(doc, alsoDeleteFile)` + DI; snackbar event flow |
| `app` | `app/src/main/java/.../ui/library/LibraryScreen.kt` | Long-press → bottom-sheet menu (Restart / Delete) + restart dialog + snackbar |
| `app` | `app/src/main/java/.../ui/AppNavGraph.kt` | Wire new constructor args |
| `app` | `app/src/main/java/.../di/AppContainer.kt` | Expose `booksDir` |

Tests for `LibraryViewModel.restart` go under `app/src/test/...` and use the same Robolectric+Room-in-memory pattern as `SyncOrchestratorTest`. The Settings screen and library bottom-sheet UI changes have no unit tests (Compose UI is verified manually + via existing CI assemble).

## Conventions

- Build/test commands run inside Docker: `scripts/dgradle <args>`.
- Commit style: gitmoji + conventional commits (`:emoji: type: subject`). No Co-Authored-By trailer.
- Branch: `feat/reset-and-clean` (already created and checked out).

---

## Task 1: `SyncStateDao.clearAll()`

**Files:**
- Modify: `data/local/src/main/java/io/theficos/ereader/data/local/db/SyncStateDao.kt`
- Test: `data/local/src/test/java/io/theficos/ereader/data/local/db/SyncStateDaoTest.kt` (create)

- [ ] **Step 1: Write the failing test**

Create `data/local/src/test/java/io/theficos/ereader/data/local/db/SyncStateDaoTest.kt`:

```kotlin
package io.theficos.ereader.data.local.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SyncStateDaoTest {
    private lateinit var db: EReaderDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), EReaderDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test fun `clearAll removes every row`() = runTest {
        val dao = db.syncStateDao()
        dao.set(SyncStateEntity("progress", 1234L))
        dao.set(SyncStateEntity("bookmarks", 5678L))

        dao.clearAll()

        assertThat(dao.lastPulled("progress")).isNull()
        assertThat(dao.lastPulled("bookmarks")).isNull()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `scripts/dgradle :data:local:testDebugUnitTest --tests "*.SyncStateDaoTest"`
Expected: FAIL — `clearAll` is unresolved.

- [ ] **Step 3: Add `clearAll` to the DAO**

Modify `data/local/src/main/java/io/theficos/ereader/data/local/db/SyncStateDao.kt` — add the `clearAll` query:

```kotlin
package io.theficos.ereader.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SyncStateDao {
    @Query("SELECT lastPulledAt FROM sync_state WHERE tableName = :tableName")
    suspend fun lastPulled(tableName: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(state: SyncStateEntity)

    @Query("DELETE FROM sync_state")
    suspend fun clearAll()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `scripts/dgradle :data:local:testDebugUnitTest --tests "*.SyncStateDaoTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add data/local/src/main/java/io/theficos/ereader/data/local/db/SyncStateDao.kt \
        data/local/src/test/java/io/theficos/ereader/data/local/db/SyncStateDaoTest.kt
git commit -m ":sparkles: feat: SyncStateDao.clearAll for sync cursor reset"
```

---

## Task 2: `ProgressRepository.resetForDocument()`

Writes a `percent=0, locator=""` row marked dirty, ready for the next push.

**Files:**
- Modify: `data/local/src/main/java/io/theficos/ereader/data/local/ProgressRepository.kt`
- Test: `data/local/src/test/java/io/theficos/ereader/data/local/ProgressRepositoryTest.kt` (create)

- [ ] **Step 1: Write the failing test**

Create `data/local/src/test/java/io/theficos/ereader/data/local/ProgressRepositoryTest.kt`:

```kotlin
package io.theficos.ereader.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.data.local.db.DocumentEntity
import io.theficos.ereader.data.local.db.EReaderDatabase
import io.theficos.ereader.data.local.db.ProgressEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProgressRepositoryTest {
    private lateinit var db: EReaderDatabase
    private lateinit var repo: ProgressRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), EReaderDatabase::class.java
        ).allowMainThreadQueries().build()
        repo = ProgressRepository(db.progressDao())
    }

    @After fun tearDown() = db.close()

    private suspend fun seedDoc(): Long = db.documentDao().insert(DocumentEntity(
        metadataId = "m1", contentHash = "h1", title = "t", author = null,
        downloadUrl = "u", localPath = "p", coverPath = null, downloadedAt = 0,
    ))

    @Test fun `resetForDocument writes a dirty zero-progress row`() = runTest {
        val docId = seedDoc()
        db.progressDao().upsert(ProgressEntity(
            documentId = docId, locator = "old", percent = 0.42,
            updatedAt = 100L, localUpdatedAt = 100L, syncedAt = 100L,
        ))

        repo.resetForDocument(docId, now = 999L)

        val row = db.progressDao().findByDocument(docId)!!
        assertThat(row.locator).isEmpty()
        assertThat(row.percent).isEqualTo(0.0)
        assertThat(row.updatedAt).isEqualTo(999L)
        assertThat(row.localUpdatedAt).isEqualTo(999L)
        assertThat(row.syncedAt).isEqualTo(0L)
    }

    @Test fun `resetForDocument seeds a row when none exists`() = runTest {
        val docId = seedDoc()

        repo.resetForDocument(docId, now = 42L)

        val row = db.progressDao().findByDocument(docId)!!
        assertThat(row.locator).isEmpty()
        assertThat(row.percent).isEqualTo(0.0)
        assertThat(row.updatedAt).isEqualTo(42L)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `scripts/dgradle :data:local:testDebugUnitTest --tests "*.ProgressRepositoryTest"`
Expected: FAIL — `resetForDocument` unresolved.

- [ ] **Step 3: Add `resetForDocument` to the repository**

Modify `data/local/src/main/java/io/theficos/ereader/data/local/ProgressRepository.kt`. Add the new method after `save`:

```kotlin
suspend fun resetForDocument(documentId: Long, now: Long) {
    dao.upsert(ProgressEntity(
        documentId = documentId,
        locator = "",
        percent = 0.0,
        updatedAt = now,
        localUpdatedAt = now,
        syncedAt = 0L,
    ))
}
```

The existing `ProgressDao.upsert` uses `OnConflictStrategy.REPLACE` keyed on `documentId` (unique index), so this both creates a row when missing and overwrites an existing one — but the resulting row's autogenerated `id` may change. That's harmless: nothing references progress by `id`.

- [ ] **Step 4: Run test to verify it passes**

Run: `scripts/dgradle :data:local:testDebugUnitTest --tests "*.ProgressRepositoryTest"`
Expected: PASS (both cases).

- [ ] **Step 5: Commit**

```bash
git add data/local/src/main/java/io/theficos/ereader/data/local/ProgressRepository.kt \
        data/local/src/test/java/io/theficos/ereader/data/local/ProgressRepositoryTest.kt
git commit -m ":sparkles: feat: ProgressRepository.resetForDocument for restart-book flow"
```

---

## Task 3: `DocumentDao.deleteAll()` + `DocumentRepository.deleteAll()`

The DAO clears the table (FK cascade removes `progress`); the repository helper also wipes the books directory contents.

**Files:**
- Modify: `data/local/src/main/java/io/theficos/ereader/data/local/db/DocumentDao.kt`
- Modify: `data/local/src/main/java/io/theficos/ereader/data/local/DocumentRepository.kt`
- Test: `data/local/src/test/java/io/theficos/ereader/data/local/DocumentRepositoryTest.kt` (create)

- [ ] **Step 1: Write the failing test**

Create `data/local/src/test/java/io/theficos/ereader/data/local/DocumentRepositoryTest.kt`:

```kotlin
package io.theficos.ereader.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.data.local.db.DocumentEntity
import io.theficos.ereader.data.local.db.EReaderDatabase
import io.theficos.ereader.data.local.db.ProgressEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DocumentRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: EReaderDatabase
    private lateinit var repo: DocumentRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), EReaderDatabase::class.java
        ).allowMainThreadQueries().build()
        repo = DocumentRepository(db.documentDao())
    }

    @After fun tearDown() = db.close()

    @Test fun `deleteAll wipes documents, cascades progress, and clears books dir`() = runTest {
        val booksDir = tmp.newFolder("books")
        val epub1 = File(booksDir, "a.epub").apply { writeText("a") }
        val epub2 = File(booksDir, "b.epub").apply { writeText("b") }

        val id1 = db.documentDao().insert(DocumentEntity(
            metadataId = "m1", contentHash = "h1", title = "t1", author = null,
            downloadUrl = "u1", localPath = epub1.path, coverPath = null, downloadedAt = 0,
        ))
        val id2 = db.documentDao().insert(DocumentEntity(
            metadataId = "m2", contentHash = "h2", title = "t2", author = null,
            downloadUrl = "u2", localPath = epub2.path, coverPath = null, downloadedAt = 0,
        ))
        db.progressDao().upsert(ProgressEntity(
            documentId = id1, locator = "x", percent = 0.5,
            updatedAt = 1, localUpdatedAt = 1, syncedAt = 1,
        ))
        db.progressDao().upsert(ProgressEntity(
            documentId = id2, locator = "y", percent = 0.5,
            updatedAt = 1, localUpdatedAt = 1, syncedAt = 1,
        ))

        repo.deleteAll(booksDir)

        assertThat(db.documentDao().findById(id1)).isNull()
        assertThat(db.documentDao().findById(id2)).isNull()
        assertThat(db.progressDao().findByDocument(id1)).isNull()
        assertThat(db.progressDao().findByDocument(id2)).isNull()
        assertThat(booksDir.exists()).isTrue()
        assertThat(booksDir.listFiles()).isEmpty()
    }

    @Test fun `deleteAll tolerates a missing books dir`() = runTest {
        val missing = File(tmp.root, "does-not-exist")
        // No throw; DB delete still applies.
        db.documentDao().insert(DocumentEntity(
            metadataId = "m1", contentHash = "h1", title = "t", author = null,
            downloadUrl = "u", localPath = "p", coverPath = null, downloadedAt = 0,
        ))

        repo.deleteAll(missing)

        assertThat(db.documentDao().findByMetadataId("m1")).isNull()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `scripts/dgradle :data:local:testDebugUnitTest --tests "*.DocumentRepositoryTest"`
Expected: FAIL — `deleteAll` unresolved.

- [ ] **Step 3: Add `deleteAll` to DAO and repository**

Modify `data/local/src/main/java/io/theficos/ereader/data/local/db/DocumentDao.kt` — add at the end of the interface:

```kotlin
@Query("DELETE FROM documents")
suspend fun deleteAll()
```

Modify `data/local/src/main/java/io/theficos/ereader/data/local/DocumentRepository.kt` — add after `delete`:

```kotlin
/**
 * Deletes every document row (cascade-deletes all progress) and best-effort
 * removes everything inside [booksDir]. The directory itself is preserved so
 * future downloads have a destination.
 */
suspend fun deleteAll(booksDir: File) {
    dao.deleteAll()
    runCatching { booksDir.listFiles()?.forEach { it.deleteRecursively() } }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `scripts/dgradle :data:local:testDebugUnitTest --tests "*.DocumentRepositoryTest"`
Expected: PASS (both cases).

- [ ] **Step 5: Commit**

```bash
git add data/local/src/main/java/io/theficos/ereader/data/local/db/DocumentDao.kt \
        data/local/src/main/java/io/theficos/ereader/data/local/DocumentRepository.kt \
        data/local/src/test/java/io/theficos/ereader/data/local/DocumentRepositoryTest.kt
git commit -m ":sparkles: feat: DocumentRepository.deleteAll wipes library and books dir"
```

---

## Task 4: Settings — `resetSync()` and `removeAllBooks()`

ViewModel methods + DI wiring. UI lands in Task 5.

**Files:**
- Modify: `app/src/main/java/io/theficos/ereader/di/AppContainer.kt`
- Modify: `app/src/main/java/io/theficos/ereader/ui/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/io/theficos/ereader/ui/AppNavGraph.kt`

No unit tests in this task: `SettingsViewModel` mostly forwards to repos/DAO. The repo + DAO behavior is already covered by Tasks 1 and 3.

- [ ] **Step 1: Expose `booksDir` from `AppContainer`**

Modify `app/src/main/java/io/theficos/ereader/di/AppContainer.kt` to make the books directory a public field. Replace the `bookDownloader` initialization block with:

```kotlin
val booksDir: File = File(appContext.filesDir, "books")
val bookDownloader: BookDownloader = BookDownloader(
    okHttp = opdsHttp.okHttp,
    booksDir = booksDir,
)
```

- [ ] **Step 2: Add new methods to `SettingsViewModel`**

Modify `app/src/main/java/io/theficos/ereader/ui/settings/SettingsViewModel.kt`:

1. Add `documentRepo: DocumentRepository`, `booksDir: File`, and `syncEnqueuer: (Context) -> Unit = { SyncEnqueuer.enqueue(it, expedited = true, replaceExisting = true) }` constructor params (the lambda lets future tests stub it).
2. Add the two methods at the bottom of the class:

```kotlin
fun resetSync(context: Context) {
    viewModelScope.launch {
        syncStateDao.clearAll()
        _sync.value = _sync.value.copy(lastSyncedAtMs = null)
        syncEnqueuer(context)
    }
}

fun removeAllBooks() {
    viewModelScope.launch {
        documentRepo.deleteAll(booksDir)
    }
}
```

Add the new imports at the top of the file:

```kotlin
import io.theficos.ereader.data.local.DocumentRepository
import java.io.File
```

- [ ] **Step 3: Update the `SettingsViewModel` constructor call site**

Modify `app/src/main/java/io/theficos/ereader/ui/AppNavGraph.kt` lines 32-38 — pass the two new constructor args:

```kotlin
val setVm = remember {
    SettingsViewModel(
        store = container.credentialStore,
        readerStore = container.readerPreferencesStore,
        syncStateDao = container.syncStateDao,
        documentRepo = container.documentRepository,
        booksDir = container.booksDir,
    )
}
```

- [ ] **Step 4: Verify compile**

Run: `scripts/dgradle :app:compileDebugKotlin`
Expected: SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/theficos/ereader/di/AppContainer.kt \
        app/src/main/java/io/theficos/ereader/ui/settings/SettingsViewModel.kt \
        app/src/main/java/io/theficos/ereader/ui/AppNavGraph.kt
git commit -m ":sparkles: feat: SettingsViewModel resetSync and removeAllBooks actions"
```

---

## Task 5: Settings UI — "Storage & sync" section

Two destructive buttons with confirmation dialogs. No unit tests; verified by `:app:assembleDebug` compiling and a quick manual run.

**Files:**
- Modify: `app/src/main/java/io/theficos/ereader/ui/settings/SettingsScreen.kt`

- [ ] **Step 1: Add the new section**

Modify `app/src/main/java/io/theficos/ereader/ui/settings/SettingsScreen.kt`. Insert this `Storage & sync` block **after** the existing `SectionLabel("Sync")` card and **before** `SectionLabel("About")`:

```kotlin
SectionLabel("Storage & sync")
QuireCard(modifier = Modifier.fillMaxWidth()) {
    val context = LocalContext.current
    var pendingResetSync by remember { mutableStateOf(false) }
    var pendingRemoveAll by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column {
            Text("Reset sync", style = MaterialTheme.typography.titleMedium)
            Text(
                "Re-pull everything on the next sync. Your books and progress are kept.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { pendingResetSync = true }) { Text("Reset sync") }
        }
        Column {
            Text("Remove all downloaded books", style = MaterialTheme.typography.titleMedium)
            Text(
                "Delete all EPUB files from this device. Reading progress is preserved on the server.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = { pendingRemoveAll = true },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Remove all downloaded books") }
        }
    }

    if (pendingResetSync) {
        AlertDialog(
            onDismissRequest = { pendingResetSync = false },
            title = { Text("Reset sync?") },
            text = { Text("Next sync will re-pull everything from the server. Local books and progress are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetSync(context)
                    pendingResetSync = false
                }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { pendingResetSync = false }) { Text("Cancel") }
            },
        )
    }
    if (pendingRemoveAll) {
        AlertDialog(
            onDismissRequest = { pendingRemoveAll = false },
            title = { Text("Remove all downloaded books?") },
            text = { Text("Delete all downloaded books from this device? Reading progress is preserved on the server and will sync back if you re-download.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeAllBooks()
                        pendingRemoveAll = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Remove all") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoveAll = false }) { Text("Cancel") }
            },
        )
    }
}
```

Add the missing imports at the top of the file:

```kotlin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
```

- [ ] **Step 2: Verify compile**

Run: `scripts/dgradle :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/io/theficos/ereader/ui/settings/SettingsScreen.kt
git commit -m ":sparkles: feat: Settings 'Storage & sync' section with reset and remove-all"
```

---

## Task 6: `LibraryViewModel.restart(doc, alsoDeleteFile)`

The orchestrated flow described in the spec: write reset → push synchronously → on success optionally delete file; on failure keep the dirty row, queue retry, and emit a snackbar event.

**Files:**
- Modify: `app/src/main/java/io/theficos/ereader/ui/library/LibraryViewModel.kt`
- Test: `app/src/test/java/io/theficos/ereader/ui/library/LibraryViewModelTest.kt` (create)
- Modify: `app/src/main/java/io/theficos/ereader/ui/AppNavGraph.kt`
- Modify: `app/build.gradle.kts` (only if test deps are missing — see Step 3)

- [ ] **Step 1: Confirm app test dependencies**

Run: `grep -n "testImplementation\|robolectric\|truth\|turbine\|coroutines.test" app/build.gradle.kts`

Expected: at minimum `junit`, `truth`, `robolectric`, `kotlinx.coroutines.test`, `mockwebserver` should appear. If any are missing, add them inside the existing `dependencies { ... }` block following the patterns in `data/sync/build.gradle.kts` (`testImplementation(libs.<lib>)`). Do not commit yet — bundle into the Step 7 commit.

- [ ] **Step 2: Write the failing test**

Create `app/src/test/java/io/theficos/ereader/ui/library/LibraryViewModelTest.kt`:

```kotlin
package io.theficos.ereader.ui.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.core.model.Document
import io.theficos.ereader.core.model.DocumentIdentity
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.local.ProgressRepository
import io.theficos.ereader.data.local.db.DocumentEntity
import io.theficos.ereader.data.local.db.EReaderDatabase
import io.theficos.ereader.data.sync.SyncClient
import io.theficos.ereader.data.sync.SyncOrchestrator
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LibraryViewModelTest {
    private lateinit var server: MockWebServer
    private lateinit var db: EReaderDatabase
    private lateinit var docs: DocumentRepository
    private lateinit var progress: ProgressRepository
    private lateinit var orchestrator: SyncOrchestrator
    private lateinit var vm: LibraryViewModel

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), EReaderDatabase::class.java
        ).allowMainThreadQueries().build()
        docs = DocumentRepository(db.documentDao())
        progress = ProgressRepository(db.progressDao())
        orchestrator = SyncOrchestrator(
            client = SyncClient(
                baseUrlProvider = { server.url("/").toString().trimEnd('/') },
                okHttp = OkHttpClient(),
            ),
            progressRepo = progress,
            progressDao = db.progressDao(),
            documentRepo = docs,
            syncState = db.syncStateDao(),
            nowMillis = { 100L },
        )
        vm = LibraryViewModel(
            docs = docs,
            progress = progress,
            syncOrchestrator = orchestrator,
            booksDir = File("/dev/null"),
            nowMillis = { 999L },
        )
    }

    @After fun tearDown() { db.close(); server.shutdown() }

    private suspend fun seedDoc(file: File): Document {
        val id = db.documentDao().insert(DocumentEntity(
            metadataId = "m1", contentHash = "h1", title = "t", author = null,
            downloadUrl = "u", localPath = file.path, coverPath = null, downloadedAt = 0,
        ))
        return Document(
            id = id,
            identity = DocumentIdentity(metadataId = "m1", contentHash = "h1"),
            title = "t", author = null, downloadUrl = "u",
            localPath = file.path, coverPath = null, downloadedAt = 0,
        )
    }

    @Test fun `restart on success without delete writes reset progress and keeps file`() = runTest {
        val tmp = File.createTempFile("book", ".epub").apply { writeText("x") }
        val doc = seedDoc(tmp)
        // push response, then pull response (orchestrator runs full cycle)
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"results":[{"document":{"metadataId":"m1","contentHash":"h1"},"updatedAt":"1970-01-01T00:00:00.100Z"}]}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[],"serverTime":"1970-01-01T00:00:00.100Z"}"""))

        val result = vm.restart(doc, alsoDeleteFile = false)

        assertThat(result).isTrue()
        val row = db.progressDao().findByDocument(doc.id)!!
        assertThat(row.locator).isEmpty()
        assertThat(row.percent).isEqualTo(0.0)
        assertThat(row.syncedAt).isEqualTo(100L) // nowMillis from orchestrator
        assertThat(tmp.exists()).isTrue()
        tmp.delete()
    }

    @Test fun `restart on success with delete also removes the file and document`() = runTest {
        val tmp = File.createTempFile("book", ".epub").apply { writeText("x") }
        val doc = seedDoc(tmp)
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"results":[{"document":{"metadataId":"m1","contentHash":"h1"},"updatedAt":"1970-01-01T00:00:00.100Z"}]}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[],"serverTime":"1970-01-01T00:00:00.100Z"}"""))

        val result = vm.restart(doc, alsoDeleteFile = true)

        assertThat(result).isTrue()
        assertThat(db.documentDao().findById(doc.id)).isNull()
        assertThat(tmp.exists()).isFalse()
    }

    @Test fun `restart on push failure keeps dirty row, file, and emits snackbar`() = runTest {
        val tmp = File.createTempFile("book", ".epub").apply { writeText("x") }
        val doc = seedDoc(tmp)
        server.enqueue(MockResponse().setResponseCode(500))

        vm.events.test {
            val result = vm.restart(doc, alsoDeleteFile = true)

            assertThat(result).isFalse()
            assertThat(awaitItem()).isInstanceOf(LibraryEvent.RestartFailed::class.java)
        }

        val row = db.progressDao().findByDocument(doc.id)!!
        assertThat(row.syncedAt).isEqualTo(0L) // still dirty
        assertThat(row.locator).isEmpty()
        assertThat(tmp.exists()).isTrue()
        assertThat(db.documentDao().findById(doc.id)).isNotNull()
        tmp.delete()
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `scripts/dgradle :app:testDebugUnitTest --tests "*.LibraryViewModelTest"`
Expected: FAIL — `restart`, `events`, and the new constructor params are unresolved.

- [ ] **Step 4: Implement the new ViewModel surface**

Modify `app/src/main/java/io/theficos/ereader/ui/library/LibraryViewModel.kt`:

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
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val syncEnqueuer: (Context) -> Unit = { SyncEnqueuer.enqueue(it, expedited = true, replaceExisting = true) },
) : ViewModel() {

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
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val items: StateFlow<List<LibraryRow>> = rows

    val continueReading: StateFlow<LibraryRow?> = rows
        .map { list ->
            list
                .filter { it.percent in 0.0001..0.9999 }
                .maxByOrNull { it.progressUpdatedAt }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _events = MutableSharedFlow<LibraryEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<LibraryEvent> = _events.asSharedFlow()

    fun delete(document: Document) {
        viewModelScope.launch { docs.delete(document) }
    }

    /**
     * Returns true on success, false on failure (a [LibraryEvent.RestartFailed]
     * is also emitted). Suspends until the push completes; intended to be
     * launched from a coroutine.
     */
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

    /**
     * Fire-and-forget wrapper for the UI. Schedules a WorkManager retry on
     * failure so the dirty row eventually drains.
     */
    fun restartFromUi(document: Document, alsoDeleteFile: Boolean, context: Context) {
        viewModelScope.launch {
            if (!restart(document, alsoDeleteFile)) {
                syncEnqueuer(context)
            }
        }
    }
}

data class LibraryRow(
    val document: Document,
    val percent: Double,
    val progressUpdatedAt: Long,
)
```

- [ ] **Step 5: Update the `LibraryViewModel` call site**

Modify `app/src/main/java/io/theficos/ereader/ui/AppNavGraph.kt` line 28 — pass the new args:

```kotlin
val libVm = remember {
    LibraryViewModel(
        docs = container.documentRepository,
        progress = container.progressRepository,
        syncOrchestrator = container.syncOrchestrator,
        booksDir = container.booksDir,
    )
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `scripts/dgradle :app:testDebugUnitTest --tests "*.LibraryViewModelTest"`
Expected: PASS (all three cases).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/theficos/ereader/ui/library/LibraryViewModel.kt \
        app/src/main/java/io/theficos/ereader/ui/AppNavGraph.kt \
        app/src/test/java/io/theficos/ereader/ui/library/LibraryViewModelTest.kt \
        app/build.gradle.kts
git commit -m ":sparkles: feat: LibraryViewModel.restart with sync-first ordering"
```

If `app/build.gradle.kts` was unchanged, drop it from `git add`.

---

## Task 7: Library UI — bottom sheet menu, restart dialog, snackbar

**Files:**
- Modify: `app/src/main/java/io/theficos/ereader/ui/library/LibraryScreen.kt`

- [ ] **Step 1: Replace the long-press dialog with a bottom sheet menu**

Modify `app/src/main/java/io/theficos/ereader/ui/library/LibraryScreen.kt`. The full file should look like this:

```kotlin
package io.theficos.ereader.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.theficos.ereader.core.model.Document
import io.theficos.ereader.data.sync.SyncEnqueuer
import io.theficos.ereader.ui.components.CoverImage
import io.theficos.ereader.ui.components.SectionLabel
import io.theficos.ereader.ui.theme.Lora

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenBook: (documentId: Long) -> Unit,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { SyncEnqueuer.enqueue(context, expedited = true) }

    val items by viewModel.items.collectAsState()
    val cont by viewModel.continueReading.collectAsState()
    var menuFor by remember { mutableStateOf<Document?>(null) }
    var pendingDelete by remember { mutableStateOf<Document?>(null) }
    var pendingRestart by remember { mutableStateOf<Document?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                LibraryEvent.RestartFailed ->
                    snackbarHostState.showSnackbar("Couldn't sync restart — will retry.")
            }
        }
    }

    if (items.isEmpty()) {
        EmptyState(modifier = Modifier.padding(contentPadding))
        return
    }

    Box(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "Quire",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            cont?.let { row ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ContinueReadingCard(row = row, onClick = { onOpenBook(row.document.id) })
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionLabel("Library · ${items.size}")
            }
            itemsIndexed(items, key = { _, r -> r.document.id }) { _, row ->
                Column(
                    modifier = Modifier.combinedClickable(
                        onClick = { onOpenBook(row.document.id) },
                        onLongClick = { menuFor = row.document },
                    ),
                ) {
                    CoverImage(
                        source = row.document.coverPath,
                        title = row.document.title,
                        author = row.document.author,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(2f / 3f),
                    )
                    Text(
                        text = row.document.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }

    menuFor?.let { doc ->
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { menuFor = null },
            sheetState = sheetState,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(
                    text = doc.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
                TextButton(
                    onClick = {
                        pendingRestart = doc
                        menuFor = null
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Text("Restart book", modifier = Modifier.fillMaxWidth())
                }
                TextButton(
                    onClick = {
                        pendingDelete = doc
                        menuFor = null
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Text(
                        "Delete from library",
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    pendingRestart?.let { doc ->
        var alsoDelete by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { pendingRestart = null },
            title = { Text("Restart book?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("\"${doc.title}\" will be marked as unread and synced to your other devices.")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = alsoDelete, onCheckedChange = { alsoDelete = it })
                        Text("Also delete the downloaded copy", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.restartFromUi(doc, alsoDelete, context)
                    pendingRestart = null
                }) { Text("Restart") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestart = null }) { Text("Cancel") }
            },
        )
    }

    pendingDelete?.let { doc ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete book?") },
            text = { Text("\"${doc.title}\" will be removed from your library and the downloaded file deleted. Reading progress will be lost.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(doc)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "q",
                fontFamily = Lora,
                style = MaterialTheme.typography.displaySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier.padding(bottom = 16.dp),
            )
            Text(
                text = "Your shelf is empty.",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Open the Catalog tab to find books.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `scripts/dgradle :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/io/theficos/ereader/ui/library/LibraryScreen.kt
git commit -m ":sparkles: feat: library bottom-sheet menu with restart-book action"
```

---

## Task 8: Final verification

- [ ] **Step 1: Run the full unit-test suite**

Run: `scripts/dgradle test`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 2: Build the debug APK**

Run: `scripts/dgradle :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual smoke (cannot be automated — UI)**

Tell the user the feature is ready and list manual checks:
- Settings → Reset sync: dialog appears, "Last synced" clears, sync re-pulls.
- Settings → Remove all downloaded books: dialog appears (red), library empties, no crash.
- Library → long-press a book: bottom sheet shows Restart / Delete.
- Restart with checkbox off: book stays on device, position resets, other devices update on next pull.
- Restart with checkbox on: book disappears from library, server position is 0.
- Restart with airplane mode + checkbox on: snackbar fires, book stays, dirty row queued.

---

## Self-review

**Spec coverage**: every section in the spec maps to a task —
- "Reset sync" UI + behavior → Tasks 1, 4, 5
- "Restart book" UI + ordering + failure modes → Tasks 2, 6, 7
- "Remove all downloaded books" → Tasks 3, 4, 5
- Error handling table → covered by Task 6 tests + Task 5/7 dialogs

**Placeholder scan**: no `TODO`/`TBD`. Every code step shows full code.

**Type consistency**: `clearAll` (DAO + DocumentDao), `deleteAll(booksDir)` (repo + DAO no-arg), `resetForDocument(documentId, now)`, `restart(document, alsoDeleteFile)`, `restartFromUi(document, alsoDeleteFile, context)`, `LibraryEvent.RestartFailed` — all consistent across tasks.
