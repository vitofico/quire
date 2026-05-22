package io.theficos.ereader.data.library.sync

import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.data.library.LibraryClient
import io.theficos.ereader.data.local.db.DocumentDao
import io.theficos.ereader.data.local.db.DocumentEntity
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [LibraryMirrorPushWorker] — Phase 0 task A-4. Robolectric is
 * required for the WorkManager scaffolding (`TestListenableWorkerBuilder`
 * needs a Context with the Application framework wired up).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LibraryMirrorPushWorkerTest {

    private lateinit var server: MockWebServer
    private lateinit var client: LibraryClient
    private lateinit var dao: FakeDocumentDao
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        client = LibraryClient(
            baseUrlProvider = { server.url("").toString().trimEnd('/') },
            http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build(),
        )
        dao = FakeDocumentDao()
    }

    @After fun tearDown() {
        server.shutdown()
        LibraryMirrorPushDependencies.holder = null
    }

    private fun installDeps(hasAccount: Boolean = true) {
        LibraryMirrorPushDependencies.holder = LibraryMirrorPushDependencies.Holder(
            client = client,
            dao = dao,
            credentials = CredentialsProvider { hasAccount },
        )
    }

    private fun summaryBody(received: Int, processed: Int = received): String = """
        {
          "received":$received,
          "processed":$processed,
          "created":$processed,
          "updated":0,
          "reactivated":0,
          "deleted":0,
          "skipped":0,
          "missing_deleted":0,
          "server_time":"2026-05-22T15:00:00+00:00"
        }
    """.trimIndent()

    private fun buildWorker(): LibraryMirrorPushWorker =
        TestListenableWorkerBuilder<LibraryMirrorPushWorker>(context).build()

    // -------------------- success / wire shape --------------------

    @Test
    fun `success path pushes one chunk with exactly the X-1 wire shape`() = runTest {
        installDeps()
        dao.rows.add(docEntity(id = 1, contentHash = "h1", title = "Dune"))
        dao.rows.add(docEntity(id = 2, contentHash = "h2", title = "Foundation"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(summaryBody(2)))

        val result = buildWorker().doWork()

        assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
        assertThat(server.requestCount).isEqualTo(1)

        val req = server.takeRequest()
        assertThat(req.path).isEqualTo("/library/v1/sync")
        assertThat(req.method).isEqualTo("POST")
        val body = req.body.readUtf8()
        val parsed = Json.parseToJsonElement(body).jsonObject

        // Root wrapper.
        assertThat(parsed.keys).containsExactly("items")
        val items = parsed["items"]!!.jsonArray
        assertThat(items).hasSize(2)

        // Every entry must carry exactly the X-1 wire shape: no
        // `content_hash`, no nested `metadata`, no enum-internal fields.
        // Pydantic `extra="forbid"` rejects unknown keys.
        val expectedKeys = setOf(
            "identity_hash",
            "identity_hash_version",
            "status",
            "last_seen_at",
            "metadata_id",
            "title",
            "authors",
            "series_name",
            "series_index",
            "isbn",
            "language",
            "subjects",
            "opds_href",
        )
        items.forEach { entry ->
            val obj = entry.jsonObject
            assertThat(obj.keys).isEqualTo(expectedKeys)
            // The renamed-to-`identity_hash` contract; X-1 rejects `content_hash`.
            assertThat(obj.containsKey("content_hash")).isFalse()
            // No nested `metadata` block — server expects FLAT entries.
            assertThat(obj.containsKey("metadata")).isFalse()
            assertThat(obj["status"]!!.jsonPrimitive.contentOrNull).isEqualTo("present")
            assertThat(obj["identity_hash_version"]!!.jsonPrimitive.intOrNull).isEqualTo(1)
        }
    }

    @Test
    fun `wire payload contains identity_hash never content_hash even as a substring of the JSON`() = runTest {
        installDeps()
        dao.rows.add(docEntity(id = 1, contentHash = "abc"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(summaryBody(1)))

        buildWorker().doWork()

        val body = server.takeRequest().body.readUtf8()
        // Defense in depth: the legacy server column name must never
        // appear on this endpoint's wire — Pydantic rejects it.
        assertThat(body).doesNotContain("content_hash")
        assertThat(body).contains("\"identity_hash\":\"abc\"")
    }

    // -------------------- chunking --------------------

    @Test
    fun `chunking splits 1200 books into 500+500+200 POSTs`() = runTest {
        installDeps()
        val books = (1..1200).map { i -> docEntity(id = i.toLong(), contentHash = "h%04d".format(i)) }
        dao.rows.addAll(books)
        repeat(3) { idx ->
            val size = if (idx < 2) 500 else 200
            server.enqueue(MockResponse().setResponseCode(200).setBody(summaryBody(size)))
        }

        val result = buildWorker().doWork()

        assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
        assertThat(server.requestCount).isEqualTo(3)
        val sizes = (1..3).map {
            val req = server.takeRequest()
            val items = Json.parseToJsonElement(req.body.readUtf8()).jsonObject["items"]!!.jsonArray
            items.size
        }
        assertThat(sizes).containsExactly(500, 500, 200).inOrder()
    }

    @Test
    fun `last_seen_at is identical across every chunk in a single run`() = runTest {
        installDeps()
        val books = (1..600).map { i -> docEntity(id = i.toLong(), contentHash = "h%04d".format(i)) }
        dao.rows.addAll(books)
        repeat(2) { server.enqueue(MockResponse().setResponseCode(200).setBody(summaryBody(500))) }

        buildWorker().doWork()

        val ts1 = firstLastSeenAt(server.takeRequest())
        val ts2 = firstLastSeenAt(server.takeRequest())
        assertThat(ts1).isNotNull()
        assertThat(ts1).isEqualTo(ts2)
    }

    private fun firstLastSeenAt(req: RecordedRequest): String? {
        val items = Json.parseToJsonElement(req.body.readUtf8()).jsonObject["items"]!!.jsonArray
        return items.firstOrNull()?.jsonObject?.get("last_seen_at")?.jsonPrimitive?.contentOrNull
    }

    // -------------------- failure semantics --------------------

    @Test
    fun `4xx drops the offending chunk and continues with the rest`() = runTest {
        installDeps()
        // 600 books → 2 chunks. First returns 422, second returns 200.
        val books = (1..600).map { i -> docEntity(id = i.toLong(), contentHash = "h%04d".format(i)) }
        dao.rows.addAll(books)
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"detail":"bad"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody(summaryBody(100)))

        val result = buildWorker().doWork()

        // 4xx is "the server rejected this batch" — we drop it and move
        // on. The overall run is still a Success because retrying
        // forever on a server-rejected payload is wrong.
        assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `5xx requests a WorkManager retry without continuing to the next chunk`() = runTest {
        installDeps()
        val books = (1..600).map { i -> docEntity(id = i.toLong(), contentHash = "h%04d".format(i)) }
        dao.rows.addAll(books)
        server.enqueue(MockResponse().setResponseCode(500).setBody("server is on fire"))
        // Second response intentionally NOT enqueued — worker must bail
        // out after the 500 rather than continuing.

        val result = buildWorker().doWork()

        assertThat(result).isInstanceOf(ListenableWorker.Result.Retry::class.java)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `401 fails the worker without retry`() = runTest {
        installDeps()
        dao.rows.add(docEntity(id = 1, contentHash = "h1"))
        server.enqueue(MockResponse().setResponseCode(401).setBody("nope"))

        val result = buildWorker().doWork()

        assertThat(result).isInstanceOf(ListenableWorker.Result.Failure::class.java)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `no account configured is a quick success no-op`() = runTest {
        installDeps(hasAccount = false)
        dao.rows.add(docEntity(id = 1, contentHash = "h1"))

        val result = buildWorker().doWork()

        assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `empty library is a quick success with no network calls`() = runTest {
        installDeps()

        val result = buildWorker().doWork()

        assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `missing dependencies returns failure`() = runTest {
        // Intentionally do NOT installDeps() — holder is null.
        LibraryMirrorPushDependencies.holder = null

        val result = buildWorker().doWork()

        assertThat(result).isInstanceOf(ListenableWorker.Result.Failure::class.java)
    }

    // -------------------- entry mapping --------------------

    @Test
    fun `entries carry identity_hash_version from the DocumentEntity not always 1`() = runTest {
        installDeps()
        // Mixed versions: today identityHashVersion is always 1, but the
        // field must round-trip. F-2's schema makes this user-data, so
        // we lock the behaviour now.
        dao.rows.add(docEntity(id = 1, contentHash = "h1", identityHashVersion = 1))
        dao.rows.add(docEntity(id = 2, contentHash = "h2", identityHashVersion = 5))
        server.enqueue(MockResponse().setResponseCode(200).setBody(summaryBody(2)))

        buildWorker().doWork()

        val items = Json.parseToJsonElement(server.takeRequest().body.readUtf8())
            .jsonObject["items"]!!.jsonArray
        val byHash = items.associateBy { it.jsonObject["identity_hash"]!!.jsonPrimitive.content }
        assertThat(byHash["h1"]!!.jsonObject["identity_hash_version"]!!.jsonPrimitive.int())
            .isEqualTo(1)
        assertThat(byHash["h2"]!!.jsonObject["identity_hash_version"]!!.jsonPrimitive.int())
            .isEqualTo(5)
    }

    @Test
    fun `author string is split into the authors list`() = runTest {
        installDeps()
        dao.rows.add(docEntity(id = 1, contentHash = "h1", author = "A & B"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(summaryBody(1)))

        buildWorker().doWork()

        val items = Json.parseToJsonElement(server.takeRequest().body.readUtf8())
            .jsonObject["items"]!!.jsonArray
        val authors = items[0].jsonObject["authors"]!!.jsonArray
        assertThat(authors.map { it.jsonPrimitive.content }).containsExactly("A", "B").inOrder()
    }
}

// kotlinx.serialization 1.7 doesn't expose JsonPrimitive.int directly without
// the import we already pull in via `intOrNull`; alias for readability.
private fun JsonPrimitive.int(): Int = intOrNull ?: error("expected int, got $content")

// ---------------------------------------------------------------------------
// Test fixtures
// ---------------------------------------------------------------------------

private fun docEntity(
    id: Long,
    contentHash: String,
    title: String = "t",
    author: String? = null,
    identityHashVersion: Int = 1,
): DocumentEntity = DocumentEntity(
    id = id,
    metadataId = null,
    contentHash = contentHash,
    title = title,
    author = author,
    downloadUrl = "https://example/$contentHash",
    localPath = "/p/$contentHash",
    coverPath = null,
    downloadedAt = id,
    seriesName = null,
    seriesIndex = null,
    librarySyncedAt = null,
    identityHashVersion = identityHashVersion,
)

/**
 * Minimal in-memory DocumentDao. Only the methods the worker uses are
 * implemented; the rest throw so accidental future calls are loud.
 */
private class FakeDocumentDao : DocumentDao {
    val rows: MutableList<DocumentEntity> = mutableListOf()

    override fun observeAll(): Flow<List<DocumentEntity>> = flowOf(rows.toList())

    // ---- everything else is unused in these tests ----

    override suspend fun insert(doc: DocumentEntity): Long = throw nope("insert")
    override suspend fun update(doc: DocumentEntity): Unit = throw nope("update")
    override suspend fun findByMetadataId(id: String): DocumentEntity? = throw nope("findByMetadataId")
    override suspend fun findByContentHash(hash: String): DocumentEntity? = throw nope("findByContentHash")
    override suspend fun findByDownloadUrl(url: String): DocumentEntity? = throw nope("findByDownloadUrl")
    override suspend fun findById(id: Long): DocumentEntity? = throw nope("findById")
    override fun observeSeriesContinuationCandidates(
        startedThreshold: Double,
        maxItems: Int,
    ): Flow<List<DocumentEntity>> = flowOf(emptyList())
    override suspend fun deleteById(id: Long): Int = throw nope("deleteById")
    override suspend fun deleteAll(): Unit = throw nope("deleteAll")
    override suspend fun findUnsyncedToLibrary(): List<DocumentEntity> = throw nope("findUnsyncedToLibrary")
    override suspend fun markLibrarySynced(id: Long, at: Long): Unit = throw nope("markLibrarySynced")

    private fun nope(name: String) =
        UnsupportedOperationException("FakeDocumentDao.$name not implemented for this test")
}
