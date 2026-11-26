package io.theficos.ereader.ui.bookdetail

import android.content.Context
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
import io.theficos.ereader.data.ai.COVER_DRAW
import io.theficos.ereader.data.ai.COVER_KEEP
import io.theficos.ereader.data.ai.COVER_PAINT
import io.theficos.ereader.data.ai.FIELD_AUTHORS
import io.theficos.ereader.data.ai.FIELD_DESCRIPTION
import io.theficos.ereader.data.ai.FIELD_SERIES
import io.theficos.ereader.data.ai.FIELD_TITLE
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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
        val client = AiClient({ server.url("").toString().trimEnd('/') }, OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build(), language = { "en" })
        ai = AiRepository(client, db.insightDao(), appLanguage = { "en" })
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
            coversDir = File(checkNotNull(System.getProperty("java.io.tmpdir"))), openLibraryHttp = OkHttpClient(),
            showTidied = MutableStateFlow(true), owner = flowOf("reader"), unmetered = MutableStateFlow(true),
            prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("book_details", Context.MODE_PRIVATE),
            scope = repositoryScope,
        )
    }

    @After fun tearDown() {
        holdTidy.countDown()
        try {
            runTest { viewModels.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        } finally {
            repositoryScope.cancel()
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

    @Test fun `Improve details shows its progress, then a preview, and says what changed`() = runTest {
        answer("/details/v1/tidy", tidied)
        val vm = viewModel()
        vm.state.first { it.details.available }

        vm.improveDetails()
        assertThat(vm.state.value.details.improving).isTrue()
        holdTidy.countDown()
        val preview = vm.state.first { it.details.preview?.cover?.preview != null }.details.preview!!

        assertThat(preview.changes.map { it.field }).containsExactly(FIELD_TITLE, FIELD_AUTHORS).inOrder()
        assertThat(preview.changes.first().current).isEqualTo("dune_frank_herbert")
        assertThat(preview.changes.first().proposed).isEqualTo("Dune")
        assertThat(preview.coverOptions).isEmpty()
        // Nothing applied yet: at most the decline the open preview holds, which shows the book's own details.
        assertThat(details.observe(bookId).first()?.response?.declined ?: "k").isEqualTo("k")
        assertThat(preview.kept(FIELD_TITLE)).isEqualTo("Dune")
        assertThat(preview.kept(FIELD_SERIES)).isNull()

        vm.toggleChange(FIELD_AUTHORS)
        assertThat(vm.state.value.details.preview!!.kept(FIELD_AUTHORS)).isNull()
        vm.applyPreview()
        val done = vm.state.first { it.details.preview == null && it.details.outcome.isNotEmpty() && it.details.shown?.let { shown -> shown.response.declined == null } == true }

        assertThat(done.details.outcome.joinToString(" ") { it.asString(englishContext()) }).isEqualTo("Updated: title.")
        assertThat(done.details.shown!!.response.accepted).containsExactly(FIELD_TITLE, FIELD_SERIES, FIELD_DESCRIPTION)
        assertThat(done.document!!.title).isEqualTo("dune_frank_herbert")
    }

    @Test fun `with nothing to change and no cover to choose there is no preview`() = runTest {
        answer("/details/v1/tidy", """{"key":"k","book":{"title":"dune_frank_herbert"},"origins":{"title":"file"},"tidy":"not_needed"}""")
        holdTidy.countDown()
        val vm = viewModel()
        vm.state.first { it.details.available }

        vm.improveDetails()
        val done = vm.state.first { it.details.outcome.isNotEmpty() }

        assertThat(done.details.preview).isNull()
        assertThat(done.details.outcome.single().asString(englishContext())).isEqualTo("These details already look right.")
        // Nothing was there to reject, so nothing is kept.
        assertThat(db.bookDetailsDao().find(bookId)).isNull()
    }

    @Test fun `a field the tidy would leave empty is not offered as a change`() = runTest {
        bookId = documents.insert(
            DocumentIdentity(contentHash = "h2"), "dune_frank_herbert", "Frank Herbert",
            "${SIDELOAD_URL_PREFIX}v1/h2", "/nowhere2.epub", null, 0L,
        )
        answer("/details/v1/tidy", """{"key":"k","book":{"title":"Dune","authors":[]},"origins":{"title":"ai"},"tidy":"done"}""")
        holdTidy.countDown()
        val vm = viewModel()
        vm.state.first { it.details.available }

        vm.improveDetails()
        val preview = vm.state.first { it.details.preview?.cover?.preview != null }.details.preview!!

        assertThat(preview.changes.map { it.field }).containsExactly(FIELD_TITLE)
        vm.applyPreview()
        val done = vm.state.first { it.details.preview == null && it.details.outcome.isNotEmpty() }
        assertThat(done.details.outcome.joinToString(" ") { it.asString(englishContext()) }).isEqualTo("Updated: title.")
    }

    @Test fun `the cover choices offered follow the server and the book`() = runTest {
        answer("/ai/v1/config", """{"configured":true,"details_supported":true,"cover_choice_supported":true,"cover_painting_supported":true}""")
        answer("/ai/v1/preferences", """{"ai_enabled":true,"style":{"tone":"neutral","language":"auto"}}""")
        ai.refresh()
        answer("/details/v1/tidy", """{"key":"k","book":{"title":"dune_frank_herbert"},"tidy":"not_needed"}""")
        holdTidy.countDown()
        val vm = viewModel()
        vm.state.first { it.details.available }

        vm.improveDetails()
        val preview = vm.state.first { it.details.preview != null }.details.preview!!

        // No cover of its own: the first choice already finds one.
        assertThat(preview.coverOptions).containsExactly(COVER_KEEP, COVER_DRAW, COVER_PAINT).inOrder()
        assertThat(preview.changes).isEmpty()
        vm.cancelPreview()
        assertThat(vm.state.value.details.preview).isNull()
        val declined = details.observe(bookId).first { it != null }!!
        assertThat(declined.response.declined).isEqualTo("k")
    }

    @Test fun `a spent allowance says when it renews`() = runTest {
        answer("/details/v1/tidy", """{"detail":{"used":5,"limit":5,"resets_at":"2026-12-01T00:00:00+00:00"}}""", code = 429)
        holdTidy.countDown()
        val vm = viewModel()
        vm.state.first { it.details.available }

        vm.improveDetails()
        val failed = vm.state.first { it.details.outcome.isNotEmpty() }

        assertThat(failed.details.outcome.single().asString(englishContext()))
            .isEqualTo("You’ve used up your AI allowance. It renews on 2026-12-01.")
        assertThat(failed.details.stored).isNull()
    }

    @Test fun `Use original is remembered for the book`() = runTest {
        answer("/details/v1/tidy", tidied)
        holdTidy.countDown()
        val vm = viewModel()
        vm.state.first { it.details.available }
        vm.improveDetails()
        vm.state.first { it.details.preview?.canApply == true }
        vm.applyPreview()
        // The decline the preview holds is a stored answer too: wait for the applied one.
        vm.state.first { it.details.shown?.let { shown -> shown.response.declined == null } == true }

        vm.useOriginalDetails(true)
        vm.state.first { it.details.stored?.useOriginal == true }

        val reopened = viewModel().state.first { it.details.stored != null }
        assertThat(reopened.details.shown).isNull()
    }

    @Test fun `tidy failures read as the reader's words`() {
        val en = englishContext()
        fun message(e: Throwable) = tidyErrorMessage(e).asString(en)
        assertThat(message(AiHttpException(429, """{"detail":"monthly_ai_quota_exhausted"}""")))
            .isEqualTo("You’ve used up your AI allowance for now.")
        assertThat(message(AiHttpException(429, """{"detail":"details_rate_limited"}""", retryAfterSeconds = 20)))
            .isEqualTo("The server is busy. Try again in a moment.")
        assertThat(message(AiHttpException(409, """{"detail":"ai_not_opted_in"}""")))
            .isEqualTo("AI isn’t available for your account right now. Check AI features in Settings.")
        assertThat(message(AiHttpException(404, "{}"))).isEqualTo("This server doesn’t tidy book details right now.")
        assertThat(message(IOException("offline"))).isEqualTo("Couldn't reach the server. Check the sync URL and your connection.")
        assertThat(message(AiHttpException(502, "{}"))).isEqualTo("Couldn’t tidy the details. Try again later.")
    }
}
