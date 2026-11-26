package io.theficos.ereader.ui.stats

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import io.theficos.ereader.data.library.LibraryHttpException
import io.theficos.ereader.data.library.LibraryStatsResponse
import io.theficos.ereader.data.library.TopAuthor
import io.theficos.ereader.data.library.TopTheme
import io.theficos.ereader.data.local.db.BookProgressRow
import io.theficos.ereader.data.local.db.ReadingSessionEntity
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A tall screen, so every card up to the per-book list is composed at once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w411dp-h3000dp")
class ReadingStatsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val figures = LibraryStatsResponse(
        totalBooks = 10,
        finishedCount = 4,
        inProgressCount = 2,
        abandonedCount = 3,
        topAuthors = listOf(TopAuthor("Ursula K. Le Guin", 4)),
        topThemes = listOf(TopTheme("science_fiction", 5, "v3+ insights only")),
        themesCaveat = "Themes are AI-derived",
    )

    /** Ten minutes of reading an hour ago. */
    private val session = System.currentTimeMillis().let { now ->
        ReadingSessionEntity(
            bookKey = "hash",
            title = "A Book",
            author = null,
            startedAt = now - 3_600_000,
            endedAt = now - 3_000_000,
            utcOffsetSeconds = 0,
            activeSeconds = 600,
            progressionRead = 0.05,
            wordsRead = null,
        )
    }
    private val finished = BookProgressRow("hash", percent = 1.0, finishedAt = 1L, abandonedAt = null)

    /** The server's figures from the cache, with a refresh that never ends so they stay. */
    private fun withFigures(repository: ReadingStatsRepository) = ReadingStatsViewModel(
        repository,
        owner = { OWNER },
        fetchLibrary = { CompletableDeferred<LibraryStatsResponse>().await() },
        libraryCache = LibraryStatsCache().apply { lastReady = OWNER to figures },
    )

    private fun withoutServer(repository: ReadingStatsRepository) = ReadingStatsViewModel(
        repository,
        owner = { null },
        fetchLibrary = { error("no server to ask") },
    )

    private fun show(vm: ReadingStatsViewModel) {
        composeRule.setContent { ReadingStatsScreen(viewModel = vm, onBack = {}) }
        composeRule.waitUntil(timeoutMillis = 10_000) { vm.state.value != null }
    }

    /** Scrolls the statistics list to [text] and checks it is on screen. */
    private fun shows(text: String) {
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(text))
        // The library card is one tall item: bring the line itself into view too.
        composeRule.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }

    private fun absent(text: String) = composeRule.onAllNodesWithText(text).assertCountEquals(0)

    @Test
    fun `with no sessions the library figures still show`() {
        show(withFigures(fixedReadingStatsRepository()))

        composeRule.onNodeWithText("Reading statistics").assertIsDisplayed()
        composeRule.onNodeWithText("Statistics start now").assertIsDisplayed()
        shows("Abandoned")
        shows("3")
        shows("Ursula K. Le Guin")
        shows("science fiction")
    }

    @Test
    fun `the server's finished count replaces the device's own`() {
        show(withFigures(fixedReadingStatsRepository(listOf(session), listOf(finished))))

        shows("Today")
        shows("YOUR LIBRARY")
        absent("Books finished")
    }

    @Test
    fun `without a server the device counts finished books and the library card is left out`() {
        show(withoutServer(fixedReadingStatsRepository(listOf(session), listOf(finished))))

        shows("Today")
        shows("Books finished")
        absent("YOUR LIBRARY")
    }

    @Test
    fun `with no sessions and no server a finished book still counts`() {
        show(withoutServer(fixedReadingStatsRepository(progress = listOf(finished))))

        composeRule.onNodeWithText("Statistics start now").assertIsDisplayed()
        shows("Books finished")
        absent("YOUR LIBRARY")
    }

    @Test
    fun `a server without library figures says so and the device counts finished books`() {
        val vm = ReadingStatsViewModel(
            fixedReadingStatsRepository(listOf(session), listOf(finished)),
            owner = { OWNER },
            fetchLibrary = { throw LibraryHttpException(404, "nope") },
        )
        show(vm)

        composeRule.waitUntil(timeoutMillis = 10_000) { vm.library.value is LibraryStatsUiState.Error }
        shows("Books finished")
        shows("Library stats aren't available on this server.")
        shows("Retry")
    }
}
