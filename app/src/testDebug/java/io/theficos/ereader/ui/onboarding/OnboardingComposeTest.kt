package io.theficos.ereader.ui.onboarding

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose-level smoke tests for the first-launch surfaces. Mirrors
 * `LibraryScreensComposeTest`: Robolectric provides the Looper/Choreographer
 * the Compose test rule needs, SDK 33 matches the rest of the suite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class OnboardingComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test fun welcome_screen_shows_three_paths() {
        composeRule.setContent {
            WelcomeScreen(
                onConnectServer = {},
                onCloudSignIn = {},
                onSkipOffline = {},
            )
        }
        composeRule.onNodeWithText("Connect to my server").assertIsDisplayed()
        composeRule.onNodeWithText("Sign in to Quire Cloud").assertIsDisplayed()
        composeRule.onNodeWithText("Skip — use offline only").assertIsDisplayed()
    }

    @Test fun welcome_screen_skip_fires_callback() {
        var skipped = false
        composeRule.setContent {
            WelcomeScreen(
                onConnectServer = {},
                onCloudSignIn = {},
                onSkipOffline = { skipped = true },
            )
        }
        composeRule.onNodeWithText("Skip — use offline only").performClick()
        assert(skipped) { "Skip callback did not fire" }
    }

    @Test fun cloud_coming_soon_renders_static_copy() {
        composeRule.setContent {
            CloudComingSoonScreen(onBack = {})
        }
        composeRule.onNodeWithText("Quire Cloud is coming soon").assertIsDisplayed()
        composeRule.onNodeWithText("Back").assertIsDisplayed()
    }
}
