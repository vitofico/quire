package io.theficos.ereader.ui.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.theficos.ereader.ui.englishContext
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
            .onNodeWithText(LibraryInsightsUiState.Disabled.AiOff.message.asString(englishContext()))
            .assertIsDisplayed()
        composeRule.onNodeWithText("Open AI settings").assertIsDisplayed()
        composeRule.onNodeWithText("Library insights").assertIsDisplayed()
    }
}
