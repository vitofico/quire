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
import io.theficos.ereader.core.model.Progress as DomainProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
        Dispatchers.setMain(UnconfinedTestDispatcher())
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
            libraryPreferencesStore = LibraryPreferencesStore(ApplicationProvider.getApplicationContext()),
            nowMillis = { 999L },
        )
    }

    @After fun tearDown() {
        // Reset the Main dispatcher unconditionally — if db.close()/server.shutdown()
        // throw, an un-reset Main pollutes the next test's setMain() with an
        // IllegalStateException and cascades lateinit failures across the suite.
        runCatching { db.close() }
        runCatching { server.shutdown() }
        Dispatchers.resetMain()
    }

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
            var final = awaitItem()
            while (final.size < 3) final = awaitItem()
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
            var final = awaitItem()
            while (final.size < 3) final = awaitItem()
            val titles = final.map { it.document.title }
            assertThat(titles).containsExactly("Alpha", "Bravo", "Charlie").inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `query filters by title case-insensitively`() = runTest {
        seed("h1", "Alpha", "Auth")
        seed("h2", "BRAVO", "Auth")
        seed("h3", "Charlie", "Auth")
        vm.setSort(LibrarySort.TITLE)
        vm.setQuery("bra")
        vm.items.test {
            var final = awaitItem()
            while (final.size != 1 || final.firstOrNull()?.document?.title != "BRAVO") {
                final = awaitItem()
            }
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
            var final = awaitItem()
            while (final.size != 1 || final.firstOrNull()?.document?.title != "Bravo") {
                final = awaitItem()
            }
            assertThat(final.map { it.document.title }).containsExactly("Bravo")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `clearing query restores full list`() = runTest {
        seed("h1", "Alpha", null)
        seed("h2", "Bravo", null)
        vm.setSort(LibrarySort.TITLE)
        vm.setQuery("alpha")
        vm.items.test {
            var filtered = awaitItem()
            while (filtered.size != 1 || filtered.firstOrNull()?.document?.title != "Alpha") {
                filtered = awaitItem()
            }
            vm.setQuery("")
            var final = awaitItem()
            while (final.size < 2) final = awaitItem()
            assertThat(final).hasSize(2)
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
        vm.seriesContinuationCandidates.test {
            var emission = awaitItem()
            while (emission.size != 1) emission = awaitItem()
            assertThat(emission.map { it.id }).containsExactly(candidateId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `seriesContinuationCandidates re-emits when a candidate is marked finished`() = runTest {
        val sibling = seedSeries("h1", "Foundation 1", "Foundation", 1.0, percent = 1.0, finishedAt = 100L, updatedAt = 100L)
        val candidate = seedSeries("h2", "Foundation 2", "Foundation", 2.0)
        vm.seriesContinuationCandidates.test {
            var emission = awaitItem()
            while (emission.size != 1) emission = awaitItem()
            assertThat(emission.map { it.id }).containsExactly(candidate)
            // Mark the candidate finished; it should drop off the shelf.
            progress.save(DomainProgress(
                documentId = candidate, locator = "loc", percent = 1.0,
                updatedAt = 999L, finishedAt = 999L,
            ))
            var next = awaitItem()
            while (next.isNotEmpty()) next = awaitItem()
            assertThat(next).isEmpty()
            // Suppress unused-variable lint on `sibling` (kept for clarity).
            assertThat(sibling).isGreaterThan(0L)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
