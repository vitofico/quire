package io.theficos.ereader.ui.library

import androidx.lifecycle.viewModelScope
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
import io.theficos.ereader.auth.CalibreCredentialStore
import io.theficos.ereader.data.sync.SyncClient
import io.theficos.ereader.data.sync.SyncOrchestrator
import io.theficos.ereader.domain.restore.RestoreSummary
import io.theficos.ereader.ui.catalog.FakeAndroidKeyStore
import io.theficos.ereader.core.model.Progress as DomainProgress
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class LibraryViewModelTest {
    private lateinit var server: MockWebServer
    private lateinit var db: EReaderDatabase
    private lateinit var docs: DocumentRepository
    private lateinit var progress: ProgressRepository
    private lateinit var orchestrator: SyncOrchestrator
    private lateinit var vm: LibraryViewModel

    @Before fun setUp() {
        FakeAndroidKeyStore.setup()
        // Queued, not unconfined. On an unconfined Main, a view-model collector resumes
        // on whichever Room thread delivered the query result and keeps running there,
        // in parallel with the test body, where advanceUntilIdle() cannot wait for it.
        // Queued on the test scheduler, every collector runs on the test thread and
        // advanceUntilIdle() drains them all.
        Dispatchers.setMain(StandardTestDispatcher())
        server = MockWebServer().also { it.start() }
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), EReaderDatabase::class.java
        ).allowMainThreadQueries().build()
        // Open the database here, on the test thread. Left to the first query, Room
        // opens it on a worker thread, and a test that finishes before that open does
        // leaves tearDown's close() racing it. Room's open and close take the same two
        // locks in opposite order, so a close() that lands mid-open hangs both
        // threads for good, and the whole test task with them.
        db.openHelper.writableDatabase
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
        vm = track(LibraryViewModel(
            docs = docs,
            progress = progress,
            syncOrchestrator = orchestrator,
            booksDir = File("/dev/null"),
            libraryPreferencesStore = LibraryPreferencesStore(ApplicationProvider.getApplicationContext()),
            nowMillis = { 999L },
        ))
    }

    @After fun tearDown() {
        // Every view model this test built keeps `WhileSubscribed` collectors alive
        // on viewModelScope, which runs on the Main dispatcher we are about to reset.
        // Left running they outlive the test and trip the next one's setMain() with
        // "Dispatchers.Main is used concurrently with setting it" (see 4bfc1bd).
        runCatching { viewModels.forEach { it.viewModelScope.cancel() } }
        // Reset the Main dispatcher unconditionally — if db.close()/server.shutdown()
        // throw, an un-reset Main pollutes the next test's setMain() with an
        // IllegalStateException and cascades lateinit failures across the suite.
        runCatching { db.close() }
        runCatching { server.shutdown() }
        Dispatchers.resetMain()
    }

    /** Every view model built by this test, so tearDown can cancel their scopes. */
    private val viewModels = mutableListOf<LibraryViewModel>()

    private fun track(viewModel: LibraryViewModel): LibraryViewModel =
        viewModel.also { viewModels += it }

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
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"results":[{"document":{"metadata_id":"m1","content_hash":"h1"},"status":"accepted","server_client_updated_at":"1970-01-01T00:00:00.100Z"}]}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[],"server_time":"1970-01-01T00:00:00.100Z"}"""))

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
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"results":[{"document":{"metadata_id":"m1","content_hash":"h1"},"status":"accepted","server_client_updated_at":"1970-01-01T00:00:00.100Z"}]}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[],"server_time":"1970-01-01T00:00:00.100Z"}"""))

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

    // Room answers these queries on its own executor threads, so the tests below
    // await the state the view model settles on instead of each emission under
    // Turbine's 3 s per-item limit. Whichever test runs first in the class pays for
    // cold class loading and the first Room work, so that limit measured the
    // machine, not the code. runTest's own timeout still fails a real hang.

    /**
     * Holds one subscription to [flow] open until the test ends, so its
     * `WhileSubscribed` upstream stays live between reads. A test asserting that
     * the view model reacts to a change needs this: without it, a later `first {}`
     * could restart the upstream and pass on a fresh query instead of the update.
     */
    private fun TestScope.keepSubscribed(flow: Flow<*>) {
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { flow.collect {} }
    }

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
        val final = vm.items.first { it.size >= 3 }
        assertThat(final.map { it.document.title }).containsExactly("Bravo", "Charlie", "Alpha").inOrder()
    }

    @Test fun `TITLE sort orders alphabetically`() = runTest {
        seed("h1", "Charlie", null)
        seed("h2", "Alpha", null)
        seed("h3", "Bravo", null)
        vm.setSort(LibrarySort.TITLE)
        val titles = vm.items.first { it.size >= 3 }.map { it.document.title }
        assertThat(titles).containsExactly("Alpha", "Bravo", "Charlie").inOrder()
    }

    @Test fun `query filters by title case-insensitively`() = runTest {
        seed("h1", "Alpha", "Auth")
        seed("h2", "BRAVO", "Auth")
        seed("h3", "Charlie", "Auth")
        vm.setSort(LibrarySort.TITLE)
        vm.setQuery("bra")
        val final = vm.items.first { it.size == 1 && it.first().document.title == "BRAVO" }
        assertThat(final.map { it.document.title }).containsExactly("BRAVO")
    }

    @Test fun `query filters by author`() = runTest {
        seed("h1", "Alpha", "King")
        seed("h2", "Bravo", "Tolkien")
        vm.setSort(LibrarySort.TITLE)
        vm.setQuery("tolk")
        val final = vm.items.first { it.size == 1 && it.first().document.title == "Bravo" }
        assertThat(final.map { it.document.title }).containsExactly("Bravo")
    }

    @Test fun `clearing query restores full list`() = runTest {
        seed("h1", "Alpha", null)
        seed("h2", "Bravo", null)
        vm.setSort(LibrarySort.TITLE)
        vm.setQuery("alpha")
        keepSubscribed(vm.items)
        vm.items.first { it.size == 1 && it.first().document.title == "Alpha" }
        vm.setQuery("")
        assertThat(vm.items.first { it.size >= 2 }).hasSize(2)
    }

    @Test fun `finished books are excluded from continueReading`() = runTest {
        seed("h1", "InProgress", null, percent = 0.5, updatedAt = 100L)
        seed("h2", "Finished", null, percent = 0.99, updatedAt = 200L, finishedAt = 200L)
        // Null until Room delivers the rows, which carry both books at once, so the
        // first book it surfaces is the one it settles on.
        val emission = vm.continueReading.first { it != null }
        assertThat(emission?.document?.title).isEqualTo("InProgress")
    }

    private suspend fun seedSeries(
        contentHash: String,
        title: String,
        seriesName: String?,
        seriesIndex: Double?,
        percent: Double = 0.0,
        finishedAt: Long? = null,
        updatedAt: Long = 0L,
    ): Long {
        val docId = db.documentDao().insert(DocumentEntity(
            metadataId = contentHash, contentHash = contentHash, title = title, author = null,
            downloadUrl = "u", localPath = "p", coverPath = null, downloadedAt = 0,
            seriesName = seriesName, seriesIndex = seriesIndex,
        ))
        if (percent > 0.0 || finishedAt != null) {
            progress.save(DomainProgress(
                documentId = docId, locator = "loc", percent = percent,
                updatedAt = updatedAt, finishedAt = finishedAt,
            ))
        }
        return docId
    }

    @Test fun `seriesContinuationCandidates emits the unread sibling-in-series`() = runTest {
        seedSeries("h1", "Foundation 1", "Foundation", 1.0, percent = 1.0, finishedAt = 100L, updatedAt = 100L)
        val candidateId = seedSeries("h2", "Foundation 2", "Foundation", 2.0)
        val emission = vm.seriesContinuationCandidates.first { it.size == 1 }
        assertThat(emission.map { it.id }).containsExactly(candidateId)
    }

    private fun vmWith(store: CalibreCredentialStore): LibraryViewModel = track(LibraryViewModel(
        docs = docs,
        progress = progress,
        syncOrchestrator = orchestrator,
        booksDir = File("/dev/null"),
        libraryPreferencesStore = LibraryPreferencesStore(ApplicationProvider.getApplicationContext()),
        nowMillis = { 999L },
        credentialStore = store,
        restoreInProgress = { _ -> RestoreSummary(0, 0, 0, 0, 0) },
    ))

    @Test fun `canRestore is true when connected and library empty`() = runTest {
        val store = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        store.saveBasicAccount("http://host", "u", "p")
        val restoreVm = vmWith(store)
        // Collecting keeps the WhileSubscribed `rows` flow hot so canRestore reflects the DB.
        restoreVm.canRestore.test {
            var v = awaitItem()
            while (!v) v = awaitItem()
            assertThat(v).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `canRestore is false for an opds-only account even if library empty`() = runTest {
        val store = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        store.saveOpdsAccount("https://feed.example/opds")
        val restoreVm = vmWith(store)
        restoreVm.canRestore.test {
            // An OPDS catalog has no quire-server behind it, so the restore
            // prompt it would gate could only ever fail. See issue #101.
            assertThat(awaitItem()).isFalse()
            advanceUntilIdle()
            assertThat(restoreVm.canRestore.value).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `canRestore is false when disconnected even if library empty`() = runTest {
        val store = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        store.clear()
        val restoreVm = vmWith(store)
        restoreVm.canRestore.test {
            // Disconnected: canRestore can never become true regardless of rows.
            assertThat(awaitItem()).isFalse()
            advanceUntilIdle()
            assertThat(restoreVm.canRestore.value).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `canRestore is false when connected but library non-empty`() = runTest {
        val store = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        store.saveBasicAccount("http://host", "u", "p")
        seed("h1", "Alpha", null)
        val restoreVm = vmWith(store)
        // Subscribe to canRestore (keeps the WhileSubscribed `rows` upstream hot).
        // Emission sequence with a connected account:
        //   1. stateIn initial → false
        //   2. rows still holds its empty start value before Room delivers → may emit true
        //   3. rows carries the seeded book → combine settles to false
        // `items` reaching the book proves Room has delivered it. canRestore reads the
        // same rows through its own collector, which may not have run yet, so drain
        // the scheduler before reading its latest value.
        restoreVm.canRestore.test {
            assertThat(restoreVm.items.first { it.isNotEmpty() }).hasSize(1)
            advanceUntilIdle()
            // expectMostRecentItem() returns the latest buffered item, discarding any
            // earlier transient true — after rows has settled to non-empty, this must be false.
            assertThat(expectMostRecentItem()).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `seriesContinuationCandidates re-emits when a candidate is marked finished`() = runTest {
        val sibling = seedSeries("h1", "Foundation 1", "Foundation", 1.0, percent = 1.0, finishedAt = 100L, updatedAt = 100L)
        val candidate = seedSeries("h2", "Foundation 2", "Foundation", 2.0)
        // One subscription for the whole test, so the empty shelf below can only
        // come from Room re-emitting after the save, not from a fresh query.
        keepSubscribed(vm.seriesContinuationCandidates)
        val emission = vm.seriesContinuationCandidates.first { it.size == 1 }
        assertThat(emission.map { it.id }).containsExactly(candidate)
        // Mark the candidate finished; it should drop off the shelf.
        progress.save(DomainProgress(
            documentId = candidate, locator = "loc", percent = 1.0,
            updatedAt = 999L, finishedAt = 999L,
        ))
        val next = vm.seriesContinuationCandidates.first { it.isEmpty() }
        assertThat(next).isEmpty()
        // Suppress unused-variable lint on `sibling` (kept for clarity).
        assertThat(sibling).isGreaterThan(0L)
    }
}
