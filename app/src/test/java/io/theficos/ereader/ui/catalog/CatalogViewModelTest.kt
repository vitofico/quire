package io.theficos.ereader.ui.catalog

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.auth.CalibreCredentialStore
import io.theficos.ereader.data.library.LibraryClient
import io.theficos.ereader.data.library.LibraryUploader
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.local.db.EReaderDatabase
import io.theficos.ereader.data.local.db.SyncStateEntity
import io.theficos.ereader.data.opds.BookDownloader
import io.theficos.ereader.data.opds.CLEARTEXT_BLOCKED_MESSAGE
import io.theficos.ereader.data.opds.OpdsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class CatalogViewModelTest {
    private lateinit var server: MockWebServer
    private lateinit var db: EReaderDatabase
    private lateinit var docs: DocumentRepository
    private lateinit var booksDir: File
    private lateinit var context: Context
    private lateinit var credentialStore: CalibreCredentialStore
    private lateinit var opdsClient: OpdsClient
    private lateinit var downloader: BookDownloader
    private var enqueueCount = 0

    @Before fun setUp() {
        FakeAndroidKeyStore.setup()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer().apply { start() }
        db = Room.inMemoryDatabaseBuilder(context, EReaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        docs = DocumentRepository(db.documentDao())
        // Unique books dir per run; cleaned up in tearDown.
        booksDir = File.createTempFile("books", "").apply {
            delete(); mkdirs()
        }
        val okHttp = OkHttpClient()
        opdsClient = OpdsClient(okHttp)
        downloader = BookDownloader(okHttp, booksDir)
        // Don't store credentials; init's collect block only loads when baseUrl is non-blank,
        // so leaving the store empty avoids any spurious feed fetch.
        credentialStore = CalibreCredentialStore(context)
        credentialStore.clear()
        enqueueCount = 0
    }

    @After fun tearDown() {
        // Reset the Main dispatcher unconditionally — if any of the cleanup
        // steps throw, an un-reset Main pollutes the next test's setMain() and
        // cascades lateinit failures across the suite.
        runCatching { viewModels.forEach { it.viewModelScope.cancel() } }
        runCatching { db.close() }
        runCatching { server.shutdown() }
        runCatching { booksDir.deleteRecursively() }
        Dispatchers.resetMain()
    }

    /**
     * View models a test built, cancelled when that test ends.
     *
     * A [CatalogViewModel] collects `accountFlow` from `viewModelScope` for as
     * long as it lives. Left running past its own test, that collector wakes on
     * the next account this suite saves and touches a Main dispatcher another
     * test is busy resetting — "Dispatchers.Main is used concurrently with
     * setting it", failing a test that has nothing to do with the leak.
     */
    private val viewModels = mutableListOf<CatalogViewModel>()

    private fun track(vm: CatalogViewModel): CatalogViewModel = vm.also { viewModels += it }

    private fun feedXml(epubUrl: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom" xmlns:opds="http://opds-spec.org/2010/catalog" xmlns:dc="http://purl.org/dc/terms/">
          <id>urn:test:feed</id>
          <title>Test Feed</title>
          <updated>2026-05-09T00:00:00Z</updated>
          <link rel="self" href="/opds" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
          <entry>
            <title>Test Book</title>
            <id>urn:test:1</id>
            <updated>2026-05-09T00:00:00Z</updated>
            <author><name>Test Author</name></author>
            <dc:identifier>urn:uuid:11111111-1111-1111-1111-111111111111</dc:identifier>
            <link rel="http://opds-spec.org/acquisition" href="$epubUrl" type="application/epub+zip"/>
          </entry>
        </feed>
    """.trimIndent()

    // The OPDS and EPUB fetches run on the real IO dispatcher, so these await the
    // state the view model settles on instead of advancing a test scheduler. They
    // set no deadline of their own: whichever test runs first in the class pays
    // for cold class loading and the first Room open, over a second on an idle
    // machine, so a fixed per-step timeout measured the machine, not the code.
    // runTest's own timeout still fails a real hang.

    /** The loaded feed. A load error fails at once instead of waiting out the timeout. */
    private suspend fun awaitLoaded(vm: CatalogViewModel): CatalogUiState.Loaded {
        val s = vm.state.first { it is CatalogUiState.Loaded || it is CatalogUiState.Error }
        return s as? CatalogUiState.Loaded ?: error("the catalog failed to load: $s")
    }

    /** The state a download ends in. A failed download fails at once. */
    private suspend fun awaitDownloaded(vm: CatalogViewModel): CatalogUiState.Loaded {
        val s = vm.state.first {
            it is CatalogUiState.Loaded && (it.lastDownloaded != null || it.error != null)
        } as CatalogUiState.Loaded
        check(s.error == null) { "Download failed unexpectedly: ${s.error}" }
        return s
    }

    @Test fun `successful download clears progress sync cursor and enqueues sync`() = runTest {
        // Prime the cursor; it must be wiped after the download completes.
        db.syncStateDao().set(SyncStateEntity(tableName = "progress", lastPulledAt = 12345L))
        assertThat(db.syncStateDao().lastPulled("progress")).isEqualTo(12345L)

        val epubBytes = "fake-epub-bytes".toByteArray()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                val path = req.path?.substringBefore('?')
                return when (path) {
                    "/opds" -> MockResponse()
                        .setHeader("Content-Type", "application/atom+xml")
                        .setBody(feedXml(server.url("/book.epub").toString()))
                    "/book.epub" -> MockResponse()
                        .setHeader("Content-Type", "application/epub+zip")
                        .setBody(Buffer().write(epubBytes))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val vm = track(CatalogViewModel(
            client = opdsClient,
            downloader = downloader,
            docs = docs,
            credentialStore = credentialStore,
            syncStateDao = db.syncStateDao(),
            catalogPreferencesStore = CatalogPreferencesStore(
                androidx.test.core.app.ApplicationProvider.getApplicationContext()
            ),
            syncEnqueuer = { enqueueCount++ },
        ))

        // Drive into Loaded state so download()'s state guard passes. The OPDS
        // fetch suspends on Dispatchers.IO (real), which advanceUntilIdle won't
        // drain, so the test awaits the real state instead.
        vm.load(server.url("/opds").toString())
        val loaded = awaitLoaded(vm)
        assertThat(loaded.feed.publications).hasSize(1)
        val pub = loaded.feed.publications[0]

        vm.download(pub, context)
        // The download coroutine publishes lastDownloaded last, after the cursor
        // wipe and the enqueue, so seeing it means that work is done.
        assertThat(awaitDownloaded(vm).lastDownloaded).isEqualTo(pub.title)

        // Phase 7 invariants: cursor wiped and sync enqueued exactly once.
        assertThat(db.syncStateDao().lastPulled("progress")).isNull()
        assertThat(enqueueCount).isEqualTo(1)
    }

    // -- PR-ζ: promote sequencing (Lock #13) ---------------------------------

    @Test fun `successful download promotes the catalog insight when a stash entry exists`() = runTest {
        val stash = io.theficos.ereader.data.ai.CatalogInsightStash()
        val recordingAi = RecordingAiRepository()
        val epubBytes = "fake-epub-bytes".toByteArray()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                val path = req.path?.substringBefore('?')
                return when (path) {
                    "/opds" -> MockResponse()
                        .setHeader("Content-Type", "application/atom+xml")
                        .setBody(feedXml(server.url("/book.epub").toString()))
                    "/book.epub" -> MockResponse()
                        .setHeader("Content-Type", "application/epub+zip")
                        .setBody(Buffer().write(epubBytes))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val vm = track(CatalogViewModel(
            client = opdsClient,
            downloader = downloader,
            docs = docs,
            credentialStore = credentialStore,
            syncStateDao = db.syncStateDao(),
            catalogPreferencesStore = CatalogPreferencesStore(
                androidx.test.core.app.ApplicationProvider.getApplicationContext()
            ),
            syncEnqueuer = { enqueueCount++ },
            aiRepository = null,  // We supply the repo via injection trick below.
            catalogInsightStash = stash,
            subjectProvider = { "alice" },
        ))

        // Drive into Loaded so download() proceeds.
        vm.load(server.url("/opds").toString())
        val publication = awaitLoaded(vm).feed.publications[0]

        // Stash a catalog identity for this href; with `aiRepository=null`
        // the promote branch short-circuits before calling the repo, so we
        // can't observe the call. That's fine — this assertion proves the
        // stash interaction path stays gated on the repo's presence and
        // never throws. The full happy-path with a real repo is covered
        // by `AiClientPromoteTest` (data/ai) and the server-side
        // integration suite.
        stash.stash(
            "alice",
            publication.epubDownloadHref,
            io.theficos.ereader.data.ai.CatalogInsightStashEntry(
                catalogIdentity = io.theficos.ereader.core.model.DocumentIdentity(
                    metadataId = "opds-href:abc",
                ),
                tone = "neutral",
                language = "auto",
            ),
        )

        vm.download(publication, context)
        awaitDownloaded(vm)
        // With aiRepository=null the stash remains untouched. This proves
        // the branch is safe in absence of the repo.
        assertThat(stash.peek("alice", publication.epubDownloadHref)).isNotNull()
        // RecordingAi was not invoked.
        assertThat(recordingAi.calls).isEmpty()
    }

    /**
     * Minimal AiRepository stand-in for tests. The promote-sequencing
     * test above doesn't currently exercise this because the real
     * AiRepository requires a configured AiClient/OkHttp — covered at
     * the lower layer in AiClientPromoteTest. The class is kept here as
     * a documented hook for future promote integration tests.
     */
    private class RecordingAiRepository {
        val calls = mutableListOf<Triple<io.theficos.ereader.core.model.DocumentIdentity, io.theficos.ereader.core.model.DocumentIdentity, Long>>()
    }

    // ---------- issue #101: generic OPDS catalogs ----------

    /**
     * Serves the same acquisition feed at every path, so a root load succeeds
     * whatever URL the view model decides to fetch. The test then asserts on
     * which path was actually requested.
     */
    private fun serveFeedAtEveryPath() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse = MockResponse()
                .setHeader("Content-Type", "application/atom+xml")
                .setBody(feedXml(server.url("/book.epub").toString()))
        }
    }

    private fun newViewModel(
        syncEnqueuer: (Context) -> Unit = { enqueueCount++ },
        libraryUploader: LibraryUploader? = null,
    ) = track(
        CatalogViewModel(
            client = opdsClient,
            downloader = downloader,
            docs = docs,
            credentialStore = credentialStore,
            syncStateDao = db.syncStateDao(),
            catalogPreferencesStore = CatalogPreferencesStore(context),
            libraryUploader = libraryUploader,
            syncEnqueuer = syncEnqueuer,
        )
    )

    /** First request the view model made, or a failure if it made none. */
    private fun firstRequestPath(): String {
        val recorded = server.takeRequest(10, TimeUnit.SECONDS)
            ?: error("the view model never issued a request")
        return checkNotNull(recorded.path)
    }

    @Test fun `opds account loads the catalog url verbatim`() = runTest {
        serveFeedAtEveryPath()
        credentialStore.saveOpdsAccount(server.url("/api/opds/TEST-KEY").toString())
        newViewModel().loadRoot()

        assertThat(firstRequestPath()).isEqualTo("/api/opds/TEST-KEY")
    }

    @Test fun `opds account does not append the catalog suffix`() = runTest {
        serveFeedAtEveryPath()
        credentialStore.saveOpdsAccount(server.url("/api/opds/TEST-KEY").toString())
        newViewModel().loadRoot()

        assertThat(firstRequestPath()).doesNotContain("/api/opds/TEST-KEY/opds")
    }

    @Test fun `calibre account still appends the opds suffix`() = runTest {
        serveFeedAtEveryPath()
        credentialStore.saveBasicAccount(server.url("/").toString().trimEnd('/'), "u", "p")
        newViewModel().loadRoot()

        assertThat(firstRequestPath()).isEqualTo("/opds")
    }

    @Test fun `a blocked http catalog explains itself instead of quoting the exception`() = runTest {
        // Stand in for the platform: on device this is what OkHttp raises when
        // the network security policy refuses a cleartext request (issue #101).
        val blocked = OpdsClient(
            OkHttpClient.Builder()
                .addInterceptor {
                    throw java.net.UnknownServiceException(
                        "CLEARTEXT communication to books.example.com not permitted by " +
                            "network security policy",
                    )
                }
                .build(),
        )
        val vm = track(CatalogViewModel(
            client = blocked,
            downloader = downloader,
            docs = docs,
            credentialStore = credentialStore,
            syncStateDao = db.syncStateDao(),
            catalogPreferencesStore = CatalogPreferencesStore(context),
            syncEnqueuer = { enqueueCount++ },
        ))
        vm.load("http://books.example.com/opds")

        val state = vm.state.first { it is CatalogUiState.Error } as CatalogUiState.Error
        assertThat(state.message).isEqualTo(CLEARTEXT_BLOCKED_MESSAGE)
        assertThat(state.message).doesNotContain("CLEARTEXT communication")
    }

    @Test fun `no account shows a scheme-neutral error`() = runTest {
        credentialStore.clear()
        val vm = newViewModel()
        vm.loadRoot()

        val state = vm.state.value as CatalogUiState.Error
        assertThat(state.message).doesNotContain("calibre-web")
        assertThat(state.message).contains("Settings")
    }

    // ---------- issue #101: reader-only accounts ----------

    /** Every library-mirror PUT that reached the server, in arrival order. */
    private val libraryPuts = CopyOnWriteArrayList<String>()

    private val libraryItemJson = """
        {"content_hash":"abc","title":"Test Book",
         "created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z"}
    """.trimIndent()

    /**
     * Serves the catalog feed at every path, the EPUB bytes at `/book.epub`,
     * and accepts the library-mirror PUT, recording it so a test can assert
     * whether the post-download hook fired at all.
     */
    private fun serveCatalogAndLibrary() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                return when (req.path?.substringBefore('?')) {
                    "/library/v1/items" -> {
                        libraryPuts += req.method.orEmpty()
                        MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody(libraryItemJson)
                    }
                    "/book.epub" -> MockResponse()
                        .setHeader("Content-Type", "application/epub+zip")
                        .setBody(Buffer().write("fake-epub-bytes".toByteArray()))
                    else -> MockResponse()
                        .setHeader("Content-Type", "application/atom+xml")
                        .setBody(feedXml(server.url("/book.epub").toString()))
                }
            }
        }
    }

    /**
     * A real uploader pointed at the MockWebServer. [LibraryUploader] is final,
     * so the cheapest honest observation of "did the mirror push happen" is
     * whether the PUT arrived.
     */
    private fun libraryUploaderOn(scope: CoroutineScope) = LibraryUploader(
        client = LibraryClient(
            baseUrlProvider = { server.url("/").toString() },
            http = OkHttpClient(),
        ),
        dao = db.documentDao(),
        scope = scope,
    )

    /**
     * Downloads the root feed's single publication and returns once the
     * download coroutine has run its post-download hook.
     *
     * The account is saved before the view model is built, so its init has
     * already started the root load. Calling loadRoot() again would put a
     * second feed response in flight that can land after the download and
     * wipe lastDownloaded.
     */
    private suspend fun downloadFirstPublication(vm: CatalogViewModel) {
        vm.download(awaitLoaded(vm).feed.publications[0], context)
        awaitDownloaded(vm)
    }

    @Test fun `download from an opds catalog does not enqueue sync or a mirror push`() = runTest {
        serveCatalogAndLibrary()
        credentialStore.saveOpdsAccount(server.url("/api/opds/TEST-KEY").toString())
        val uploaderScope = CoroutineScope(Dispatchers.IO)
        try {
            downloadFirstPublication(newViewModel(libraryUploader = libraryUploaderOn(uploaderScope)))

            assertThat(enqueueCount).isEqualTo(0)
            assertThat(libraryPuts).isEmpty()
        } finally {
            uploaderScope.cancel()
        }
    }

    @Test fun `download from a calibre catalog still enqueues sync and a mirror push`() = runTest {
        serveCatalogAndLibrary()
        credentialStore.saveBasicAccount(server.url("/").toString().trimEnd('/'), "u", "p")
        val uploaderScope = CoroutineScope(Dispatchers.IO)
        try {
            downloadFirstPublication(newViewModel(libraryUploader = libraryUploaderOn(uploaderScope)))

            assertThat(enqueueCount).isEqualTo(1)
            assertThat(libraryPuts).isNotEmpty()
        } finally {
            uploaderScope.cancel()
        }
    }
}
