package io.theficos.ereader.ui.bookdetail

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.core.model.DocumentIdentity
import io.theficos.ereader.core.model.SIDELOAD_URL_PREFIX
import io.theficos.ereader.data.ai.AiClient
import io.theficos.ereader.data.ai.AiHttpException
import io.theficos.ereader.data.ai.AiRepository
import io.theficos.ereader.data.ai.BookDetailsRepository
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.local.ProgressRepository
import io.theficos.ereader.data.local.db.EReaderDatabase
import io.theficos.ereader.ui.englishContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class BookDetailViewModelDetailsTest {
    private lateinit var db: EReaderDatabase
    private lateinit var server: MockWebServer
    private lateinit var documents: DocumentRepository
    private lateinit var ai: AiRepository
    private lateinit var details: BookDetailsRepository
    private val responses = ConcurrentHashMap<String, ConcurrentLinkedQueue<MockResponse>>()
    private val holdTidy = CountDownLatch(1)
    private val viewModels = mutableListOf<BookDetailViewModel>()
    private var bookId = 0L

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EReaderDatabase::class.java)
            .allowMainThreadQueries().build()
        db.openHelper.writableDatabase
        documents = DocumentRepository(db.documentDao())
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/details/v1/tidy") holdTidy.await(10, TimeUnit.SECONDS)
                    return responses[request.path]?.poll() ?: MockResponse().setResponseCode(404)
                }
            }
            start()
        }
        val client = AiClient({ server.url("").toString().trimEnd('/') }, OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build())
        ai = AiRepository(client, db.insightDao())
        answer("/ai/v1/config", """{"configured":true,"details_supported":true}""")
        answer("/ai/v1/preferences", """{"ai_enabled":true,"style":{"tone":"neutral","language":"auto"}}""")
        runBlocking {
            ai.refresh()
            bookId = documents.insert(
                DocumentIdentity(contentHash = "h"), "dune_frank_herbert", null,
                "${SIDELOAD_URL_PREFIX}v1/h", "/nowhere.epub", null, 0L,
            )
        }
        details = BookDetailsRepository(
            client = client, ai = ai, documents = documents, dao = db.bookDetailsDao(),
            coversDir = File(System.getProperty("java.io.tmpdir")), openLibraryHttp = OkHttpClient(),
            showTidied = MutableStateFlow(true),
        )
    }

    @After fun tearDown() {
        holdTidy.countDown()
        try {
            runTest { viewModels.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        } finally {
            runCatching { server.shutdown() }
            db.close()
            Dispatchers.resetMain()
        }
    }

    private fun answer(path: String, body: String, code: Int = 200) {
        responses.getOrPut(path) { ConcurrentLinkedQueue() } += MockResponse().setResponseCode(code).setBody(body)
    }

    private fun viewModel() = BookDetailViewModel(
        documentId = bookId,
        documents = documents,
        progress = ProgressRepository(db.progressDao()),
        ai = ai,
        details = details,
        openOpfBytes = { null },
    ).also { viewModels += it }

    private val tidied = """{"key":"k","book":{"title":"Dune","authors":["Frank Herbert"]},""" +
        """"origins":{"title":"ai","authors":"openlibrary"},"tidy":"done"}"""

    @Test fun `Tidy details shows its progress, then the tidied book and how it went`() = runTest {
        answer("/details/v1/tidy", tidied)
        val vm = viewModel()
        vm.state.first { it.details.available }

        vm.tidyDetails()
        assertThat(vm.state.value.details.tidying).isTrue()
        holdTidy.countDown()
        val done = vm.state.first { it.details.outcome != null && it.details.stored != null }

        assertThat(done.details.tidying).isFalse()
        assertThat(done.details.outcome!!.asString(englishContext())).isEqualTo("Details tidied.")
        assertThat(done.details.shown!!.response.book.title).isEqualTo("Dune")
        assertThat(done.document!!.title).isEqualTo("dune_frank_herbert")
    }

    @Test fun `a spent allowance says when it renews`() = runTest {
        answer("/details/v1/tidy", """{"detail":{"used":5,"limit":5,"resets_at":"2026-12-01T00:00:00+00:00"}}""", code = 429)
        holdTidy.countDown()
        val vm = viewModel()
        vm.state.first { it.details.available }

        vm.tidyDetails()
        val failed = vm.state.first { it.details.outcome != null }

        assertThat(failed.details.outcome!!.asString(englishContext()))
            .isEqualTo("You’ve used up your AI allowance. It renews on 2026-12-01.")
        assertThat(failed.details.stored).isNull()
    }

    @Test fun `Use original is remembered for the book`() = runTest {
        answer("/details/v1/tidy", tidied)
        holdTidy.countDown()
        val vm = viewModel()
        vm.state.first { it.details.available }
        vm.tidyDetails()
        vm.state.first { it.details.shown != null }

        vm.useOriginalDetails(true)
        vm.state.first { it.details.stored?.useOriginal == true }

        val reopened = viewModel().state.first { it.details.stored != null }
        assertThat(reopened.details.shown).isNull()
    }

    @Test fun `tidy failures read as the reader's words`() {
        val en = englishContext()
        fun message(e: Throwable) = tidyErrorMessage(e).asString(en)
        assertThat(message(AiHttpException(429, "{}"))).isEqualTo("You’ve used up your AI allowance for now.")
        assertThat(message(AiHttpException(409, """{"detail":"ai_not_opted_in"}""")))
            .isEqualTo("AI isn’t available for your account right now. Check AI features in Settings.")
        assertThat(message(AiHttpException(404, "{}"))).isEqualTo("This server doesn’t tidy book details right now.")
        assertThat(message(IOException("offline"))).isEqualTo("Couldn't reach the server. Check the sync URL and your connection.")
        assertThat(message(AiHttpException(502, "{}"))).isEqualTo("Couldn’t tidy the details. Try again later.")
    }
}
