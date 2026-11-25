package io.theficos.ereader.data.library.convert

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.data.library.convert.ConversionRepository.Companion.NETWORK_RETRY_MS
import io.theficos.ereader.data.library.convert.ConversionRepository.Companion.POLL_MS
import io.theficos.ereader.data.local.db.ConversionJobEntity
import io.theficos.ereader.data.local.db.DocumentEntity
import io.theficos.ereader.data.local.db.EReaderDatabase
import io.theficos.ereader.testing.awaitRequest
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConversionRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var db: EReaderDatabase
    private lateinit var sourceDir: File
    private var clock = 1_000_000L
    private var owner: String? = OWNER
    private var wakes = 0
    private var keys = 0

    /** What the next import answers; null imports for real into the documents table. */
    private var importAnswer: ImportOutcome? = null
    private val imported = mutableListOf<ByteArray>()
    private var requestsAtImport = -1

    private val pdf = "%PDF-1.7 a small test document".toByteArray()
    private val epub = "PK not really an epub, the importer is faked".toByteArray()

    @Before fun setUp() {
        server = MockWebServer()
        // An unexpected request gets an answer the repository treats as a refusal, never a hang.
        (server.dispatcher as QueueDispatcher).setFailFast(MockResponse().setResponseCode(418))
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EReaderDatabase::class.java)
            .allowMainThreadQueries().build()
        db.openHelper.writableDatabase
        sourceDir = tmp.newFolder("sources")
    }

    @After fun tearDown() {
        try {
            db.close()
        } finally {
            server.shutdown()
        }
    }

    /** A fresh repository over the same database and files, as after process death. */
    private fun repository() = ConversionRepository(
        client = ConvertClient(
            baseUrlProvider = { server.url("/").toString() },
            // OkHttp would otherwise resend a request whose connection broke, hiding the failure.
            http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).retryOnConnectionFailure(false).build(),
        ),
        dao = db.conversionJobDao(),
        documents = db.documentDao(),
        owner = { owner },
        ownerFlow = MutableStateFlow(owner),
        sourceDir = sourceDir,
        downloadDir = tmp.newFolder(),
        importEpub = { file, _ ->
            imported += file.readBytes()
            requestsAtImport = server.requestCount
            importAnswer ?: ImportOutcome.Saved(saveBook(file.readBytes()))
        },
        wake = { wakes++ },
        now = { clock },
        newId = { "id-${++keys}" },
    )

    /** Like the sideload importer: the same book twice is the same row. */
    private suspend fun saveBook(bytes: ByteArray): Long {
        val hash = sha256(bytes)
        db.documentDao().findByContentHash(hash)?.let { return it.id }
        return db.documentDao().insert(
            DocumentEntity(
                metadataId = null, contentHash = hash, title = "Book", author = null,
                downloadUrl = "sideload://v1/$hash", localPath = "/books/x.epub", coverPath = null, downloadedAt = 0,
            ),
        )
    }

    private suspend fun ConversionRepository.startPdf(bytes: ByteArray = pdf, maxBytes: Long = 1_000_000) =
        start("Field notes.pdf", maxBytes) { bytes.inputStream() }

    /** Steps until nothing is left to do, letting the clock run through each wait. */
    private suspend fun ConversionRepository.drain() {
        repeat(30) {
            val wait = step() ?: return
            clock += wait
        }
        error("conversion never settled")
    }

    private suspend fun ConversionRepository.only(): Conversion = conversions.first().single()

    private fun job(
        state: String,
        failure: String? = null,
        retryable: Boolean = false,
        detail: String? = null,
        epubBytes: ByteArray = epub,
    ): MockResponse {
        val result = if (state == "ready" || state == "review") {
            """{"sha256":"${sha256(epubBytes)}","bytes":${epubBytes.size},"rendition_id":"urn:uuid:1"}"""
        } else "null"
        val review = if (state == "review") {
            """{"reason":"2 pages need review","affected_pages":[{"page":3,"label":"iii"},{"page":12,"label":"12"}],"issues":[]}"""
        } else "null"
        val fail = failure?.let {
            """{"code":"$it","retryable":$retryable,"detail":${detail?.let { d -> "\"$d\"" } ?: "null"}}"""
        } ?: "null"
        return json(
            200,
            """{"id":"job-1","state":"$state","mode":"local","source":{"sha256":"x","bytes":1},""" +
                """"result":$result,"review":$review,"failure":$fail,"attempt":1}""",
        )
    }

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun refusal(code: Int, detail: String, retryAfter: Long? = null) =
        json(code, """{"detail":"$detail"}""").apply { retryAfter?.let { setHeader("Retry-After", it) } }

    private fun epubBody(bytes: ByteArray = epub) =
        MockResponse().setHeader("Content-Type", "application/epub+zip").setBody(Buffer().write(bytes))

    private fun RecordedRequest.route() = "$method ${path!!.removePrefix("/convert/v1")}"

    private fun RecordedRequest.jsonBody() = Json.parseToJsonElement(body.readUtf8()).jsonObject

    private fun requests(n: Int) = List(n) { server.awaitRequest().route() }

    // Happy path.

    @Test fun `a PDF goes up, converts, comes back as a book and is acknowledged only after the import`() = runTest {
        val repo = repository()
        assertThat(repo.startPdf()).isEqualTo(ConversionStart.Started("id-1"))
        assertThat(wakes).isEqualTo(1)
        assertThat(repo.only().status).isEqualTo(ConversionStatus.UPLOADING)

        server.enqueue(job("created").setResponseCode(201))
        server.enqueue(job("queued").setResponseCode(202))
        assertThat(repo.step()).isEqualTo(POLL_MS)
        val create = server.awaitRequest()
        assertThat(create.route()).isEqualTo("POST /jobs")
        val body = create.jsonBody()
        assertThat(body["idempotency_key"]!!.jsonPrimitive.content).isEqualTo("id-2")
        assertThat(body["sha256"]!!.jsonPrimitive.content).isEqualTo(sha256(pdf))
        assertThat(body["bytes"]!!.jsonPrimitive.content).isEqualTo(pdf.size.toString())
        assertThat(body["mode"]!!.jsonPrimitive.content).isEqualTo("local")
        val upload = server.awaitRequest()
        assertThat(upload.route()).isEqualTo("PUT /jobs/job-1/source")
        assertThat(upload.getHeader("Content-Type")).startsWith("application/pdf")
        assertThat(upload.body.readByteArray()).isEqualTo(pdf)
        assertThat(repo.only().status).isEqualTo(ConversionStatus.QUEUED)

        // Not before the contract's five seconds.
        assertThat(repo.step()).isEqualTo(POLL_MS)
        assertThat(server.requestCount).isEqualTo(2)

        clock += POLL_MS
        server.enqueue(job("running"))
        assertThat(repo.step()).isEqualTo(POLL_MS)
        assertThat(repo.only().status).isEqualTo(ConversionStatus.CONVERTING)

        clock += POLL_MS
        server.enqueue(job("ready"))
        server.enqueue(epubBody())
        server.enqueue(job("acknowledged"))
        repo.drain()
        assertThat(requests(4)).containsExactly(
            "GET /jobs/job-1", "GET /jobs/job-1", "GET /jobs/job-1/epub", "POST /jobs/job-1/acknowledge",
        ).inOrder()
        assertThat(imported.single()).isEqualTo(epub)
        assertThat(requestsAtImport).isEqualTo(5) // the download was the last request before the import

        val done = repo.only()
        assertThat(done.status).isEqualTo(ConversionStatus.READY)
        assertThat(done.documentId).isNotNull()
        assertThat(done.canRemove).isTrue()
        assertThat(sourceDir.listFiles()).isEmpty()
    }

    @Test fun `a book that needs a look is imported and says which pages`() = runTest {
        val repo = repository()
        repo.startPdf()
        server.enqueue(job("review"))
        server.enqueue(epubBody())
        server.enqueue(job("acknowledged"))
        repo.drain()
        val done = repo.only()
        assertThat(done.status).isEqualTo(ConversionStatus.REVIEW)
        assertThat(done.review!!.affectedPages.map { it.page }).containsExactly(3, 12).inOrder()
        assertThat(done.review!!.affectedPages.first().label).isEqualTo("iii")
        assertThat(imported).hasSize(1)
    }

    // Resuming after process death, at each stage.

    @Test fun `saved but never sent, a new process creates the job with the saved key`() = runTest {
        repository().startPdf()
        server.enqueue(job("created"))
        server.enqueue(job("queued"))
        repository().step()
        assertThat(server.awaitRequest().jsonBody()["idempotency_key"]!!.jsonPrimitive.content).isEqualTo("id-2")
        assertThat(server.awaitRequest().route()).isEqualTo("PUT /jobs/job-1/source")
    }

    @Test fun `an upload whose answer was lost is not sent again when the server already has it`() = runTest {
        val repo = repository()
        repo.startPdf()
        server.enqueue(job("created"))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertThat(repo.step()).isEqualTo(ConversionRepository.UPLOAD_RETRY_MS)
        requests(2)

        // The process dies; the next one asks with the same key and learns the PDF arrived.
        clock += ConversionRepository.UPLOAD_RETRY_MS
        server.enqueue(job("queued"))
        val next = repository()
        assertThat(next.step()).isEqualTo(POLL_MS)
        val ask = server.awaitRequest()
        assertThat(ask.route()).isEqualTo("POST /jobs")
        assertThat(ask.jsonBody()["idempotency_key"]!!.jsonPrimitive.content).isEqualTo("id-2")
        assertThat(server.requestCount).isEqualTo(3)
        assertThat(next.only().status).isEqualTo(ConversionStatus.QUEUED)
    }

    @Test fun `a conversion on the server is polled, not created again, after process death`() = runTest {
        insert(stage = "convert", jobId = "job-1")
        server.enqueue(job("running"))
        repository().step()
        assertThat(server.awaitRequest().route()).isEqualTo("GET /jobs/job-1")
    }

    @Test fun `a download cut short by process death starts again`() = runTest {
        insert(stage = "import", jobId = "job-1", jobJson = readyJson())
        server.enqueue(epubBody())
        server.enqueue(job("acknowledged"))
        val repo = repository()
        repo.drain()
        assertThat(requests(2)).containsExactly("GET /jobs/job-1/epub", "POST /jobs/job-1/acknowledge").inOrder()
        assertThat(repo.only().status).isEqualTo(ConversionStatus.READY)
    }

    @Test fun `a book imported before process death is acknowledged without a second import`() = runTest {
        insert(stage = "acknowledge", jobId = "job-1", jobJson = readyJson(), documentId = 7)
        server.enqueue(job("acknowledged"))
        val repo = repository()
        repo.drain()
        assertThat(server.awaitRequest().route()).isEqualTo("POST /jobs/job-1/acknowledge")
        assertThat(imported).isEmpty()
        assertThat(repo.only().documentId).isEqualTo(7)
    }

    @Test fun `resume wakes the worker only while a conversion is under way`() = runTest {
        val repo = repository()
        repo.resume()
        assertThat(wakes).isEqualTo(0)
        insert(stage = "convert", jobId = "job-1")
        repo.resume()
        assertThat(wakes).isEqualTo(1)
    }

    // Creating the job.

    @Test fun `a hosted plan without writes fails for good and drops the PDF copy`() = runTest {
        val row = failOnCreate(refusal(403, "hosting_subscription_required"))
        assertThat(row.problem).isEqualTo("hosting_subscription_required")
        assertThat(row.canRetry).isFalse()
        assertThat(sourceDir.listFiles()).isEmpty()
    }

    @Test fun `a source too large for the server fails`() = runTest {
        assertThat(failOnCreate(refusal(413, "source_too_large")).problem).isEqualTo("source_too_large")
    }

    @Test fun `a schema error fails as a refusal`() = runTest {
        val row = failOnCreate(json(422, """{"detail":[{"loc":["body","sha256"],"msg":"bad"}]}"""))
        assertThat(row.problem).isEqualTo(ConversionRepository.INVALID_REQUEST)
    }

    @Test fun `conversion switched off on the server fails plainly`() = runTest {
        assertThat(failOnCreate(refusal(404, "Not Found")).problem).isEqualTo(ConversionRepository.CONVERSION_OFF)
    }

    @Test fun `an idempotency conflict is retried with a new key`() = runTest {
        val repo = repository()
        val row = failOnCreate(refusal(409, "idempotency_conflict"), repo)
        assertThat(row.canRetry).isTrue()

        repo.retry(row.id)
        server.enqueue(job("queued"))
        repo.step()
        assertThat(server.awaitRequest().jsonBody()["idempotency_key"]!!.jsonPrimitive.content).isEqualTo("id-3")
    }

    @Test fun `no running converter fails with a retry that keeps the key`() = runTest {
        val repo = repository()
        val row = failOnCreate(refusal(503, "converter_unavailable", retryAfter = 60), repo)
        assertThat(row.problem).isEqualTo("converter_unavailable")
        assertThat(row.canRetry).isTrue()

        repo.retry(row.id)
        assertThat(wakes).isEqualTo(2)
        server.enqueue(job("queued"))
        repo.step()
        assertThat(server.awaitRequest().jsonBody()["idempotency_key"]!!.jsonPrimitive.content).isEqualTo("id-2")
    }

    @Test fun `too many active jobs waits for Retry-After before asking again`() = runTest {
        val repo = repository()
        repo.startPdf()
        server.enqueue(refusal(429, "too_many_active_jobs", retryAfter = 60))
        assertThat(repo.step()).isEqualTo(60_000)
        server.awaitRequest()
        val waiting = repo.only()
        assertThat(waiting.status).isEqualTo(ConversionStatus.UPLOADING)
        assertThat(waiting.problem).isEqualTo("too_many_active_jobs")
        assertThat(waiting.waitingUntil).isEqualTo(clock + 60_000)

        clock += 30_000
        assertThat(repo.step()).isEqualTo(30_000)
        assertThat(server.requestCount).isEqualTo(1)

        clock += 30_000
        server.enqueue(job("queued"))
        repo.step()
        assertThat(server.awaitRequest().route()).isEqualTo("POST /jobs")
        assertThat(repo.only().waitingUntil).isNull()
    }

    @Test fun `the daily limit waits as long as the server says`() = runTest {
        val repo = repository()
        repo.startPdf()
        server.enqueue(refusal(429, "daily_limit_reached", retryAfter = 3_600))
        assertThat(repo.step()).isEqualTo(3_600_000)
        assertThat(repo.only().problem).isEqualTo("daily_limit_reached")
    }

    @Test fun `signed out waits instead of failing`() = runTest {
        val repo = repository()
        repo.startPdf()
        server.enqueue(refusal(401, "invalid_token"))
        assertThat(repo.step()).isEqualTo(ConversionRepository.SIGNED_OUT_WAIT_MS)
        assertThat(repo.only().problem).isEqualTo(ConversionRepository.SIGNED_OUT)
    }

    // Sending the PDF.

    @Test fun `a timed out upload is tried once more, then left to the reader`() = runTest {
        val repo = repository()
        repo.startPdf()
        server.enqueue(job("created"))
        server.enqueue(refusal(408, "upload_timed_out"))
        assertThat(repo.step()).isEqualTo(ConversionRepository.UPLOAD_RETRY_MS)
        clock += ConversionRepository.UPLOAD_RETRY_MS
        server.enqueue(job("created"))
        server.enqueue(refusal(408, "upload_timed_out"))
        assertThat(repo.step()).isNull()
        assertThat(requests(4).count { it.startsWith("PUT") }).isEqualTo(2)

        val failed = repo.only()
        assertThat(failed.problem).isEqualTo(ConversionRepository.UPLOAD_FAILED)
        assertThat(failed.canRetry).isTrue()
        // Nothing more goes out until the reader asks.
        clock += 3_600_000
        assertThat(repo.step()).isNull()
        assertThat(server.requestCount).isEqualTo(4)

        repo.retry(failed.id)
        server.enqueue(job("created"))
        server.enqueue(job("queued"))
        repo.step()
        assertThat(requests(2)).containsExactly("POST /jobs", "PUT /jobs/job-1/source").inOrder()
    }

    @Test fun `an upload under way elsewhere is waited for, then asked about`() = runTest {
        val repo = repository()
        repo.startPdf()
        server.enqueue(job("created"))
        server.enqueue(refusal(409, "upload_in_progress"))
        assertThat(repo.step()).isEqualTo(ConversionRepository.UPLOAD_IN_PROGRESS_WAIT_MS)
        assertThat(repo.only().problem).isEqualTo("upload_in_progress")
        clock += ConversionRepository.UPLOAD_IN_PROGRESS_WAIT_MS
        server.enqueue(job("queued"))
        repo.step()
        assertThat(requests(3).last()).isEqualTo("POST /jobs")
        assertThat(repo.only().status).isEqualTo(ConversionStatus.QUEUED)
    }

    @Test fun `a source the server no longer accepts means the job moved on`() = runTest {
        val repo = repository()
        repo.startPdf()
        server.enqueue(job("created"))
        server.enqueue(refusal(409, "not_accepting_source"))
        server.enqueue(job("running"))
        repo.step()
        assertThat(requests(3).last()).isEqualTo("GET /jobs/job-1")
        assertThat(repo.only().status).isEqualTo(ConversionStatus.CONVERTING)
    }

    @Test fun `a body over the limit, a wrong media type and a file that is not a PDF fail`() = runTest {
        for ((answer, problem) in listOf(
            refusal(413, "request body exceeds 10 bytes") to "source_too_large",
            refusal(415, "unsupported_media_type") to "not_a_pdf",
            refusal(422, "not_a_pdf") to "not_a_pdf",
        )) {
            clock += 1 // newest first
            val repo = repository()
            repo.startPdf(bytes = pdf + problem.toByteArray())
            server.enqueue(job("created"))
            server.enqueue(answer)
            assertThat(repo.step()).isNull()
            assertThat(repo.conversions.first().first().problem).isEqualTo(problem)
        }
    }

    @Test fun `a checksum the server disagrees with is sent again`() = runTest {
        val repo = repository()
        repo.startPdf()
        server.enqueue(job("created"))
        server.enqueue(refusal(422, "checksum_mismatch"))
        assertThat(repo.step()).isEqualTo(ConversionRepository.UPLOAD_RETRY_MS)
    }

    // Conversion outcomes.

    @Test fun `a failed conversion is acknowledged and explained, with no retry when it would fail again`() = runTest {
        val repo = repository()
        repo.startPdf()
        server.enqueue(job("failed", failure = "encrypted", detail = "the PDF is encrypted"))
        server.enqueue(job("acknowledged"))
        repo.drain()
        assertThat(requests(2)).containsExactly("POST /jobs", "POST /jobs/job-1/acknowledge").inOrder()
        val failed = repo.only()
        assertThat(failed.status).isEqualTo(ConversionStatus.FAILED)
        assertThat(failed.problem).isEqualTo("encrypted")
        assertThat(failed.detail).isEqualTo("the PDF is encrypted")
        assertThat(failed.canRetry).isFalse()
        assertThat(sourceDir.listFiles()).isEmpty()
    }

    @Test fun `a retryable failure starts a new job with a new key`() = runTest {
        val repo = repository()
        repo.startPdf()
        server.enqueue(job("failed", failure = "worker_lost", retryable = true))
        server.enqueue(job("acknowledged"))
        repo.drain()
        requests(2)
        val failed = repo.only()
        assertThat(failed.canRetry).isTrue()

        repo.retry(failed.id)
        server.enqueue(job("queued"))
        repo.step()
        assertThat(server.awaitRequest().jsonBody()["idempotency_key"]!!.jsonPrimitive.content).isEqualTo("id-3")
    }

    @Test fun `a job the server forgot fails and can start over`() = runTest {
        insert(stage = "convert", jobId = "job-1")
        server.enqueue(refusal(404, "job_not_found"))
        val repo = repository()
        assertThat(repo.step()).isNull()
        assertThat(repo.only().problem).isEqualTo(ConversionRepository.JOB_GONE)
        assertThat(repo.only().canRetry).isTrue()
    }

    @Test fun `a busy server while polling waits for Retry-After`() = runTest {
        insert(stage = "convert", jobId = "job-1")
        server.enqueue(refusal(503, "converter_unavailable", retryAfter = 45))
        assertThat(repository().step()).isEqualTo(45_000)
    }

    // Getting the book.

    @Test fun `a result already gone fails without acknowledging`() = runTest {
        insert(stage = "import", jobId = "job-1", jobJson = readyJson())
        server.enqueue(refusal(410, "result_gone"))
        val repo = repository()
        assertThat(repo.step()).isNull()
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(repo.only().problem).isEqualTo(ConversionRepository.RESULT_GONE)
        assertThat(repo.only().canRetry).isTrue()
    }

    @Test fun `a download that does not match the result is tried three times, never imported`() = runTest {
        insert(stage = "import", jobId = "job-1", jobJson = readyJson())
        val repo = repository()
        repeat(3) { server.enqueue(epubBody("tampered".toByteArray())) }
        repo.drain()
        assertThat(server.requestCount).isEqualTo(3)
        assertThat(imported).isEmpty()
        assertThat(repo.only().problem).isEqualTo(ConversionRepository.DOWNLOAD_FAILED)
        assertThat(repo.only().canRetry).isTrue()
    }

    @Test fun `an EPUB the importer rejects is not acknowledged`() = runTest {
        insert(stage = "import", jobId = "job-1", jobJson = readyJson())
        importAnswer = ImportOutcome.Invalid
        server.enqueue(epubBody())
        val repo = repository()
        repo.drain()
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(repo.only().problem).isEqualTo(ConversionRepository.INVALID_EPUB)
        assertThat(repo.only().canRetry).isFalse()
    }

    @Test fun `an import that fails is retried by downloading again, then acknowledged`() = runTest {
        insert(stage = "import", jobId = "job-1", jobJson = readyJson())
        importAnswer = ImportOutcome.Failed
        server.enqueue(epubBody())
        val repo = repository()
        repo.drain()
        assertThat(server.requestCount).isEqualTo(1)
        val failed = repo.only()
        assertThat(failed.problem).isEqualTo(ConversionRepository.IMPORT_FAILED)

        importAnswer = null
        repo.retry(failed.id)
        server.enqueue(epubBody())
        server.enqueue(job("acknowledged"))
        repo.drain()
        assertThat(requests(3).drop(1)).containsExactly("GET /jobs/job-1/epub", "POST /jobs/job-1/acknowledge").inOrder()
        assertThat(repo.only().status).isEqualTo(ConversionStatus.READY)
    }

    // The same PDF twice.

    @Test fun `sharing the same PDF again while it converts gives the same conversion`() = runTest {
        val repo = repository()
        repo.startPdf()
        assertThat(repo.startPdf()).isEqualTo(ConversionStart.AlreadyConverting("id-1"))
        assertThat(repo.conversions.first()).hasSize(1)
        assertThat(sourceDir.listFiles()!!.toList()).hasSize(1)
    }

    @Test fun `sharing a converted PDF again points at the book`() = runTest {
        val repo = repository()
        repo.startPdf()
        server.enqueue(job("ready"))
        server.enqueue(epubBody())
        server.enqueue(job("acknowledged"))
        repo.drain()
        val book = repo.only().documentId!!
        assertThat(repo.startPdf()).isEqualTo(ConversionStart.AlreadyInLibrary(book))
    }

    @Test fun `converting the same PDF again after forgetting it still gives one book`() = runTest {
        val repo = repository()
        repo.startPdf()
        server.enqueue(job("ready"))
        server.enqueue(epubBody())
        server.enqueue(job("acknowledged"))
        repo.drain()
        val first = repo.only()
        repo.remove(first.id)
        assertThat(repo.conversions.first()).isEmpty()

        repo.startPdf()
        server.enqueue(job("ready"))
        server.enqueue(epubBody())
        server.enqueue(job("acknowledged"))
        repo.drain()
        assertThat(repo.only().documentId).isEqualTo(first.documentId)
        assertThat(db.documentDao().observeAll().first()).hasSize(1)
    }

    @Test fun `a PDF over the limit is refused before anything is saved`() = runTest {
        val repo = repository()
        assertThat(repo.startPdf(maxBytes = 5)).isEqualTo(ConversionStart.TooLarge(5))
        assertThat(repo.conversions.first()).isEmpty()
        assertThat(sourceDir.listFiles()).isEmpty()
        assertThat(wakes).isEqualTo(0)
    }

    @Test fun `no server, no conversion`() = runTest {
        owner = null
        val repo = repository()
        assertThat(repo.startPdf()).isEqualTo(ConversionStart.NoServer)
        assertThat(repo.offer()).isEqualTo(ConversionOffer.NoServer)
    }

    @Test fun `another account's conversions are left alone`() = runTest {
        insert(stage = "convert", jobId = "job-1", owner = "elsewhere bob")
        val repo = repository()
        assertThat(repo.step()).isNull()
        assertThat(repo.conversions.first()).isEmpty()
        assertThat(server.requestCount).isEqualTo(0)
    }

    // Cancelling.

    @Test fun `cancelling before the job exists sends nothing`() = runTest {
        val repo = repository()
        repo.startPdf()
        repo.cancel("id-1")
        assertThat(repo.step()).isNull()
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(repo.only().status).isEqualTo(ConversionStatus.CANCELLED)
        assertThat(sourceDir.listFiles()).isEmpty()
    }

    @Test fun `cancelling a conversion on the server tells the server`() = runTest {
        insert(stage = "convert", jobId = "job-1")
        val repo = repository()
        assertThat(repo.only().canCancel).isTrue()
        repo.cancel("id-1")
        server.enqueue(job("cancelled"))
        repo.drain()
        assertThat(server.awaitRequest().route()).isEqualTo("POST /jobs/job-1/cancel")
        assertThat(repo.only().status).isEqualTo(ConversionStatus.CANCELLED)
        assertThat(repo.only().canCancel).isFalse()
    }

    @Test fun `a job that finished before the cancel arrived is cleared, not imported`() = runTest {
        insert(stage = "convert", jobId = "job-1")
        val repo = repository()
        repo.cancel("id-1")
        server.enqueue(refusal(409, "not_cancellable"))
        server.enqueue(job("ready"))
        server.enqueue(job("acknowledged"))
        repo.drain()
        assertThat(requests(3)).containsExactly(
            "POST /jobs/job-1/cancel", "GET /jobs/job-1", "POST /jobs/job-1/acknowledge",
        ).inOrder()
        assertThat(imported).isEmpty()
        assertThat(repo.only().status).isEqualTo(ConversionStatus.CANCELLED)
    }

    @Test fun `cancelling cuts off an upload under way`() = runTest {
        val repo = repository()
        repo.startPdf()
        server.enqueue(job("created"))
        // The server holds its answer, so the upload is still going when the reader cancels.
        server.enqueue(job("queued").setHeadersDelay(5, TimeUnit.SECONDS))
        val stepping = async(Dispatchers.IO) { repo.step() }
        requests(2)
        repo.cancel("id-1")
        assertThat(stepping.await()).isEqualTo(0)

        server.enqueue(job("cancelled"))
        repo.drain()
        assertThat(server.awaitRequest().route()).isEqualTo("POST /jobs/job-1/cancel")
        assertThat(repo.only().status).isEqualTo(ConversionStatus.CANCELLED)
    }

    @Test fun `a cancel arriving while a step works is never overwritten`() = runTest {
        insert(stage = "convert", jobId = "job-1")
        val repo = repository()
        server.enqueue(job("ready").setHeadersDelay(1, TimeUnit.SECONDS))
        val stepping = async(Dispatchers.IO) { repo.step() }
        server.awaitRequest()
        repo.cancel("id-1")
        stepping.await()
        assertThat(db.conversionJobDao().find("id-1")!!.stage).isEqualTo("cancel")
    }

    // Discovery.

    @Test fun `the offer follows the capabilities`() = runTest {
        val repo = repository()
        server.enqueue(json(200, """{"available":true,"max_bytes":104857600,"max_pages":1000}"""))
        assertThat(repo.offer()).isEqualTo(ConversionOffer.Available(104_857_600))
        assertThat(server.awaitRequest().route()).isEqualTo("GET /capabilities")
        server.enqueue(json(200, """{"available":false,"max_bytes":104857600}"""))
        assertThat(repo.offer()).isEqualTo(ConversionOffer.Unavailable)
        server.enqueue(refusal(404, "Not Found"))
        assertThat(repo.offer()).isEqualTo(ConversionOffer.NotOffered)
        server.enqueue(refusal(401, "invalid_token"))
        assertThat(repo.offer()).isEqualTo(ConversionOffer.SignedOut)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertThat(repo.offer()).isEqualTo(ConversionOffer.Unreachable)
    }

    @Test fun `a network failure is retried later without failing`() = runTest {
        insert(stage = "convert", jobId = "job-1")
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val repo = repository()
        assertThat(repo.step()).isEqualTo(NETWORK_RETRY_MS)
        assertThat(repo.only().status).isEqualTo(ConversionStatus.QUEUED)
    }

    private suspend fun failOnCreate(answer: MockResponse, repo: ConversionRepository = repository()): Conversion {
        repo.startPdf()
        server.enqueue(answer)
        assertThat(repo.step()).isNull()
        server.awaitRequest()
        return repo.only().also { assertThat(it.status).isEqualTo(ConversionStatus.FAILED) }
    }

    private fun readyJson() =
        """{"id":"job-1","state":"ready","result":{"sha256":"${sha256(epub)}","bytes":${epub.size}}}"""

    private suspend fun insert(
        stage: String,
        jobId: String?,
        jobJson: String? = null,
        documentId: Long? = null,
        owner: String = OWNER,
    ) {
        val source = File(sourceDir, "id-1.pdf").apply { writeBytes(pdf) }
        db.conversionJobDao().insert(
            ConversionJobEntity(
                id = "id-1", owner = owner, idempotencyKey = "key-1", fileName = "Field notes.pdf",
                sha256 = sha256(pdf), bytes = pdf.size.toLong(), sourcePath = source.absolutePath, jobId = jobId,
                stage = stage, problem = null, jobJson = jobJson, retryAt = null, failures = 0,
                documentId = documentId, createdAt = clock, updatedAt = clock,
            ),
        )
    }

    private companion object {
        const val OWNER = "https://quire.example alice"

        fun sha256(bytes: ByteArray) =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
