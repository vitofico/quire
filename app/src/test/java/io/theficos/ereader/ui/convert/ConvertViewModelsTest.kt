package io.theficos.ereader.ui.convert

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.data.library.convert.ConversionRepository
import io.theficos.ereader.data.library.convert.ConversionStart
import io.theficos.ereader.data.library.convert.ConversionStatus
import io.theficos.ereader.data.library.convert.ConvertClient
import io.theficos.ereader.data.library.convert.ImportOutcome
import io.theficos.ereader.data.local.db.EReaderDatabase
import io.theficos.ereader.testing.awaitRequest
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class ConvertViewModelsTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var db: EReaderDatabase
    private lateinit var repository: ConversionRepository
    private val built = mutableListOf<ViewModel>()
    private var owner: String? = "https://quire.example alice"
    private var wakes = 0
    private val pdf = "%PDF-1.7 tiny".toByteArray()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        (server.dispatcher as QueueDispatcher).setFailFast(MockResponse().setResponseCode(418))
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EReaderDatabase::class.java)
            .allowMainThreadQueries().build()
        db.openHelper.writableDatabase
        repository = ConversionRepository(
            client = ConvertClient(
                { server.url("/").toString() },
                OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).retryOnConnectionFailure(false).build(),
            ),
            dao = db.conversionJobDao(),
            documents = db.documentDao(),
            owner = { owner },
            ownerFlow = MutableStateFlow(owner),
            sourceDir = tmp.newFolder(),
            downloadDir = tmp.newFolder(),
            importEpub = { _, _ -> ImportOutcome.Failed },
            wake = { wakes++ },
        )
    }

    @After fun tearDown() {
        // A view model's scope left running keeps collecting after Main is reset.
        built.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
        db.close()
        server.shutdown()
    }

    private fun prompt(size: Long? = pdf.size.toLong(), metered: Boolean = false, open: () -> InputStream? = { pdf.inputStream() }) =
        PdfConvertViewModel(repository, "Field notes.pdf", size, open, metered = { metered }).also { built += it }

    private fun capabilities(available: Boolean = true, maxBytes: Long = 1_000) =
        MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"available":$available,"max_bytes":$maxBytes,"max_pages":1000}""")

    private fun refusal(code: Int, detail: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody("""{"detail":"$detail"}""")

    private suspend fun PdfConvertViewModel.settled(): PdfConvertState =
        state.first { it != PdfConvertState.Checking && it != PdfConvertState.Preparing }

    @Test fun `an offered conversion asks first, then starts`() = runTest {
        server.enqueue(capabilities())
        val vm = prompt()
        assertThat(vm.settled()).isEqualTo(PdfConvertState.Confirm(maxBytes = 1_000, metered = false))
        assertThat(server.awaitRequest().path).isEqualTo("/convert/v1/capabilities")

        vm.confirm()
        assertThat(vm.settled()).isEqualTo(PdfConvertState.Done(ConversionStart.Started(repository.conversions.first().single().id)))
        assertThat(wakes).isEqualTo(1)
    }

    @Test fun `a metered connection is mentioned`() = runTest {
        server.enqueue(capabilities())
        assertThat(prompt(metered = true).settled()).isEqualTo(PdfConvertState.Confirm(1_000, metered = true))
    }

    @Test fun `a PDF over the server's limit is turned away before anything is copied`() = runTest {
        server.enqueue(capabilities(maxBytes = 5))
        assertThat(prompt().settled()).isEqualTo(PdfConvertState.TooLarge(5))
        assertThat(repository.conversions.first()).isEmpty()
    }

    @Test fun `a PDF of unknown size over the limit is caught while copying`() = runTest {
        server.enqueue(capabilities(maxBytes = 5))
        val vm = prompt(size = null)
        assertThat(vm.settled()).isEqualTo(PdfConvertState.Confirm(5, metered = false))
        vm.confirm()
        assertThat(vm.settled()).isEqualTo(PdfConvertState.TooLarge(5))
    }

    @Test fun `every reason it cannot convert is explained`() = runTest {
        for ((answer, reason) in listOf(
            refusal(404, "Not Found") to ConvertBlocked.NOT_OFFERED,
            refusal(401, "invalid_token") to ConvertBlocked.SIGNED_OUT,
            capabilities(available = false) to ConvertBlocked.UNAVAILABLE,
            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) to ConvertBlocked.UNREACHABLE,
        )) {
            server.enqueue(answer)
            assertThat(prompt().settled()).isEqualTo(PdfConvertState.Blocked(reason))
        }
    }

    @Test fun `with no server there is nothing to ask`() = runTest {
        owner = null
        assertThat(prompt().settled()).isEqualTo(PdfConvertState.Blocked(ConvertBlocked.NO_SERVER))
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun `an unreachable server can be asked again`() = runTest {
        server.enqueue(refusal(502, "bad_gateway"))
        val vm = prompt()
        assertThat(vm.settled()).isEqualTo(PdfConvertState.Blocked(ConvertBlocked.UNREACHABLE))
        server.enqueue(capabilities())
        vm.check()
        assertThat(vm.settled()).isInstanceOf(PdfConvertState.Confirm::class.java)
    }

    @Test fun `a file that cannot be read says so`() = runTest {
        server.enqueue(capabilities())
        val vm = prompt(open = { null })
        vm.settled()
        vm.confirm()
        assertThat(vm.settled()).isEqualTo(PdfConvertState.Blocked(ConvertBlocked.UNREADABLE))
    }

    @Test fun `sharing the same PDF twice points at the conversion under way`() = runTest {
        server.enqueue(capabilities())
        server.enqueue(capabilities())
        val first = prompt()
        first.settled()
        first.confirm()
        val started = (first.settled() as PdfConvertState.Done).outcome as ConversionStart.Started
        val second = prompt()
        second.settled()
        second.confirm()
        assertThat(second.settled()).isEqualTo(PdfConvertState.Done(ConversionStart.AlreadyConverting(started.id)))
    }

    @Test fun `the conversions screen lists, cancels and removes`() = runTest {
        repository.start("Field notes.pdf", 1_000) { pdf.inputStream() }
        wakes = 0
        val vm = ConversionsViewModel(repository).also { built += it }
        vm.conversions.filterNotNull().test {
            val row = awaitItem().single()
            assertThat(row.status).isEqualTo(ConversionStatus.UPLOADING)
            assertThat(row.canCancel).isTrue()
            assertThat(row.canRemove).isFalse()

            vm.cancel(row.id)
            assertThat(awaitItem().single().status).isEqualTo(ConversionStatus.CANCELLED)
            repository.step()
            assertThat(awaitItem().single().canRemove).isTrue()

            vm.remove(row.id)
            assertThat(awaitItem()).isEmpty()
        }
        // Opening the screen woke the worker for the conversion under way, and the cancel woke it
        // again to tell the server.
        assertThat(wakes).isEqualTo(2)
        assertThat(server.requestCount).isEqualTo(0)
    }
}
