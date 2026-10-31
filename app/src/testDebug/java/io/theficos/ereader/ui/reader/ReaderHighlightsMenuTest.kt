package io.theficos.ereader.ui.reader

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.quire.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], application = android.app.Application::class)
class ReaderHighlightsMenuTest {
    @get:Rule val compose = createComposeRule()
    private fun label(id: Int) = ApplicationProvider.getApplicationContext<Context>().getString(id)

    @Test fun `large text narrow More menu retains Pause Highlights and Book details as separate actions`() {
        val chosen = mutableListOf<String>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                MaterialTheme { Box(Modifier.width(320.dp)) {
                    ReaderTopBar(true, "Long book title", {}, { chosen += "details" }, {}, false, {},
                        onHighlights = { chosen += "highlights" }, onReadingPause = { chosen += "pause" }, hasMemory = true)
                } }
            }
        }
        compose.onNodeWithContentDescription(label(R.string.common_more)).performClick()
        compose.onNodeWithText("Pause & resume").assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertThat(chosen).containsExactly("pause") }
        compose.onNodeWithText("Highlights").assertDoesNotExist()
        compose.onNodeWithContentDescription(label(R.string.common_more)).performClick()
        compose.onNodeWithText("Highlights").assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertThat(chosen).containsExactly("pause", "highlights") }
        compose.onNodeWithContentDescription(label(R.string.common_more)).performClick()
        compose.onNodeWithText("Book details").assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertThat(chosen).containsExactly("pause", "highlights", "details") }
    }

    @Test fun `a narrow reader retains direct highlight and type controls and routes Highlights from More`() {
        val chosen = mutableListOf<String>()
        compose.setContent { MaterialTheme { Box(Modifier.width(320.dp)) {
            ReaderTopBar(true, "A long book title", {}, { chosen += "details" }, {}, false, {}, onHighlights = { chosen += "highlights" })
        } } }
        compose.onNodeWithText("Highlight").assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.reader_type_and_theme)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.common_more)).performClick()
        compose.onNodeWithText("Highlights").assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertThat(chosen).containsExactly("highlights") }
        compose.onNodeWithText("Book details").assertDoesNotExist()
    }

    @Test fun `Book details retains its separate action and Done remains directly available`() {
        val chosen = mutableListOf<String>()
        compose.setContent { MaterialTheme { Box(Modifier.width(320.dp)) {
            ReaderTopBar(true, "Book", {}, { chosen += "details" }, {}, true, { chosen += "done" }, onHighlights = { chosen += "highlights" })
        } } }
        compose.onNodeWithText("Done").assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.common_more)).performClick()
        compose.onNodeWithText("Book details").assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertThat(chosen).containsExactly("details") }
    }
}
