package io.theficos.ereader.ui.scan

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.core.metadata.MetadataBundle
import io.theficos.ereader.data.library.AffinityRequestBody
import io.theficos.ereader.data.library.AffinityResponse
import io.theficos.ereader.data.library.LibraryHttpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScanViewModelTest {

    // A valid ISBN-13 (978-0-306-40615-7).
    private val isbn13 = "9780306406157"
    // The ISBN-10 form of the same book (0-306-40615-2).
    private val isbn10 = "0306406152"

    private val bundle = MetadataBundle(title = "Some Book", author = "Some Author")

    private val affinity = AffinityResponse(
        affinityVersion = 1,
        owned = null,
        score = 42,
        band = "maybe",
        reasons = emptyList(),
        generatedAt = "2026-05-29T00:00:00Z",
    )

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun `invalid isbn transitions to InvalidIsbn`() = runTest {
        val vm = ScanViewModel(
            lookup = { error("lookup should not be called") },
            runAffinity = { error("affinity should not be called") },
            onReauth = { error("onReauth should not be called") },
        )
        vm.onIsbnSubmitted("not-an-isbn")
        assertThat(vm.state.value).isEqualTo(ScanUiState.InvalidIsbn)
    }

    @Test fun `valid isbn with lookup miss transitions to NotFound`() = runTest {
        val vm = ScanViewModel(
            lookup = { null },
            runAffinity = { error("affinity should not be called") },
            onReauth = { error("onReauth should not be called") },
        )
        vm.state.test {
            assertThat(awaitItem()).isEqualTo(ScanUiState.Idle)
            vm.onIsbnSubmitted(isbn13)
            var s = awaitItem()
            while (s is ScanUiState.Working) s = awaitItem()
            assertThat(s).isEqualTo(ScanUiState.NotFound)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `valid isbn with lookup hit and affinity ok yields Result with affinity`() = runTest {
        var sentBody: AffinityRequestBody? = null
        val vm = ScanViewModel(
            lookup = { bundle },
            runAffinity = { body -> sentBody = body; affinity },
            onReauth = { error("onReauth should not be called") },
        )
        vm.state.test {
            assertThat(awaitItem()).isEqualTo(ScanUiState.Idle)
            vm.onIsbnSubmitted(isbn13)
            var s = awaitItem()
            while (s is ScanUiState.Working) s = awaitItem()
            assertThat(s).isInstanceOf(ScanUiState.Result::class.java)
            val result = s as ScanUiState.Result
            assertThat(result.bundle).isEqualTo(bundle)
            assertThat(result.affinity).isEqualTo(affinity)
            assertThat(result.affinityUnavailable).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
        // Identity is built from the canonical ISBN-13.
        assertThat(sentBody!!.identity.isbn).isEqualTo(isbn13)
        assertThat(sentBody!!.identity.metadataId).isEqualTo("isbn:$isbn13")
        assertThat(sentBody!!.bundle).isEqualTo(bundle)
    }

    @Test fun `affinity 404 yields Result with affinity null and unavailable true`() = runTest {
        val vm = ScanViewModel(
            lookup = { bundle },
            runAffinity = { throw LibraryHttpException(404, "not found") },
            onReauth = { error("onReauth should not be called") },
        )
        vm.state.test {
            assertThat(awaitItem()).isEqualTo(ScanUiState.Idle)
            vm.onIsbnSubmitted(isbn13)
            var s = awaitItem()
            while (s is ScanUiState.Working) s = awaitItem()
            assertThat(s).isInstanceOf(ScanUiState.Result::class.java)
            val result = s as ScanUiState.Result
            assertThat(result.bundle).isEqualTo(bundle)
            assertThat(result.affinity).isNull()
            assertThat(result.affinityUnavailable).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `affinity 401 invokes onReauth`() = runTest {
        var reauthCalls = 0
        val vm = ScanViewModel(
            lookup = { bundle },
            runAffinity = { throw LibraryHttpException(401, "unauthorized") },
            onReauth = { reauthCalls++ },
        )
        vm.onIsbnSubmitted(isbn13)
        assertThat(reauthCalls).isEqualTo(1)
    }

    @Test fun `affinity other error yields Failed`() = runTest {
        val vm = ScanViewModel(
            lookup = { bundle },
            runAffinity = { throw LibraryHttpException(500, "boom") },
            onReauth = { error("onReauth should not be called") },
        )
        vm.state.test {
            assertThat(awaitItem()).isEqualTo(ScanUiState.Idle)
            vm.onIsbnSubmitted(isbn13)
            var s = awaitItem()
            while (s is ScanUiState.Working) s = awaitItem()
            assertThat(s).isInstanceOf(ScanUiState.Failed::class.java)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `non-library throwable yields Failed`() = runTest {
        val vm = ScanViewModel(
            lookup = { bundle },
            runAffinity = { throw RuntimeException("network down") },
            onReauth = { error("onReauth should not be called") },
        )
        vm.state.test {
            assertThat(awaitItem()).isEqualTo(ScanUiState.Idle)
            vm.onIsbnSubmitted(isbn13)
            var s = awaitItem()
            while (s is ScanUiState.Working) s = awaitItem()
            assertThat(s).isInstanceOf(ScanUiState.Failed::class.java)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `isbn-10 input is canonicalized to isbn-13`() = runTest {
        var sentBody: AffinityRequestBody? = null
        val vm = ScanViewModel(
            lookup = { bundle },
            runAffinity = { body -> sentBody = body; affinity },
            onReauth = { error("onReauth should not be called") },
        )
        vm.state.test {
            assertThat(awaitItem()).isEqualTo(ScanUiState.Idle)
            vm.onIsbnSubmitted(isbn10)
            var s = awaitItem()
            while (s is ScanUiState.Working) s = awaitItem()
            assertThat(s).isInstanceOf(ScanUiState.Result::class.java)
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(sentBody!!.identity.isbn).isEqualTo(isbn13)
        assertThat(sentBody!!.identity.metadataId).isEqualTo("isbn:$isbn13")
    }
}
