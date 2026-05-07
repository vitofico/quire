package io.theficos.ereader.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.local.ProgressRepository
import io.theficos.ereader.data.local.db.DocumentEntity
import io.theficos.ereader.data.local.db.EReaderDatabase
import io.theficos.ereader.data.local.db.ProgressEntity
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SyncOrchestratorTest {
    private lateinit var server: MockWebServer
    private lateinit var db: EReaderDatabase
    private lateinit var orchestrator: SyncOrchestrator
    private lateinit var docs: DocumentRepository
    private lateinit var progress: ProgressRepository

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
    }

    @After fun tearDown() { db.close(); server.shutdown() }

    private suspend fun seedDoc(metadataId: String?, hash: String): Long =
        db.documentDao().insert(DocumentEntity(
            metadataId = metadataId, contentHash = hash, title = "t", author = null,
            downloadUrl = "u", localPath = "p", coverPath = null, downloadedAt = 0,
        ))

    private suspend fun seedProgress(documentId: Long, locator: String, percent: Double, updatedAt: Long) {
        db.progressDao().upsert(
            ProgressEntity(
                documentId = documentId,
                locator = locator,
                percent = percent,
                updatedAt = updatedAt,
                localUpdatedAt = updatedAt,
                syncedAt = 0L,
            )
        )
    }

    @Test fun `push then pull happy path`() = runTest {
        val docId = seedDoc(metadataId = "m", hash = "h")
        seedProgress(documentId = docId, locator = "loc1", percent = 0.5, updatedAt = 50L)

        server.enqueue(MockResponse().setBody(
            """{"results":[{"document":{"metadata_id":"m","content_hash":"h"},"status":"accepted","server_client_updated_at":"1970-01-01T00:00:00.050Z"}]}"""
        ))
        server.enqueue(MockResponse().setBody(
            """{"items":[],"server_time":"1970-01-01T00:00:00.200Z"}"""
        ))

        val result = orchestrator.runOnce()
        assertThat(result).isInstanceOf(SyncResult.Success::class.java)

        assertThat(progress.dirty()).isEmpty()
        assertThat(db.syncStateDao().lastPulled("progress")).isEqualTo(200L)
    }

    @Test fun `pull writes server progress when local is older`() = runTest {
        val docId = seedDoc(metadataId = "m", hash = "h")

        server.enqueue(MockResponse().setBody(
            """{"items":[{"document":{"metadata_id":"m","content_hash":"h"},"locator":"server-loc","percent":0.7,"client_updated_at":"1970-01-01T00:00:00.500Z"}],"server_time":"1970-01-01T00:00:00.600Z"}"""
        ))

        val result = orchestrator.runOnce()
        assertThat(result).isInstanceOf(SyncResult.Success::class.java)

        val saved = progress.get(docId)
        assertThat(saved?.locator).isEqualTo("server-loc")
        assertThat(saved?.updatedAt).isEqualTo(500L)
    }

    @Test fun `unauthorized stops the pipeline`() = runTest {
        val docId = seedDoc(metadataId = "m", hash = "h")
        seedProgress(documentId = docId, locator = "loc1", percent = 0.5, updatedAt = 50L)
        server.enqueue(MockResponse().setResponseCode(401))
        val result = orchestrator.runOnce()
        assertThat(result).isInstanceOf(SyncResult.Unauthorized::class.java)
    }
}
