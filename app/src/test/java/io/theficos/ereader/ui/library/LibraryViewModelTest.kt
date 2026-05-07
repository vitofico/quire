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
@Config(sdk = [33], application = android.app.Application::class)
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
}
