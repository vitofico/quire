package io.theficos.ereader.ui.library

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.data.library.LibraryHttpException
import io.theficos.ereader.data.library.LibraryStatsResponse
import io.theficos.ereader.data.library.TopAuthor
import io.theficos.ereader.data.library.TopTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryStatsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val fakeStats = LibraryStatsResponse(
        totalBooks = 3,
        finishedCount = 1,
        inProgressCount = 1,
        topAuthors = listOf(TopAuthor("A", 1)),
        topThemes = listOf(TopTheme("noir", 1, "v3+ insights only")),
        themesCaveat = "caveat",
    )

    @Test
    fun `load success transitions Loading to Ready`() = runTest(dispatcher) {
        val vm = LibraryStatsViewModel(fetch = { fakeStats })
        vm.state.test {
            assertThat(awaitItem()).isInstanceOf(LibraryStatsUiState.Loading::class.java)
            vm.load()
            val ready = awaitItem()
            assertThat(ready).isInstanceOf(LibraryStatsUiState.Ready::class.java)
            assertThat((ready as LibraryStatsUiState.Ready).stats.totalBooks).isEqualTo(3)
        }
    }

    @Test
    fun `http error transitions to Error with code`() = runTest(dispatcher) {
        val vm = LibraryStatsViewModel(fetch = { throw LibraryHttpException(503, "down") })
        vm.state.test {
            assertThat(awaitItem()).isInstanceOf(LibraryStatsUiState.Loading::class.java)
            vm.load()
            val err = awaitItem()
            assertThat(err).isInstanceOf(LibraryStatsUiState.Error::class.java)
            assertThat((err as LibraryStatsUiState.Error).message).contains("503")
        }
    }

    @Test
    fun `unauthorized maps to specific message`() = runTest(dispatcher) {
        val vm = LibraryStatsViewModel(fetch = { throw LibraryHttpException(401, "nope") })
        vm.state.test {
            awaitItem() // Loading
            vm.load()
            val err = awaitItem() as LibraryStatsUiState.Error
            assertThat(err.message).contains("Sign in")
        }
    }

    @Test
    fun `not found 404 maps to specific message`() = runTest(dispatcher) {
        val vm = LibraryStatsViewModel(fetch = { throw LibraryHttpException(404, "nope") })
        vm.state.test {
            awaitItem() // Loading
            vm.load()
            val err = awaitItem() as LibraryStatsUiState.Error
            assertThat(err.message).contains("aren't available")
        }
    }

    @Test
    fun `retry after error triggers fresh load`() = runTest(dispatcher) {
        var attempt = 0
        val vm = LibraryStatsViewModel(fetch = {
            attempt++
            if (attempt == 1) throw LibraryHttpException(500, "x") else fakeStats
        })
        vm.state.test {
            awaitItem() // Loading
            vm.load()
            assertThat(awaitItem()).isInstanceOf(LibraryStatsUiState.Error::class.java)
            vm.load()
            assertThat(awaitItem()).isInstanceOf(LibraryStatsUiState.Loading::class.java)
            assertThat(awaitItem()).isInstanceOf(LibraryStatsUiState.Ready::class.java)
        }
    }
}
