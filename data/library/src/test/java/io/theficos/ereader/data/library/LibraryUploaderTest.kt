package io.theficos.ereader.data.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.data.local.db.DocumentDao
import io.theficos.ereader.data.local.db.DocumentEntity
import io.theficos.ereader.data.local.db.EReaderDatabase
import io.theficos.ereader.testing.awaitRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
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
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LibraryUploaderTest {
    private lateinit var server: MockWebServer
    private lateinit var client: LibraryClient
    private lateinit var db: EReaderDatabase
    private lateinit var dao: DocumentDao
    private lateinit var scope: CoroutineScope

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        client = LibraryClient(
            baseUrlProvider = { server.url("").toString().trimEnd('/') },
            http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build(),
        )
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), EReaderDatabase::class.java,
        ).allowMainThreadQueries().build()
        db.openHelper.writableDatabase
        dao = db.documentDao()
        // `enqueueOne` launches into this scope; in tests we don't actually
        // need it to do anything because we drive `runOnce` directly.
        scope = CoroutineScope(StandardTestDispatcher())
    }

    @After fun tearDown() {
        try {
            if (::db.isInitialized) db.close()
        } finally {
            if (::server.isInitialized) server.shutdown()
        }
    }

    private fun successBody(contentHash: String): String = """
        {
          "content_hash":"$contentHash",
          "title":"t",
          "authors":[],
          "metadata_id":null,
          "series_name":null,
          "series_index":null,
          "isbn":null,
          "language":null,
          "subjects":[],
          "opds_href":null,
          "created_at":"2026-05-17T00:00:00+00:00",
          "updated_at":"2026-05-17T00:00:00+00:00",
          "deleted_at":null
        }
    """.trimIndent()

    @Test
    fun `runOnce sends nothing and keeps rows unsynced when no server is configured`() = runTest {
        dao.insert(docEntity(id = 1, contentHash = "h1"))
        val offline = LibraryClient(
            baseUrlProvider = { null },
            http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build(),
        )

        val result = LibraryUploader(client = offline, dao = dao, scope = scope).runOnce()

        assertThat(result).isEqualTo(UploadResult(attempted = 0, succeeded = 0, abortedOnAuth = false))
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(dao.findUnsyncedToLibrary().map { it.id }).containsExactly(1L)
    }

    @Test
    fun `runOnce puts every unsynced doc and marks each synced`() = runTest {
        dao.insert(docEntity(id = 1, contentHash = "h1"))
        dao.insert(docEntity(id = 2, contentHash = "h2"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(successBody("h1")))
        server.enqueue(MockResponse().setResponseCode(200).setBody(successBody("h2")))

        val uploader = LibraryUploader(client = client, dao = dao, scope = scope, nowMillis = { 1000L })
        val result = uploader.runOnce()

        assertThat(result.attempted).isEqualTo(2)
        assertThat(result.succeeded).isEqualTo(2)
        assertThat(result.abortedOnAuth).isFalse()
        assertThat(dao.findById(1)?.librarySyncedAt).isEqualTo(1000L)
        assertThat(dao.findById(2)?.librarySyncedAt).isEqualTo(1000L)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `runOnce continues past a transient failure on the first doc`() = runTest {
        // DAO orders newest-first (downloadedAt DESC); id=2 was downloaded
        // later, so it's PUT first and gets the 500 response. id=1 follows
        // and succeeds.
        dao.insert(docEntity(id = 1, contentHash = "h1", downloadedAt = 100L))
        dao.insert(docEntity(id = 2, contentHash = "h2", downloadedAt = 200L))
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(successBody("h1")))

        val uploader = LibraryUploader(client = client, dao = dao, scope = scope, nowMillis = { 2000L })
        val result = uploader.runOnce()

        assertThat(result.attempted).isEqualTo(2)
        assertThat(result.succeeded).isEqualTo(1)
        assertThat(result.abortedOnAuth).isFalse()
        // Only the second-attempted doc (id=1) was marked.
        assertThat(dao.findById(1)?.librarySyncedAt).isEqualTo(2000L)
        assertThat(dao.findById(2)?.librarySyncedAt).isNull()
    }

    @Test
    fun `runOnce aborts the whole batch on 401 and marks nothing`() = runTest {
        dao.insert(docEntity(id = 1, contentHash = "h1"))
        dao.insert(docEntity(id = 2, contentHash = "h2"))
        server.enqueue(MockResponse().setResponseCode(401).setBody("nope"))
        // Second response intentionally never enqueued — the uploader must not
        // attempt the second row after a 401.

        val uploader = LibraryUploader(client = client, dao = dao, scope = scope, nowMillis = { 3000L })
        val result = uploader.runOnce()

        assertThat(result.abortedOnAuth).isTrue()
        assertThat(result.succeeded).isEqualTo(0)
        assertThat(dao.findById(1)?.librarySyncedAt).isNull()
        assertThat(dao.findById(2)?.librarySyncedAt).isNull()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `runOnce skips rows already marked synced`() = runTest {
        dao.insert(docEntity(id = 1, contentHash = "h1", librarySyncedAt = 999L))
        dao.insert(docEntity(id = 2, contentHash = "h2", librarySyncedAt = null))
        server.enqueue(MockResponse().setResponseCode(200).setBody(successBody("h2")))
        // A regression must fail the count assertion without waiting for an empty response queue.
        server.enqueue(MockResponse().setResponseCode(200).setBody(successBody("h1")))

        val uploader = LibraryUploader(client = client, dao = dao, scope = scope, nowMillis = { 4000L })
        val result = uploader.runOnce()

        assertThat(result.attempted).isEqualTo(1)
        assertThat(result.succeeded).isEqualTo(1)
        // Only the unsynced row was PUT and marked.
        assertThat(dao.findById(1)?.librarySyncedAt).isEqualTo(999L)
        assertThat(dao.findById(2)?.librarySyncedAt).isEqualTo(4000L)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `runOnce with no unsynced rows is a quick no-op`() = runTest {
        // Empty DAO → no calls, no failures.
        val uploader = LibraryUploader(client = client, dao = dao, scope = scope)
        val result = uploader.runOnce()

        assertThat(result.attempted).isEqualTo(0)
        assertThat(result.succeeded).isEqualTo(0)
        assertThat(result.abortedOnAuth).isFalse()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `runOnce skips a 409 (metadata_id_conflict) like any other non-401 and continues`() = runTest {
        // Per the DAO order (downloadedAt DESC), id=2 is PUT first → 409, id=1
        // is PUT second → 200.
        dao.insert(docEntity(id = 1, contentHash = "h1", downloadedAt = 100L))
        dao.insert(docEntity(id = 2, contentHash = "h2", downloadedAt = 200L))
        server.enqueue(
            MockResponse().setResponseCode(409).setBody(
                """{"detail":{"error":"metadata_id_conflict"}}"""
            )
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(successBody("h1")))

        val uploader = LibraryUploader(client = client, dao = dao, scope = scope, nowMillis = { 5000L })
        val result = uploader.runOnce()

        assertThat(result.abortedOnAuth).isFalse()
        assertThat(result.succeeded).isEqualTo(1)
        // The 409'd row (id=2) is NOT marked synced — it stays in the next pass's queue.
        assertThat(dao.findById(1)?.librarySyncedAt).isEqualTo(5000L)
        assertThat(dao.findById(2)?.librarySyncedAt).isNull()
        assertThat(server.awaitRequest().body.readUtf8()).contains("h2")
        assertThat(server.awaitRequest().body.readUtf8()).contains("h1")
    }

    @Test
    fun `authors are split from a comma-and-ampersand-joined author string`() {
        assertThat(parseAuthors("Frank Herbert")).containsExactly("Frank Herbert")
        assertThat(parseAuthors("A & B")).containsExactly("A", "B").inOrder()
        assertThat(parseAuthors("A, B & C")).containsExactly("A", "B", "C").inOrder()
        assertThat(parseAuthors(null)).isEmpty()
        assertThat(parseAuthors("")).isEmpty()
        assertThat(parseAuthors(" , & ")).isEmpty()
    }
}

private fun docEntity(
    id: Long,
    contentHash: String,
    librarySyncedAt: Long? = null,
    downloadedAt: Long = id,
): DocumentEntity = DocumentEntity(
    id = id,
    metadataId = null,
    contentHash = contentHash,
    title = "t",
    author = null,
    downloadUrl = "u",
    localPath = "p",
    coverPath = null,
    downloadedAt = downloadedAt,
    seriesName = null,
    seriesIndex = null,
    librarySyncedAt = librarySyncedAt,
)
