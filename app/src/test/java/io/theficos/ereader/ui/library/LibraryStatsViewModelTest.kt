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
        abandonedCount = 0,
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

    @Test
    fun `second load shows cached Ready immediately and refreshes in background`() =
        runTest(dispatcher) {
            val fresh = fakeStats.copy(totalBooks = 99)
            var attempt = 0
            val vm = LibraryStatsViewModel(fetch = {
                attempt++
                if (attempt == 1) fakeStats else fresh
            })
            vm.state.test {
                assertThat(awaitItem()).isInstanceOf(LibraryStatsUiState.Loading::class.java)
                vm.load()
                val first = awaitItem() as LibraryStatsUiState.Ready
                assertThat(first.stats.totalBooks).isEqualTo(3)

                // Second load: cache hit. No Loading flash; goes straight
                // from Ready(cached) (no state change emitted because the
                // value is identical) to Ready(fresh) after background fetch.
                vm.load()
                val second = awaitItem() as LibraryStatsUiState.Ready
                assertThat(second.stats.totalBooks).isEqualTo(99)
            }
        }

    @Test
    fun `refresh failure with cached data keeps cached Ready visible`() = runTest(dispatcher) {
        var attempt = 0
        val vm = LibraryStatsViewModel(fetch = {
            attempt++
            if (attempt == 1) fakeStats else throw LibraryHttpException(500, "boom")
        })
        vm.state.test {
            awaitItem() // Loading
            vm.load()
            val first = awaitItem() as LibraryStatsUiState.Ready
            assertThat(first.stats.totalBooks).isEqualTo(3)

            // Second load should NOT emit an Error variant; cached Ready
            // persists silently. Verify by emitting another load() after
            // and confirming no Error has come through in between.
            vm.load()
            // No new emissions expected (state value didn't change).
            expectNoEvents()
        }
    }

    @Test
    fun `abandoned count flows through Ready state`() = runTest(dispatcher) {
        val stats = fakeStats.copy(abandonedCount = 5)
        val vm = LibraryStatsViewModel(fetch = { stats })
        vm.state.test {
            awaitItem() // Loading
            vm.load()
            val ready = awaitItem() as LibraryStatsUiState.Ready
            assertThat(ready.stats.abandonedCount).isEqualTo(5)
        }
    }

    /**
     * Real SWR: navigating away from the screen and reopening it must NOT
     * show a spinner flash. Achieved by hoisting the cache out of the VM
     * into a shared holder that lives on the factory (process lifetime).
     *
     * Without the fix, the new VM has an empty per-instance cache and emits
     * Loading until the network call returns.
     */
    @Test
    fun `new VM via shared cache emits Ready before fetch completes`() = runTest(dispatcher) {
        val cache = LibraryStatsCache()
        // First VM populates the cache.
        val vm1 = LibraryStatsViewModel(fetch = { fakeStats }, cache = cache)
        vm1.state.test {
            awaitItem() // Loading
            vm1.load()
            val first = awaitItem() as LibraryStatsUiState.Ready
            assertThat(first.stats.totalBooks).isEqualTo(3)
        }

        // Simulate back-out + re-entry: a fresh VM, same factory-held cache.
        // The fetch lambda suspends forever so we can prove the cached Ready
        // is visible BEFORE any network result arrives.
        val gate = kotlinx.coroutines.CompletableDeferred<LibraryStatsResponse>()
        val vm2 = LibraryStatsViewModel(fetch = { gate.await() }, cache = cache)
        vm2.state.test {
            // Initial state is already Ready(cached) — no Loading flash.
            val initial = awaitItem()
            assertThat(initial).isInstanceOf(LibraryStatsUiState.Ready::class.java)
            assertThat((initial as LibraryStatsUiState.Ready).stats.totalBooks).isEqualTo(3)
            vm2.load()
            // Still Ready(cached) while the in-flight fetch is gated.
            expectNoEvents()
            // Now let the fetch complete with fresh data.
            gate.complete(fakeStats.copy(totalBooks = 42))
            val refreshed = awaitItem() as LibraryStatsUiState.Ready
            assertThat(refreshed.stats.totalBooks).isEqualTo(42)
        }
    }

    /**
     * Concurrency guard: an in-flight fetch launched before a later load()
     * must not clobber state once a newer request has started. Regression
     * test for the catch-path race noted in PR review.
     */
    @Test
    fun `stale catch-path response does not override fresh success`() = runTest(dispatcher) {
        val slowFail = kotlinx.coroutines.CompletableDeferred<LibraryStatsResponse>()
        var call = 0
        val vm = LibraryStatsViewModel(fetch = {
            call++
            if (call == 1) {
                slowFail.await() // never completes normally; we'll fail it
                error("unreachable")
            } else {
                fakeStats.copy(totalBooks = 7)
            }
        })
        vm.state.test {
            awaitItem() // Loading
            vm.load() // call 1 in flight
            // Kick off a second load BEFORE the first errors out.
            vm.load() // call 2 — succeeds
            val ready = awaitItem() as LibraryStatsUiState.Ready
            assertThat(ready.stats.totalBooks).isEqualTo(7)
            // Now let the first call fail. With the generation guard, the
            // stale failure must be ignored: no Error emission.
            slowFail.completeExceptionally(LibraryHttpException(500, "stale"))
            expectNoEvents()
        }
    }
}
