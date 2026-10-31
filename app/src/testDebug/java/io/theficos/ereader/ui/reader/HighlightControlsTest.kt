package io.theficos.ereader.ui.reader

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.core.model.DocumentIdentity
import io.theficos.ereader.core.model.Highlight
import io.theficos.ereader.core.model.HighlightColour
import io.theficos.ereader.ui.uiText
import io.theficos.quire.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class HighlightControlsTest {
    @get:Rule val compose = createComposeRule()

    private val saved = Highlight("id", DocumentIdentity(contentHash = "hash"), "locator", "Saved passage",
        HighlightColour.GREEN, "Title", "Author", 0, 0)

    @Test fun `new passage offers labelled colours and choosing one saves that semantic colour`() {
        val chosen = mutableListOf<HighlightColour>()
        compose.setContent {
            MaterialTheme {
                HighlightEditorSheet(HighlightEditor.New("locator", "New passage"), false, null,
                    onColour = { chosen += it }, onRemove = {}, onDismiss = {})
            }
        }
        compose.onNodeWithText("New passage").assertIsDisplayed()
        compose.onNodeWithText("Remove highlight").assertDoesNotExist()
        // Robolectric does not dispatch injected pointer clicks to this separate dialog window.
        // Exercise its public accessibility action here; native emulator QA covers physical taps.
        compose.onNodeWithText("Pink").assertIsDisplayed().assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick) { assertThat(it()).isTrue() }
        compose.runOnIdle { assertThat(chosen).containsExactly(HighlightColour.PINK) }
    }

    @Test fun `saved highlight shows its current colour and offers removal`() {
        var removed = 0
        compose.setContent {
            MaterialTheme {
                HighlightEditorSheet(HighlightEditor.Existing(saved), false, null,
                    onColour = {}, onRemove = { removed++ }, onDismiss = {})
            }
        }
        compose.onNodeWithText("Green").assertIsSelected()
        compose.onNodeWithText("Remove highlight").assertIsDisplayed().assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick) { assertThat(it()).isTrue() }
        compose.runOnIdle { assertThat(removed).isEqualTo(1) }
    }

    @Test fun `pending writes disable mutations and cancellation`() {
        compose.setContent {
            MaterialTheme {
                HighlightEditorSheet(HighlightEditor.Existing(saved), true, null,
                    onColour = {}, onRemove = {}, onDismiss = {})
            }
        }
        compose.onNodeWithText("Pink").assertIsNotEnabled()
        compose.onNodeWithText("Remove highlight").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
    }

    @Test fun `failed write remains visible with enabled controls for retry`() {
        var retries = 0
        compose.setContent {
            MaterialTheme {
                HighlightEditorSheet(HighlightEditor.Existing(saved), false,
                    uiText(R.string.reader_highlight_save_failed), onColour = {}, onRemove = { retries++ }, onDismiss = {})
            }
        }
        compose.onNodeWithText("Couldn’t save the change. Try again.").assertIsDisplayed()
        compose.onNodeWithText("Saved passage").assertIsDisplayed()
        compose.onNodeWithText("Remove highlight").assertIsDisplayed().assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick) { assertThat(it()).isTrue() }
        compose.runOnIdle { assertThat(retries).isEqualTo(1) }
    }

    @Test fun `highlight mode exposes a Done control and mode title`() {
        var done = 0
        compose.setContent {
            MaterialTheme {
                ReaderTopBar(true, "Book title", {}, {}, {}, true, { done++ })
            }
        }
        compose.onNodeWithText("Highlight mode").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        assertThat(done).isEqualTo(1)
    }
}
