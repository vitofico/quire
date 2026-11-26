package io.theficos.ereader.ui.stats

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import io.theficos.ereader.data.library.LibraryHttpException
import io.theficos.ereader.data.library.LibraryStatsResponse
import io.theficos.ereader.data.library.TopAuthor
import io.theficos.ereader.data.library.TopTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ReadingStatsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

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

    @Test
    fun `with no sessions the library figures still show`() {
        val stats = LibraryStatsResponse(
            totalBooks = 10,
            finishedCount = 4,
            inProgressCount = 2,
            abandonedCount = 3,
            topAuthors = listOf(TopAuthor("Ursula K. Le Guin", 4)),
            topThemes = listOf(TopTheme("science_fiction", 5, "v3+ insights only")),
            themesCaveat = "Themes are AI-derived",
        )
        // The refresh never ends, so the cached figures stay on screen.
        val never = CompletableDeferred<LibraryStatsResponse>()
        val vm = ReadingStatsViewModel(
            emptyReadingStatsRepository(),
            fetchLibrary = { never.await() },
            libraryCache = LibraryStatsCache().apply { lastReady = stats },
        )
        show(vm)

        composeRule.onNodeWithText("Reading statistics").assertIsDisplayed()
        composeRule.onNodeWithText("Statistics start now").assertIsDisplayed()
        shows("Abandoned")
        shows("3")
        shows("Ursula K. Le Guin")
        shows("science fiction")
    }

    @Test
    fun `a server without library figures says so beside the reading statistics`() {
        val vm = ReadingStatsViewModel(
            emptyReadingStatsRepository(),
            fetchLibrary = { throw LibraryHttpException(404, "nope") },
        )
        show(vm)

        composeRule.waitUntil(timeoutMillis = 10_000) { vm.library.value is LibraryStatsUiState.Error }
        composeRule.onNodeWithText("Statistics start now").assertIsDisplayed()
        shows("Library stats aren't available on this server.")
        shows("Retry")
    }
}
