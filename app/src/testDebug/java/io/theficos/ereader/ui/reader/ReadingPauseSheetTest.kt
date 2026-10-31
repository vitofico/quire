package io.theficos.ereader.ui.reader

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import io.theficos.ereader.data.local.db.MemoryBookmark
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.google.common.truth.Truth.assertThat

@RunWith(RobolectricTestRunner::class)
// Native text metrics are required for real large-font wrapping and pointer hit targets.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], application = android.app.Application::class, qualifiers = "w320dp-h640dp")
class ReadingPauseSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `account change clears an unsaved draft even when neither account has a bookmark`() {
        val scope = mutableStateOf("account-a")
        compose.setContent {
            MaterialTheme {
                ReadingPauseSheet(null, false, true, false, null,
                    { _, _ -> }, {}, {}, {}, accountScope = scope.value)
            }
        }
        compose.onNodeWithText("Your note")
            .performScrollTo().performTextReplacement("Private unsaved question")
        compose.runOnIdle { scope.value = "account-b" }
        compose.onNodeWithText("Private unsaved question").assertDoesNotExist()
    }

    @Test fun `large text narrow sheet lets reader edit replace remove and return`() {
        var saved: Pair<String, Boolean>? = null
        var removed = false
        var returned = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                MaterialTheme {
                    ReadingPauseSheet(MemoryBookmark(1, "a", "loc", "My question", "Nearby passage", 1),
                        true, true, false, StoppingPoint(2, StopBoundary.RESOURCE, 0.03),
                        { note, replace -> saved = note to replace }, { returned = true }, { removed = true }, {})
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Return to saved passage").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.waitForIdle()
        compose.onNodeWithText("Return to saved passage").performTouchInput {
            down(center)
            advanceEventTime(100)
            up()
        }
        compose.waitForIdle()
        assertThat(returned).isTrue()
        compose.onNodeWithText("My question").performScrollTo().performTextReplacement("Why is the narrator avoiding this?")
        compose.onNodeWithText("Save note at saved location").performScrollTo().assertIsDisplayed()
            .assertIsEnabled().performTouchInput { click() }
        compose.waitForIdle()
        assertThat(saved).isEqualTo("Why is the narrator avoiding this?" to false)
        compose.onNodeWithText("Replace with current location").performScrollTo().assertIsDisplayed()
            .assertIsEnabled().performTouchInput { click() }
        compose.waitForIdle()
        assertThat(saved?.second).isTrue()
        compose.onNodeWithText("Remove bookmark").performScrollTo().assertIsDisplayed()
            .assertIsEnabled().performTouchInput { click() }
        compose.waitForIdle()
        assertThat(removed).isTrue()
    }

    @Test fun `unopenable bookmark keeps note and offers recovery while return is disabled`() {
        compose.setContent {
            MaterialTheme {
                ReadingPauseSheet(MemoryBookmark(1, "a", "garbage", "Do not lose me", "", 1),
                    false, true, false, null, { _, _ -> }, {}, {}, {})
            }
        }
        compose.onNodeWithText("Do not lose me").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Return to saved passage").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Replace with current location").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Remove bookmark").performScrollTo().assertIsDisplayed()
    }
}
