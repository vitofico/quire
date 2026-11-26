package io.theficos.ereader.data.ai

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.theficos.ereader.core.model.Document
import io.theficos.ereader.core.model.DocumentIdentity
import io.theficos.ereader.core.model.SIDELOAD_URL_PREFIX
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.local.db.BookDetailsEntity
import io.theficos.ereader.data.local.db.EReaderDatabase
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BookDetailsRepositoryTest {
    private lateinit var db: EReaderDatabase
    private lateinit var documents: DocumentRepository
    private lateinit var server: MockWebServer
    private lateinit var openLibrary: MockWebServer
    private lateinit var dir: File
    private lateinit var ai: AiRepository
    private lateinit var repo: BookDetailsRepository
    private lateinit var client: AiClient
    private lateinit var http: OkHttpClient
    private val showTidied = MutableStateFlow(true)
    private val owner = MutableStateFlow<String?>("https://quire.example reader")
    private val unmetered = MutableStateFlow(true)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Midday, so an hour either way stays on the same local day. */
    private var now = LocalDate.of(2026, 11, 25).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    /** What the repository waited for, as [BookDetailsRepository.followLibrary] sleeps between passes. */
    private val waits = Channel<Long>(Channel.UNLIMITED)

    /** Responses by request path, taken in order; anything else answers 404. */
    private val responses = ConcurrentHashMap<String, ConcurrentLinkedQueue<MockResponse>>()
    private val received = ConcurrentLinkedQueue<RecordedRequest>()
    private var holdLookup: CountDownLatch? = null

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EReaderDatabase::class.java)
            .allowMainThreadQueries().build()
        db.openHelper.writableDatabase
        documents = DocumentRepository(db.documentDao())
        dir = kotlin.io.path.createTempDirectory("book-details").toFile()
        server = MockWebServer().apply { dispatcher = routes(); start() }
        openLibrary = MockWebServer().apply { dispatcher = routes(); start() }
        http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
        client = AiClient({ server.url("").toString().trimEnd('/') }, http, language = { "en" })
        ai = AiRepository(client, db.insightDao(), appLanguage = { "en" })
        answer("/ai/v1/config", """{"configured":true,"details_supported":true}""")
        answer("/ai/v1/preferences", """{"ai_enabled":true,"style":{"tone":"neutral","language":"auto"}}""")
        runBlocking { ai.refresh() }
        received.clear()
        repo = newRepository()
    }

    /** Open Library's https hosts, answered by [openLibrary]. */
    private val toOpenLibrary = Interceptor { chain ->
        chain.proceed(chain.request().newBuilder().url(openLibrary.url(chain.request().url.encodedPath)).build())
    }

    private fun newRepository() = BookDetailsRepository(
        client = client,
        ai = ai,
        documents = documents,
        dao = db.bookDetailsDao(),
        coversDir = dir,
        openLibraryHttp = http.newBuilder().addInterceptor(toOpenLibrary).build(),
        showTidied = showTidied,
        owner = owner,
        unmetered = unmetered,
        prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("book_details", Context.MODE_PRIVATE),
        scope = scope,
        clock = { now },
        sleep = { ms ->
            now += ms
            waits.send(ms)
        },
    )

    @After fun tearDown() {
        scope.cancel()
        holdLookup?.countDown()
        runCatching { server.shutdown() }
        runCatching { openLibrary.shutdown() }
        db.close()
        dir.deleteRecursively()
    }

    private fun routes() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            received += request
            if (request.path == "/details/v1/lookup") holdLookup?.await(10, TimeUnit.SECONDS)
            val path = request.path!!.substringBefore('?').let { if (request.path!!.startsWith("/details/v1/covers/")) request.path!! else it }
            return responses[path]?.poll() ?: MockResponse().setResponseCode(404)
        }
    }

    private fun answer(path: String, body: String, code: Int = 200, retryAfter: String? = null) {
        val response = MockResponse().setResponseCode(code).setBody(body)
        retryAfter?.let { response.setHeader("Retry-After", it) }
        responses.getOrPut(path) { ConcurrentLinkedQueue() } += response
    }

    private fun details(
        title: String = "Dune",
        authors: String = """["Frank Herbert"]""",
        origins: String = """{"title":"ai","authors":"openlibrary"}""",
        cover: String = """{"source":null,"url":null,"generated":false}""",
        tidy: String = "done",
        description: String? = null,
        key: String = "k1",
    ) = """{"key":"$key","book":{"title":"$title","authors":$authors""" +
        (description?.let { ""","description":"$it"""" } ?: "") +
        """},"origins":$origins,"cover":$cover,"tidy":"$tidy","cached":false}"""

    private fun lookups() = received.filter { it.path == "/details/v1/lookup" }

    /** The shelf's answer for [id], once it shows and meets [until]. */
    private suspend fun shownFor(id: Long, until: (BookDetails) -> Boolean = { true }): BookDetails =
        withTimeout(5_000) { repo.shown.first { it[id]?.let(until) == true } }.getValue(id)

    /** An applied answer, past the decline a tidy holds while its preview is open. */
    private fun applied(details: BookDetails) = details.response.declined == null

    private suspend fun waitFor(condition: suspend () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(10)
    }

    private fun startOfTomorrow() =
        Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().plusDays(1)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private suspend fun book(
        title: String = "dune_frank_herbert",
        author: String? = null,
        cover: String? = null,
        file: File = File(dir, "$title.epub"),
        url: String = "${SIDELOAD_URL_PREFIX}v1/hash-$title",
    ): Document {
        val id = documents.insert(DocumentIdentity(metadataId = "m-$title", contentHash = "hash-$title"), title, author, url, file.path, cover, 0L)
        return checkNotNull(documents.findById(id))
    }

    @Test fun `a book with a file name for a title is asked about once, and its answer shows offline`() = runBlocking {
        val doc = book()
        answer("/details/v1/lookup", details())

        repo.runAutoPass()

        val sent = Json.parseToJsonElement(lookups().single().body.readUtf8()).jsonObject
        assertThat(sent["identity"]!!.jsonObject["content_hash"]!!.jsonPrimitive.content).isEqualTo("hash-dune_frank_herbert")
        assertThat(sent["identity"]!!.jsonObject["metadata_id"]!!.jsonPrimitive.content).isEqualTo("m-dune_frank_herbert")
        assertThat(sent["book"]!!.jsonObject["title"]!!.jsonPrimitive.content).isEqualTo("dune_frank_herbert")
        assertThat(sent["source"]!!.jsonPrimitive.content).isEqualTo("file")
        assertThat(sent["cover"]!!.jsonPrimitive.content).isEqualTo("null")

        server.shutdown()
        repo.runAutoPass()
        assertThat(lookups()).hasSize(1)
        val shown = shownFor(doc.id)
        assertThat(doc.shownWith(shown).title).isEqualTo("Dune")
        assertThat(doc.shownWith(shown).author).isEqualTo("Frank Herbert")
        assertThat(documents.findById(doc.id)!!.title).isEqualTo("dune_frank_herbert")
    }

    @Test fun `a book that has everything is never sent`() = runBlocking {
        val epub = File(dir, "dune.epub").also { writeEpub(it, "Dune", "Frank Herbert", "A desert planet.") }
        val cover = File(dir, "dune.jpg").apply { writeText("jpg") }
        book(title = "Dune", author = "Frank Herbert", cover = cover.path, file = epub)

        repo.runAutoPass()
        repo.runAutoPass()

        assertThat(lookups()).isEmpty()
    }

    @Test fun `a 429 pauses the whole pass for as long as Retry-After says`() = runBlocking {
        book("first_book")
        book("second_book")
        answer("/details/v1/lookup", """{"detail":"details_rate_limited"}""", code = 429, retryAfter = "120")

        assertThat(repo.runAutoPass()).isEqualTo(now + 120_000)
        assertThat(lookups()).hasSize(1)
        assertThat(db.bookDetailsDao().all()).isEmpty()

        now += 119_000
        repo.runAutoPass()
        assertThat(lookups()).hasSize(1)

        now += 2_000
        answer("/details/v1/lookup", details())
        answer("/details/v1/lookup", details(title = "Second"))
        repo.runAutoPass()
        assertThat(lookups()).hasSize(3)
    }

    @Test fun `a tidy refused for the month, with no Retry-After, pauses the background pass for a day`() = runBlocking {
        val doc = book()
        answer("/details/v1/tidy", """{"detail":"monthly_ai_quota_exhausted"}""", code = 429)

        val error = runCatching { repo.propose(doc.id) }.exceptionOrNull()
        assertThat((error as AiHttpException).code).isEqualTo(429)
        assertThat(repo.runAutoPass()).isEqualTo(now + 24 * 3_600_000L)

        now += 23 * 3_600_000L
        repo.runAutoPass()
        assertThat(lookups()).isEmpty()

        now += 3_600_000L
        answer("/details/v1/lookup", details())
        repo.runAutoPass()
        assertThat(lookups()).hasSize(1)
    }

    @Test fun `offline, feature off and opted out each pause the pass and keep nothing`() = runBlocking {
        book("first_book")
        book("second_book")
        for (code in listOf(404, 409)) {
            answer("/details/v1/lookup", """{"detail":"nope"}""", code = code)
            val before = lookups().size
            repo.runAutoPass()
            assertThat(lookups()).hasSize(before + 1)
            repo.runAutoPass()
            assertThat(lookups()).hasSize(before + 1)
            now += 31 * 60_000L
        }
        server.shutdown()
        repo.runAutoPass()
        assertThat(db.bookDetailsDao().all()).isEmpty()
    }

    @Test fun `a spent allowance in the answer is kept, stops the pass, and the book is asked again a day later`() = runBlocking {
        val first = book("first_book")
        book("second_book")
        answer("/details/v1/lookup", details(tidy = "quota_exceeded", origins = """{"title":"file","authors":"openlibrary"}""", title = "first_book"))

        assertThat(repo.runAutoPass()).isEqualTo(startOfTomorrow())
        assertThat(lookups()).hasSize(1)
        assertThat(repo.observe(first.id).first { it != null }!!.response.book.authors).containsExactly("Frank Herbert")

        // No more lookups that day, even from a fresh start.
        now += 3_600_000L
        repo = newRepository()
        repo.runAutoPass()
        assertThat(lookups()).hasSize(1)

        now += 24 * 3_600_000L
        answer("/details/v1/lookup", details(title = "First"))
        answer("/details/v1/lookup", details(title = "Second"))
        repo.runAutoPass()
        assertThat(lookups()).hasSize(3)
        assertThat(repo.observe(first.id).first { it?.response?.tidy == "done" }).isNotNull()
    }

    @Test fun `an answer for different details than were sent is kept as it comes`() = runBlocking {
        val doc = book("dune_frank_herbert")
        answer("/details/v1/lookup", details(title = "Dune Messiah").replace("\"cached\":false", "\"cached\":true"))

        repo.runAutoPass()

        assertThat(doc.shownWith(shownFor(doc.id)).title).isEqualTo("Dune Messiah")
    }

    @Test fun `a generated cover is fetched from the server and shows where the book has none`() = runBlocking {
        val doc = book()
        answer("/details/v1/lookup", details(cover = """{"source":"generated","url":"$generatedCover","generated":true}"""))
        answer(generatedCover, "png-bytes")

        repo.runAutoPass()

        assertThat(received.map { it.path }).contains(generatedCover)
        val path = doc.shownWith(shownFor(doc.id)).coverPath!!
        assertThat(File(path).readText()).isEqualTo("png-bytes")
    }

    private fun drawnCover(url: String = generatedCover, painting: Boolean = true) =
        details(cover = """{"source":"generated","url":"$url","generated":true,"painting":$painting}""")

    @Test fun `a cover still being painted is asked for again an hour later, and the painted one replaces it`() = runBlocking {
        val doc = book()
        answer("/details/v1/lookup", drawnCover())
        answer(generatedCover, "drawn")

        assertThat(repo.runAutoPass()).isEqualTo(now + 3_600_000L)
        val drawn = File(shownFor(doc.id).coverPath!!)

        now += 3_600_000L
        unmetered.value = false
        assertThat(repo.runAutoPass()).isNull()
        assertThat(lookups()).hasSize(1)

        unmetered.value = true
        val painted = generatedCover.replace("v=7", "v=8")
        answer("/details/v1/lookup", drawnCover(url = painted, painting = false))
        answer(painted, "painted")
        assertThat(repo.runAutoPass()).isNull()

        assertThat(lookups()).hasSize(2)
        // The stored row moves to the new file; the shelf follows a moment later, so the old one stays a while.
        val stored = db.bookDetailsDao().find(doc.id)!!.coverPath!!
        assertThat(File(stored).readText()).isEqualTo("painted")
        waitFor { shownFor(doc.id).coverPath == stored }
        assertThat(drawn.readText()).isEqualTo("drawn")

        now += 31 * 60_000L
        repo.runAutoPass()
        assertThat(drawn.exists()).isFalse()
        assertThat(File(stored).exists()).isTrue()
        now += 24 * 3_600_000L
        repo.runAutoPass()
        assertThat(lookups()).hasSize(2)
    }

    @Test fun `a painting that never arrives is asked about three more times, then the drawn cover stays`() = runBlocking {
        val doc = book()
        repeat(5) { answer("/details/v1/lookup", drawnCover()) }
        answer(generatedCover, "drawn")

        repeat(5) {
            repo.runAutoPass()
            now += 3_600_000L
        }

        assertThat(lookups()).hasSize(4)
        assertThat(File(shownFor(doc.id).coverPath!!).readText()).isEqualTo("drawn")
    }

    @Test fun `asking again for a painting goes first and leaves the day's lookups to new books`() = runBlocking {
        book("first_book")
        answer("/details/v1/lookup", drawnCover())
        answer(generatedCover, "drawn")
        repo.runAutoPass()

        // All but one of the day's lookups spent elsewhere.
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("book_details", Context.MODE_PRIVATE)
        prefs.edit().putInt("auto_lookups", BookDetailsRepository.DAILY_LOOKUPS - 1).apply()
        book("second_book")
        now += 3_600_000L
        answer("/details/v1/lookup", drawnCover(painting = false))
        answer("/details/v1/lookup", details(title = "Second"))

        repo.runAutoPass()

        assertThat(lookups().map { Json.parseToJsonElement(it.body.readUtf8()).jsonObject["book"]!!.jsonObject["title"]!!.jsonPrimitive.content })
            .containsExactly("first_book", "first_book", "second_book").inOrder()
    }

    @Test fun `an Open Library cover comes from Open Library and the archive it redirects to, without the account's login`() = runBlocking {
        val doc = book()
        answer("/details/v1/lookup", details(cover = """{"source":"openlibrary","url":"https://covers.openlibrary.org/b/id/1-L.jpg","generated":false}"""))
        responses.getOrPut("/b/id/1-L.jpg") { ConcurrentLinkedQueue() } +=
            MockResponse().setResponseCode(302).setHeader("Location", "https://ia800500.us.archive.org/view/1-L.jpg")
        responses.getOrPut("/view/1-L.jpg") { ConcurrentLinkedQueue() } +=
            MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(byteArrayOf(1, 2, 3)))

        repo.runAutoPass()

        val first = openLibrary.takeRequest(5, TimeUnit.SECONDS)!!
        assertThat(first.path).isEqualTo("/b/id/1-L.jpg")
        assertThat(first.getHeader("Authorization")).isNull()
        assertThat(openLibrary.takeRequest(5, TimeUnit.SECONDS)!!.path).isEqualTo("/view/1-L.jpg")
        assertThat(File(repo.observe(doc.id).first { it != null }!!.coverPath!!).readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test fun `a cover address off Open Library's hosts, or a redirect away from them, is never fetched`() = runBlocking {
        val plain = book("plain_book")
        val elsewhere = book("elsewhere_book")
        val redirected = book("redirected_book")
        fun cover(url: String) = details(cover = """{"source":"openlibrary","url":"$url","generated":false}""")
        answer("/details/v1/lookup", cover("http://covers.openlibrary.org/b/id/1-L.jpg"))
        answer("/details/v1/lookup", cover("https://covers.example/b/id/2-L.jpg"))
        answer("/details/v1/lookup", cover("https://covers.openlibrary.org/b/id/3-L.jpg"))
        responses.getOrPut("/b/id/3-L.jpg") { ConcurrentLinkedQueue() } +=
            MockResponse().setResponseCode(302).setHeader("Location", "https://tracker.example/3-L.jpg")

        repo.runAutoPass()

        assertThat(openLibrary.takeRequest(5, TimeUnit.SECONDS)!!.path).isEqualTo("/b/id/3-L.jpg")
        assertThat(openLibrary.takeRequest(500, TimeUnit.MILLISECONDS)).isNull()
        for (doc in listOf(plain, elsewhere, redirected)) {
            assertThat(repo.observe(doc.id).first { it != null }!!.coverPath).isNull()
        }
    }

    @Test fun `a book with its own cover never gets another`() = runBlocking {
        val cover = File(dir, "own.jpg").apply { writeText("own") }
        val doc = book(cover = cover.path, url = "https://catalog.example/book.epub")
        answer("/details/v1/lookup", details(cover = """{"source":"generated","url":"$generatedCover","generated":true}"""))

        repo.runAutoPass()

        val sent = Json.parseToJsonElement(lookups().single().body.readUtf8()).jsonObject
        assertThat(sent["source"]!!.jsonPrimitive.content).isEqualTo("catalog")
        assertThat(sent["cover"]!!.jsonPrimitive.content).isEqualTo("catalog")
        assertThat(received.map { it.path }).doesNotContain(generatedCover)
        assertThat(shownFor(doc.id).coverPath).isNull()
        assertThat(doc.shownWith(shownFor(doc.id)).coverPath).isEqualTo(cover.path)
    }

    @Test fun `an applied tidy wins over a background lookup still under way`() = runBlocking {
        val doc = book()
        holdLookup = CountDownLatch(1)
        answer("/details/v1/lookup", details(title = "From the background"))
        answer("/details/v1/tidy", details(title = "From the tidy"))

        val pass = async(Dispatchers.IO) { repo.runAutoPass() }
        while (lookups().isEmpty()) Thread.yield()
        val proposal = repo.propose(doc.id)
        repo.apply(proposal, DETAILS_FIELDS, COVER_KEEP, repo.coverPreview(proposal, COVER_KEEP))
        holdLookup!!.countDown()
        pass.await()

        assertThat(db.bookDetailsDao().find(doc.id)!!.responseJson).contains("From the tidy")
    }

    @Test fun `a lookup begun before a preview opened cannot bring back what the reader rejected`() = runBlocking {
        val doc = book()
        holdLookup = CountDownLatch(1)
        answer("/details/v1/lookup", details(title = "From the background"))
        answer("/details/v1/tidy", details(title = "From the tidy"))

        val pass = async(Dispatchers.IO) { repo.runAutoPass() }
        while (lookups().isEmpty()) Thread.yield()
        val proposal = repo.propose(doc.id)
        holdLookup!!.countDown()
        pass.await()
        assertThat(db.bookDetailsDao().find(doc.id)!!.responseJson).doesNotContain("From the background")

        repo.close(proposal)
        val stored = repo.observe(doc.id).first { it != null }!!
        assertThat(stored.response.declined).isEqualTo("k1")
        assertThat(doc.shownWith(stored).title).isEqualTo("dune_frank_herbert")
    }

    @Test fun `a rejected record served again by a later lookup shows none of its changes`() = runBlocking {
        val doc = book()
        answer("/details/v1/tidy", details(title = "Dune"))
        repo.close(repo.propose(doc.id))

        // The tidy moved the reader's pointer: the lookup serves the same record, with its cover.
        answer("/details/v1/lookup", details(title = "Dune", cover = """{"source":"generated","url":"$generatedCover","generated":true}"""))
        answer(generatedCover, "drawn")
        repo.runAutoPass()

        assertThat(lookups()).hasSize(1)
        assertThat(db.bookDetailsDao().find(doc.id)!!.responseJson).contains("\"declined\":\"k1\"")
        val shown = doc.shownWith(shownFor(doc.id) { it.coverPath != null })
        assertThat(shown.title).isEqualTo("dune_frank_herbert")
        assertThat(shown.author).isNull()
        assertThat(File(shown.coverPath!!).readText()).isEqualTo("drawn")
    }

    @Test fun `applying keeps only the ticked fields, and a field the catalog changed since shows the catalog's`() = runBlocking {
        val doc = book(author = "herbert")
        answer("/details/v1/tidy", details(title = "Dune"))

        val proposal = repo.propose(doc.id)
        val applied = repo.apply(proposal, listOf(FIELD_AUTHORS, FIELD_SERIES, FIELD_DESCRIPTION), COVER_KEEP, null)!!

        assertThat(doc.shownWith(applied).title).isEqualTo("dune_frank_herbert")
        assertThat(doc.shownWith(applied).author).isEqualTo("Frank Herbert")
        assertThat(doc.copy(author = "F. Herbert").shownWith(applied).author).isEqualTo("F. Herbert")
        assertThat(doc.shownWith(shownFor(doc.id, ::applied)).author).isEqualTo("Frank Herbert")
    }

    /** The automatic pass stores "Dune" by Frank Herbert under [key] for [doc], and it shows. */
    private suspend fun foundByThePass(doc: Document, key: String = "k1") {
        answer("/details/v1/lookup", details(key = key))
        repo.runAutoPass()
        assertThat(doc.shownWith(shownFor(doc.id) { it.response.key == key }).title).isEqualTo("Dune")
    }

    @Test fun `cancelling a tidy that serves the record already shown keeps what the book shows`() = runBlocking {
        val doc = book()
        foundByThePass(doc)
        answer("/details/v1/tidy", details())

        repo.close(repo.propose(doc.id))

        val stored = db.bookDetailsDao().find(doc.id)!!
        assertThat(stored.responseJson).contains("\"declined\":\"k1\"")
        val shown = doc.shownWith(shownFor(doc.id) { it.response.declined == "k1" })
        assertThat(shown.title).isEqualTo("Dune")
        assertThat(shown.author).isEqualTo("Frank Herbert")

        // The same record served again by the pass still shows.
        now += 3_600_000L
        db.bookDetailsDao().save(stored.copy(retryAt = now))
        answer("/details/v1/lookup", details())
        repo.runAutoPass()
        assertThat(doc.shownWith(shownFor(doc.id)).title).isEqualTo("Dune")
    }

    @Test fun `a tidy with nothing to change puts the book's answer back as it was, declining nothing`() = runBlocking {
        val doc = book()
        foundByThePass(doc)
        val before = db.bookDetailsDao().find(doc.id)
        answer("/details/v1/tidy", details())

        repo.dismiss(repo.propose(doc.id))

        assertThat(db.bookDetailsDao().find(doc.id)).isEqualTo(before)

        // A book with nothing stored keeps nothing.
        val other = book(title = "other_book")
        answer("/details/v1/tidy", details(title = "other_book", key = "k2"))
        repo.dismiss(repo.propose(other.id))
        assertThat(db.bookDetailsDao().find(other.id)).isNull()
    }

    @Test fun `a rejected record served later keeps the details the book showed, with the record's cover`() = runBlocking {
        val doc = book()
        foundByThePass(doc)
        answer("/details/v1/tidy", details(title = "Dune Messiah", key = "k2"))
        repo.close(repo.propose(doc.id))

        val stored = db.bookDetailsDao().find(doc.id)!!
        now += 3_600_000L
        db.bookDetailsDao().save(stored.copy(retryAt = now))
        answer("/details/v1/lookup", details(title = "Dune Messiah", key = "k2", cover = """{"source":"generated","url":"$generatedCover","generated":true}"""))
        answer(generatedCover, "drawn")
        repo.runAutoPass()

        val shown = doc.shownWith(shownFor(doc.id) { it.coverPath != null })
        assertThat(shown.title).isEqualTo("Dune")
        assertThat(shown.author).isEqualTo("Frank Herbert")
        assertThat(File(shown.coverPath!!).readText()).isEqualTo("drawn")
    }

    @Test fun `a preview the app never ended counts as rejected, and the book keeps what it showed`() = runBlocking {
        val doc = book()
        foundByThePass(doc)
        val fresh = book(title = "fresh_book")
        answer("/details/v1/tidy", details(title = "Dune Messiah", key = "k2"))
        answer("/details/v1/tidy", details(title = "Fresh Book", key = "k3"))
        repo.propose(doc.id)
        repo.propose(fresh.id)

        // The process dies with both previews open; the next one finds the records served again.
        repo = newRepository()
        now += 3_600_000L
        answer("/details/v1/lookup", details(title = "Fresh Book", key = "k3"))
        repo.runAutoPass()

        assertThat(doc.shownWith(shownFor(doc.id)).title).isEqualTo("Dune")
        assertThat(lookups().last().body.clone().readUtf8()).contains("fresh_book")
        assertThat(fresh.shownWith(shownFor(fresh.id) { it.response.declined == "k3" }).title).isEqualTo("fresh_book")
    }

    @Test fun `a rejected tidy of a book with nothing stored keeps the book's own details, as an older app reads them`() = runBlocking {
        val doc = book()
        answer("/details/v1/tidy", details(title = "Dune"))
        repo.close(repo.propose(doc.id))

        // Read as the released app read it, knowing nothing of declined records or kept fields.
        val released = Json { ignoreUnknownKeys = true }
        val raw = db.bookDetailsDao().find(doc.id)!!.responseJson!!
        val book = released.parseToJsonElement(raw).jsonObject["book"]!!.jsonObject
        assertThat(book["title"]!!.jsonPrimitive.content).isEqualTo("dune_frank_herbert")
        assertThat(book["authors"].toString()).isEqualTo("[]")

        // A row as the released app wrote it still shows all of its answer.
        val other = book(title = "other_book")
        db.bookDetailsDao().save(
            BookDetailsEntity(
                other.id,
                """{"key":"k9","book":{"title":"Other Book","authors":["A. Writer"]},"origins":{"title":"ai"},""" +
                    """"cover":{"source":null,"url":null,"generated":false},"tidy":"done","painting_checks":0}""",
                null, useOriginal = false, retryAt = null,
            ),
        )
        val shown = other.shownWith(shownFor(other.id))
        assertThat(shown.title).isEqualTo("Other Book")
        assertThat(shown.author).isEqualTo("A. Writer")
    }

    @Test fun `an Apply the screen stops waiting for still keeps the reader's choice`() = runBlocking {
        offerCoverChoice()
        val doc = book()
        answer("/details/v1/tidy", drawnCover(painting = false))
        answer(generatedCover, "drawn")
        answer("/details/v1/lookup", drawnCover(painting = false))
        val proposal = repo.propose(doc.id)
        val preview = repo.coverPreview(proposal, COVER_PAINT)
        answer("/details/v1/lookup", drawnCover(painting = true))
        holdLookup = CountDownLatch(1)

        val screen = launch(Dispatchers.IO) { repo.apply(proposal, DETAILS_FIELDS, COVER_PAINT, preview) }
        while (lookups().size < 2) Thread.yield()
        screen.cancel()
        repo.close(proposal)
        holdLookup!!.countDown()

        val stored = shownFor(doc.id) { it.response.coverChoice == COVER_PAINT }
        assertThat(stored.response.declined).isNull()
        assertThat(doc.shownWith(stored).title).isEqualTo("Dune")
    }

    @Test fun `Use original chosen while Apply runs is not undone by it`() = runBlocking {
        offerCoverChoice()
        val doc = book()
        answer("/details/v1/tidy", drawnCover(painting = false))
        answer(generatedCover, "drawn")
        answer("/details/v1/lookup", drawnCover(painting = false))
        val proposal = repo.propose(doc.id)
        val preview = repo.coverPreview(proposal, COVER_PAINT)
        answer("/details/v1/lookup", drawnCover(painting = true))
        holdLookup = CountDownLatch(1)

        // Apply waits on the painting while the reader switches to the original.
        val applying = async(Dispatchers.IO) { repo.apply(proposal, DETAILS_FIELDS, COVER_PAINT, preview) }
        while (lookups().size < 2) Thread.yield()
        repo.setUseOriginal(doc.id, true)
        holdLookup!!.countDown()

        assertThat(withTimeout(5_000) { applying.await() }!!.useOriginal).isTrue()
        val stored = withTimeout(5_000) { repo.observe(doc.id).first { it != null && applied(it) } }!!
        assertThat(stored.useOriginal).isTrue()
    }

    @Test fun `a server that can no longer paint when Apply asks keeps the drawn cover`() = runBlocking {
        offerCoverChoice()
        val doc = book()
        answer("/details/v1/tidy", drawnCover(painting = false))
        answer(generatedCover, "drawn")
        answer("/details/v1/lookup", drawnCover(painting = false))
        val proposal = repo.propose(doc.id)
        val preview = repo.coverPreview(proposal, COVER_PAINT)
        answer("/details/v1/lookup", """{"detail":"cover_art_unavailable"}""", code = 409)

        val applied = repo.apply(proposal, DETAILS_FIELDS, COVER_PAINT, preview)!!

        assertThat(applied.response.coverChoice).isEqualTo(COVER_DRAW)
        assertThat(applied.response.cover.painting).isFalse()
        assertThat(File(applied.coverPath!!).readText()).isEqualTo("drawn")
        assertThat(db.bookDetailsDao().find(doc.id)!!.retryAt).isNull()
    }

    private fun offerCoverChoice(painting: Boolean = true) = runBlocking {
        answer("/ai/v1/config", """{"configured":true,"details_supported":true,"cover_choice_supported":true,"cover_painting_supported":$painting}""")
        answer("/ai/v1/preferences", """{"ai_enabled":true,"style":{"tone":"neutral","language":"auto"}}""")
        ai.refresh()
        received.clear()
    }

    private fun sentChoice(request: RecordedRequest) =
        Json.parseToJsonElement(request.body.clone().readUtf8()).jsonObject["cover_choice"]?.jsonPrimitive?.content

    @Test fun `a painted cover replaces the book's own, is asked for only on apply, and checked again in an hour`() = runBlocking {
        offerCoverChoice()
        val own = File(dir, "own.jpg").apply { writeText("own") }
        val doc = book(title = "Dune", author = "Frank Herbert", cover = own.path)
        answer("/details/v1/tidy", details(cover = """{"source":"catalog","url":null,"generated":false}"""))
        answer("/details/v1/lookup", drawnCover(painting = false))
        answer(generatedCover, "drawn")
        answer("/details/v1/lookup", drawnCover(painting = true))

        val proposal = repo.propose(doc.id)
        assertThat(sentChoice(received.first { it.path == "/details/v1/tidy" })).isNull()
        val preview = repo.coverPreview(proposal, COVER_PAINT)
        assertThat(sentChoice(lookups().single())).isEqualTo(COVER_DRAW)
        val applied = repo.apply(proposal, DETAILS_FIELDS, COVER_PAINT, preview)!!

        assertThat(lookups().map(::sentChoice)).containsExactly(COVER_DRAW, COVER_PAINT).inOrder()
        assertThat(applied.response.cover.painting).isTrue()
        assertThat(File(doc.shownWith(shownFor(doc.id, ::applied)).coverPath!!).readText()).isEqualTo("drawn")

        val painted = generatedCover.replace("v=7", "v=8")
        answer("/details/v1/lookup", drawnCover(url = painted, painting = false))
        answer(painted, "painted")
        now += 3_600_000L
        repo.runAutoPass()

        assertThat(sentChoice(lookups().last())).isEqualTo(COVER_PAINT)
        waitFor { File(doc.shownWith(shownFor(doc.id)).coverPath!!).readText() == "painted" }

        // Back to the original shows the book's own cover again.
        repo.setUseOriginal(doc.id, true)
        withTimeout(5_000) { repo.shown.first { doc.id !in it } }
        assertThat(doc.shownWith(repo.shown.value[doc.id]).coverPath).isEqualTo(own.path)
    }

    @Test fun `a server that stopped painting keeps the drawn cover and is asked for it from then on`() = runBlocking {
        offerCoverChoice()
        val doc = book()
        answer("/details/v1/tidy", drawnCover(painting = false))
        answer(generatedCover, "drawn")
        answer("/details/v1/lookup", drawnCover(painting = false))
        answer("/details/v1/lookup", drawnCover(painting = true))
        val proposal = repo.propose(doc.id)
        repo.apply(proposal, DETAILS_FIELDS, COVER_PAINT, repo.coverPreview(proposal, COVER_PAINT))

        answer("/details/v1/lookup", """{"detail":"cover_art_unavailable"}""", code = 409)
        now += 3_600_000L
        repo.runAutoPass()

        val stored = shownFor(doc.id) { it.response.coverChoice != COVER_PAINT }
        assertThat(stored.response.coverChoice).isEqualTo(COVER_DRAW)
        assertThat(File(doc.shownWith(stored).coverPath!!).readText()).isEqualTo("drawn")
        assertThat(db.bookDetailsDao().find(doc.id)!!.retryAt).isNull()
    }

    @Test fun `a server that takes no cover choice is never sent one`() = runBlocking {
        val doc = book()
        answer("/details/v1/tidy", drawnCover(painting = false))
        answer(generatedCover, "drawn")

        val proposal = repo.propose(doc.id)
        repo.apply(proposal, DETAILS_FIELDS, COVER_KEEP, repo.coverPreview(proposal, COVER_KEEP))

        val body = received.first { it.path == "/details/v1/tidy" }.body.readUtf8()
        assertThat(body).doesNotContain("cover_choice")
        assertThat(File(doc.shownWith(shownFor(doc.id, ::applied)).coverPath!!).readText()).isEqualTo("drawn")
    }

    @Test fun `a cover preview that fails leaves the cover as it is and the text changes still apply`() = runBlocking {
        offerCoverChoice()
        val own = File(dir, "own.jpg").apply { writeText("own") }
        val doc = book(cover = own.path)
        answer("/details/v1/tidy", details(title = "Dune"))
        answer("/details/v1/lookup", drawnCover(painting = false))
        // The drawn cover's image is not served: its download fails.

        val proposal = repo.propose(doc.id)
        runCatching { repo.coverPreview(proposal, COVER_DRAW) }.also { assertThat(it.isFailure).isTrue() }
        val applied = repo.apply(proposal, DETAILS_FIELDS, COVER_KEEP, null)!!

        assertThat(doc.shownWith(applied).title).isEqualTo("Dune")
        assertThat(doc.shownWith(applied).coverPath).isEqualTo(own.path)
    }

    @Test fun `use original hides the found details from the shelf and is remembered`() = runBlocking {
        val doc = book()
        answer("/details/v1/lookup", details())
        repo.runAutoPass()

        shownFor(doc.id)

        repo.setUseOriginal(doc.id, true)

        withTimeout(5_000) { repo.shown.first { it.isEmpty() } }
        assertThat(repo.observe(doc.id).first { it?.useOriginal == true }).isNotNull()
    }

    @Test fun `with the setting off nothing is sent and nothing shows`() = runBlocking {
        val doc = book()
        answer("/details/v1/lookup", details())
        repo.runAutoPass()
        shownFor(doc.id)

        showTidied.value = false
        book("another_book")
        repo.runAutoPass()

        assertThat(lookups()).hasSize(1)
        withTimeout(5_000) { repo.shown.first { it.isEmpty() } }
        assertThat(repo.observe(doc.id).first()).isNull()
        assertThat(repo.available.first()).isFalse()
        assertThat(repo.hasStored.first()).isTrue()
    }

    @Test fun `deleting a book drops its details`() = runBlocking {
        val doc = book()
        answer("/details/v1/lookup", details())
        repo.runAutoPass()

        documents.delete(doc)

        assertThat(db.bookDetailsDao().all()).isEmpty()
    }

    @Test fun `turning the setting off stops a pass under way`() = runBlocking {
        book("first_book")
        book("second_book")
        holdLookup = CountDownLatch(1)
        answer("/details/v1/lookup", details(title = "First"))
        answer("/details/v1/lookup", details(title = "Second"))

        val follow = launch(Dispatchers.IO) { repo.followLibrary() }
        waitFor { lookups().isNotEmpty() }
        showTidied.value = false
        delay(300)
        holdLookup!!.countDown()
        delay(500)

        assertThat(lookups()).hasSize(1)
        assertThat(db.bookDetailsDao().all()).isEmpty()
        follow.cancelAndJoin()
    }

    @Test fun `after a pause the pass runs again on its own`() = runBlocking {
        val doc = book()
        answer("/details/v1/lookup", """{"detail":"details_rate_limited"}""", code = 429, retryAfter = "120")
        answer("/details/v1/lookup", details())

        val follow = launch(Dispatchers.IO) { repo.followLibrary() }

        assertThat(withTimeout(5_000) { waits.receive() }).isEqualTo(120_000)
        assertThat(shownFor(doc.id).response.book.title).isEqualTo("Dune")
        assertThat(lookups()).hasSize(2)
        follow.cancelAndJoin()
    }

    @Test fun `the automatic pass makes at most 25 lookups a day`() = runBlocking {
        repeat(BookDetailsRepository.DAILY_LOOKUPS + 1) { n ->
            book("book_$n")
            answer("/details/v1/lookup", details(title = "Book $n"))
        }

        assertThat(repo.runAutoPass()).isEqualTo(startOfTomorrow())
        assertThat(lookups()).hasSize(BookDetailsRepository.DAILY_LOOKUPS)

        repo = newRepository()
        repo.runAutoPass()
        assertThat(lookups()).hasSize(BookDetailsRepository.DAILY_LOOKUPS)

        now = startOfTomorrow() + 1
        repo.runAutoPass()
        assertThat(lookups()).hasSize(BookDetailsRepository.DAILY_LOOKUPS + 1)
    }

    @Test fun `the automatic pass waits for an unmetered network, an Improve details does not`() = runBlocking {
        val doc = book()
        unmetered.value = false
        answer("/details/v1/tidy", details())

        assertThat(repo.runAutoPass()).isNull()
        assertThat(lookups()).isEmpty()
        assertThat(repo.propose(doc.id).answer.book.title).isEqualTo("Dune")
    }

    @Test fun `another account or server drops the stored details and their covers`() = runBlocking {
        val doc = book()
        answer("/details/v1/lookup", details(cover = """{"source":"generated","url":"$generatedCover","generated":true}"""))
        answer(generatedCover, "png-bytes")
        val follow = launch(Dispatchers.IO) { repo.followLibrary() }
        val cover = File(shownFor(doc.id).coverPath!!)
        assertThat(cover.isFile).isTrue()

        owner.value = "https://other.example reader"

        // The rows go first, then their covers.
        waitFor { db.bookDetailsDao().all().isEmpty() && !cover.exists() }
        follow.cancelAndJoin()
    }

    @Test fun `found details stop showing when the reader opts out of AI, and stay while it is unknown`() = runBlocking {
        val doc = book()
        answer("/details/v1/lookup", details())
        repo.runAutoPass()
        shownFor(doc.id)

        answer("/ai/v1/config", """{"configured":true,"details_supported":true}""")
        answer("/ai/v1/preferences", """{"ai_enabled":false,"style":{"tone":"neutral","language":"auto"}}""")
        ai.refresh()

        withTimeout(5_000) { repo.shown.first { it.isEmpty() } }
        assertThat(repo.observe(doc.id).first()).isNull()
        assertThat(repo.hasStored.first { it }).isTrue()
    }

    @Test fun `the request carries the library's details and the file's, cut to the server's limits`() {
        val doc = Document(
            id = 1, identity = DocumentIdentity(metadataId = "m", contentHash = "h"), title = " Dune ",
            author = "Frank Herbert", downloadUrl = "https://catalog.example/1.epub", localPath = "/nowhere",
            coverPath = null, downloadedAt = 0, seriesName = "Dune", seriesIndex = 1.0,
        )
        val opf = io.theficos.ereader.core.metadata.MetadataBundle(
            title = "Dune", author = "Someone Else", description = "x".repeat(9000), publishDate = "1965-08-01",
            subjects = listOf("Science fiction", "Science fiction", " "), language = "en",
        )

        val body = detailsRequest(doc, opf)

        assertThat(body.identity).isEqualTo(DocumentIdentity(metadataId = "m", contentHash = "h"))
        assertThat(body.book.title).isEqualTo("Dune")
        assertThat(body.book.authors).containsExactly("Frank Herbert")
        assertThat(body.book.seriesName).isEqualTo("Dune")
        assertThat(body.book.description).hasLength(8192)
        assertThat(body.book.year).isEqualTo(1965)
        assertThat(body.book.subjects).containsExactly("Science fiction")
        assertThat(body.source).isEqualTo("catalog")
        assertThat(body.cover).isNull()
    }

    @Test fun `titles that look like file names`() {
        listOf(
            "dune_frank_herbert", "Dune.epub", "Dune (retail)", "Dune [epub]", "Herbert-Dune-1965",
            "dune frank herbert",
        ).forEach { assertWithMessage(it).that(looksLikeFileName(it)).isTrue() }
        listOf("Dune", "The Lord of the Rings", "Dune Messiah", "1984", "Les Misérables", "百年の孤独")
            .forEach { assertWithMessage(it).that(looksLikeFileName(it)).isFalse() }
    }

    @Test fun `a book needs details when it lacks a cover, an author or a description`() {
        val doc = Document(1, DocumentIdentity(contentHash = "h"), "Dune", "Frank Herbert", "u", "/p", "/cover", 0)
        val book = DetailsBook(title = "Dune", authors = listOf("Frank Herbert"), description = "A desert planet.")
        assertThat(needsDetails(doc, book)).isFalse()
        assertThat(needsDetails(doc.copy(coverPath = null), book)).isTrue()
        assertThat(needsDetails(doc, book.copy(authors = emptyList()))).isTrue()
        assertThat(needsDetails(doc, book.copy(description = null))).isTrue()
        assertThat(needsDetails(doc, book.copy(title = "dune_frank_herbert"))).isTrue()
    }

    private val generatedCover = "/details/v1/covers/${"a".repeat(64)}?v=7"

    private fun writeEpub(file: File, title: String, author: String, description: String) {
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write(
                """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray(),
            )
            zip.putNextEntry(ZipEntry("content.opf"))
            zip.write(
                """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>$title</dc:title>
                  <dc:creator>$author</dc:creator><dc:description>$description</dc:description></metadata></package>""".toByteArray(),
            )
        }
    }
}
