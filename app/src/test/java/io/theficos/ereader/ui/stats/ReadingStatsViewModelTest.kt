package io.theficos.ereader.ui.stats

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.data.library.LibraryHttpException
import io.theficos.ereader.data.library.LibraryStatsResponse
import io.theficos.ereader.data.library.TopAuthor
import io.theficos.ereader.data.library.TopTheme
import io.theficos.ereader.data.local.db.BookProgressRow
import io.theficos.ereader.data.local.db.BookWordCountEntity
import io.theficos.ereader.data.local.db.ReadingSessionDao
import io.theficos.ereader.data.local.db.ReadingSessionEntity
import io.theficos.ereader.ui.englishContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

internal const val OWNER = "https://server reader@example.com"

/** A repository over fixed [sessions] and [progress], with no database. */
internal fun fixedReadingStatsRepository(
    sessions: List<ReadingSessionEntity> = emptyList(),
    progress: List<BookProgressRow> = emptyList(),
) = ReadingStatsRepository(
    dao = object : ReadingSessionDao {
        override suspend fun insert(session: ReadingSessionEntity) = Unit
        override fun observeAll(): Flow<List<ReadingSessionEntity>> = flowOf(sessions)
        override suspend fun forOwner(owner: String): List<ReadingSessionEntity> = sessions.filter { it.owner == owner }
        override suspend fun assignUnowned(owner: String) = Unit
        override suspend fun clearSyncedHistory(owner: String, cutoff: Long) = Unit
        override suspend fun clearForOwner(owner: String?) = Unit
        override suspend fun clear() = Unit
        override suspend fun wordCount(bookKey: String): Long? = null
        override fun observeWordCounts(): Flow<List<BookWordCountEntity>> = flowOf(emptyList())
        override suspend fun saveWordCount(count: BookWordCountEntity) = Unit
        override fun observeBookProgress(): Flow<List<BookProgressRow>> = flowOf(progress)
    },
    prefs = ApplicationProvider.getApplicationContext<Application>()
        .getSharedPreferences("reading-stats-test", Context.MODE_PRIVATE),
    scope = CoroutineScope(Job()),
)

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ReadingStatsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private var owner: String? = OWNER

    private fun viewModel(
        cache: LibraryStatsCache = LibraryStatsCache(),
        fetch: suspend () -> LibraryStatsResponse,
    ) = ReadingStatsViewModel(fixedReadingStatsRepository(), owner = { owner }, fetchLibrary = fetch, libraryCache = cache)

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
        val vm = viewModel(fetch = { fakeStats })
        vm.library.test {
            assertThat(awaitItem()).isInstanceOf(LibraryStatsUiState.Loading::class.java)
            vm.loadLibrary()
            val ready = awaitItem()
            assertThat(ready).isInstanceOf(LibraryStatsUiState.Ready::class.java)
            assertThat((ready as LibraryStatsUiState.Ready).stats.totalBooks).isEqualTo(3)
        }
    }

    @Test
    fun `http error transitions to Error with code`() = runTest(dispatcher) {
        val vm = viewModel(fetch = { throw LibraryHttpException(503, "down") })
        vm.library.test {
            assertThat(awaitItem()).isInstanceOf(LibraryStatsUiState.Loading::class.java)
            vm.loadLibrary()
            val err = awaitItem()
            assertThat(err).isInstanceOf(LibraryStatsUiState.Error::class.java)
            assertThat((err as LibraryStatsUiState.Error).message.asString(englishContext())).contains("503")
        }
    }

    @Test
    fun `unauthorized maps to specific message`() = runTest(dispatcher) {
        val vm = viewModel(fetch = { throw LibraryHttpException(401, "nope") })
        vm.library.test {
            awaitItem() // Loading
            vm.loadLibrary()
            val err = awaitItem() as LibraryStatsUiState.Error
            assertThat(err.message.asString(englishContext())).contains("Sign in")
        }
    }

    @Test
    fun `no server set up shows no library figures and fetches nothing`() = runTest(dispatcher) {
        owner = null
        var fetches = 0
        val vm = viewModel { fetches++; fakeStats }
        assertThat(vm.library.value).isEqualTo(LibraryStatsUiState.NoServer)
        vm.loadLibrary()
        advanceUntilIdle()
        assertThat(vm.library.value).isEqualTo(LibraryStatsUiState.NoServer)
        assertThat(fetches).isEqualTo(0)
    }

    @Test
    fun `signing out clears the cached figures`() = runTest(dispatcher) {
        val cache = LibraryStatsCache().apply { lastReady = OWNER to fakeStats }
        val vm = viewModel(cache) { fakeStats }
        assertThat(vm.library.value).isEqualTo(LibraryStatsUiState.Ready(fakeStats))
        owner = null
        vm.loadLibrary()
        advanceUntilIdle()
        assertThat(vm.library.value).isEqualTo(LibraryStatsUiState.NoServer)
        assertThat(cache.lastReady).isNull()
    }

    @Test
    fun `another account's cached figures are never shown`() = runTest(dispatcher) {
        val cache = LibraryStatsCache().apply { lastReady = "https://server other@example.com" to fakeStats }
        val vm = viewModel(cache) { throw LibraryHttpException(503, "down") }
        assertThat(vm.library.value).isEqualTo(LibraryStatsUiState.Loading)
        vm.loadLibrary()
        assertThat(vm.library.value).isEqualTo(LibraryStatsUiState.Loading)
        advanceUntilIdle()
        assertThat(vm.library.value).isInstanceOf(LibraryStatsUiState.Error::class.java)
    }

    @Test
    fun `not found 404 maps to specific message`() = runTest(dispatcher) {
        val vm = viewModel(fetch = { throw LibraryHttpException(404, "nope") })
        vm.library.test {
            awaitItem() // Loading
            vm.loadLibrary()
            val err = awaitItem() as LibraryStatsUiState.Error
            assertThat(err.message.asString(englishContext())).contains("aren't available")
        }
    }

    @Test
    fun `retry after error triggers fresh load`() = runTest(dispatcher) {
        var attempt = 0
        val vm = viewModel(fetch = {
            attempt++
            if (attempt == 1) throw LibraryHttpException(500, "x") else fakeStats
        })
        vm.library.test {
            awaitItem() // Loading
            vm.loadLibrary()
            assertThat(awaitItem()).isInstanceOf(LibraryStatsUiState.Error::class.java)
            vm.loadLibrary()
            assertThat(awaitItem()).isInstanceOf(LibraryStatsUiState.Loading::class.java)
            assertThat(awaitItem()).isInstanceOf(LibraryStatsUiState.Ready::class.java)
        }
    }

    @Test
    fun `second load shows cached Ready immediately and refreshes in background`() =
        runTest(dispatcher) {
            val fresh = fakeStats.copy(totalBooks = 99)
            var attempt = 0
            val vm = viewModel(fetch = {
                attempt++
                if (attempt == 1) fakeStats else fresh
            })
            vm.library.test {
                assertThat(awaitItem()).isInstanceOf(LibraryStatsUiState.Loading::class.java)
                vm.loadLibrary()
                val first = awaitItem() as LibraryStatsUiState.Ready
                assertThat(first.stats.totalBooks).isEqualTo(3)

                // Second load: cache hit. No Loading flash; goes straight
                // from Ready(cached) (no state change emitted because the
                // value is identical) to Ready(fresh) after background fetch.
                vm.loadLibrary()
                val second = awaitItem() as LibraryStatsUiState.Ready
                assertThat(second.stats.totalBooks).isEqualTo(99)
            }
        }

    @Test
    fun `refresh failure with cached data keeps cached Ready visible`() = runTest(dispatcher) {
        var attempt = 0
        val vm = viewModel(fetch = {
            attempt++
            if (attempt == 1) fakeStats else throw LibraryHttpException(500, "boom")
        })
        vm.loadLibrary()
        advanceUntilIdle()
        assertThat(vm.library.value).isEqualTo(LibraryStatsUiState.Ready(fakeStats))

        vm.loadLibrary()
        advanceUntilIdle()
        assertThat(attempt).isEqualTo(2)
        assertThat(vm.library.value).isEqualTo(LibraryStatsUiState.Ready(fakeStats))
    }

    @Test
    fun `abandoned count flows through Ready state`() = runTest(dispatcher) {
        val stats = fakeStats.copy(abandonedCount = 5)
        val vm = viewModel(fetch = { stats })
        vm.library.test {
            awaitItem() // Loading
            vm.loadLibrary()
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
        val vm1 = viewModel(cache) { fakeStats }
        vm1.library.test {
            awaitItem() // Loading
            vm1.loadLibrary()
            val first = awaitItem() as LibraryStatsUiState.Ready
            assertThat(first.stats.totalBooks).isEqualTo(3)
        }

        // Simulate back-out + re-entry: a fresh VM, same factory-held cache.
        // The fetch lambda suspends forever so we can prove the cached Ready
        // is visible BEFORE any network result arrives.
        val gate = CompletableDeferred<LibraryStatsResponse>()
        val vm2 = viewModel(cache) { gate.await() }
        vm2.library.test {
            // Initial state is already Ready(cached): no Loading flash.
            val initial = awaitItem()
            assertThat(initial).isInstanceOf(LibraryStatsUiState.Ready::class.java)
            assertThat((initial as LibraryStatsUiState.Ready).stats.totalBooks).isEqualTo(3)
            vm2.loadLibrary()
            // Still Ready(cached) while the in-flight fetch is gated.
            expectNoEvents()
            // Now let the fetch complete with fresh data.
            gate.complete(fakeStats.copy(totalBooks = 42))
            val refreshed = awaitItem() as LibraryStatsUiState.Ready
            assertThat(refreshed.stats.totalBooks).isEqualTo(42)
        }
    }

    /** Only the latest fetch may change the figures: an earlier one that ends late is ignored. */
    @Test
    fun `a late earlier fetch does not overwrite a newer one`() = runTest(dispatcher) {
        val first = CompletableDeferred<LibraryStatsResponse>()
        var call = 0
        val vm = viewModel(fetch = {
            call++
            if (call == 1) first.await() else fakeStats.copy(totalBooks = 7)
        })
        vm.loadLibrary()
        runCurrent() // call 1 is waiting
        vm.loadLibrary()
        advanceUntilIdle()
        assertThat((vm.library.value as LibraryStatsUiState.Ready).stats.totalBooks).isEqualTo(7)

        first.complete(fakeStats.copy(totalBooks = 1))
        advanceUntilIdle()
        assertThat((vm.library.value as LibraryStatsUiState.Ready).stats.totalBooks).isEqualTo(7)
    }
}
