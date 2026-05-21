package io.theficos.ereader.ui.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.theficos.ereader.data.library.LibraryStatsResponse
import io.theficos.ereader.data.library.TopAuthor
import io.theficos.ereader.data.library.TopTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Smoke tests for the Compose UI test harness. The point is to prove that
 * `scripts/dgradle :app:testDebugUnitTest` can render Material 3 composables
 * under Robolectric and query the resulting semantics tree — not to lock in
 * every pixel of these screens.
 *
 * Robolectric is required even with `createComposeRule()` because Compose's
 * test rule indirectly touches Android framework classes (Looper,
 * Choreographer, etc.) that a plain JVM JUnit run can't satisfy. SDK 33
 * matches the rest of the app's Robolectric-running tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class LibraryScreensComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun libraryInsights_disabled_aiOff_rendersOptInCopyAndSettingsCta() {
        composeRule.setContent {
            LibraryInsightsScreenContent(
                state = LibraryInsightsUiState.Disabled.AiOff,
                onBack = {},
                onOpenBook = {},
                onOpenWeb = {},
                onOpenSettings = {},
                onRefresh = {},
            )
        }

        composeRule
            .onNodeWithText(LibraryInsightsUiState.Disabled.AiOff.message)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Open AI settings").assertIsDisplayed()
        composeRule.onNodeWithText("Library insights").assertIsDisplayed()
    }

    @Test
    fun libraryStats_ready_rendersAbandonedTileWithCount() {
        val stats = LibraryStatsResponse(
            totalBooks = 10,
            finishedCount = 4,
            inProgressCount = 2,
            abandonedCount = 3,
            topAuthors = listOf(TopAuthor("Ursula K. Le Guin", 4)),
            topThemes = listOf(TopTheme("science_fiction", 5, "v3+ insights only")),
            themesCaveat = "Themes are AI-derived",
        )

        // Block the background fetch on a never-completed deferred so the VM
        // keeps the `Ready(cached)` we seeded via the cache — no flakiness
        // from a parallel fetch() racing with the assertions.
        val never = CompletableDeferred<LibraryStatsResponse>()
        val cache = LibraryStatsCache().apply { lastReady = stats }
        val vm = LibraryStatsViewModel(
            fetch = { never.await() },
            cache = cache,
        )

        composeRule.setContent {
            LibraryStatsScreen(viewModel = vm, onBack = {})
        }

        composeRule.onNodeWithText("Library stats").assertIsDisplayed()
        composeRule.onNodeWithText("Abandoned").assertIsDisplayed()
        // Abandoned count (3) + top-author count (4) both render; spot-check both.
        composeRule.onNodeWithText("3").assertIsDisplayed()
        composeRule.onNodeWithText("Ursula K. Le Guin").assertIsDisplayed()
    }
}
