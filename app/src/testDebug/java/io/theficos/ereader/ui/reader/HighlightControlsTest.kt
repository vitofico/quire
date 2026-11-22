package io.theficos.ereader.ui.reader

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
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
                    onSave = { colour, _ -> chosen += colour }, onRemove = {}, onDismiss = {})
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
                    onSave = { _, _ -> }, onRemove = { removed++ }, onDismiss = {})
            }
        }
        compose.onNodeWithText("Green").assertIsSelected()
        compose.onNodeWithText("Remove highlight").performScrollTo().assertIsDisplayed().assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick) { assertThat(it()).isTrue() }
        compose.runOnIdle { assertThat(removed).isEqualTo(1) }
    }

    @Test fun `saved comment is editable and blank clears it while preserving colour`() {
        var change: Pair<HighlightColour, String?>? = null
        compose.setContent {
            MaterialTheme {
                HighlightEditorSheet(HighlightEditor.Existing(saved.copy(comment = "My thought")), false, null,
                    onSave = { colour, comment -> change = colour to comment }, onRemove = {}, onDismiss = {})
            }
        }
        compose.onNodeWithText("My thought").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextReplacement(" ")
        compose.onNodeWithText("Save").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertThat(change).isEqualTo(HighlightColour.GREEN to null) }
    }

    @Test fun `new comment accompanies selected colour`() {
        var change: Pair<HighlightColour, String?>? = null
        compose.setContent {
            MaterialTheme {
                HighlightEditorSheet(HighlightEditor.New("location", "A passage"), false, null,
                    onSave = { colour, comment -> change = colour to comment }, onRemove = {}, onDismiss = {})
            }
        }
        compose.onNode(hasSetTextAction()).performTextReplacement("Remember this")
        compose.onNodeWithText("Pink").performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertThat(change).isEqualTo(HighlightColour.PINK to "Remember this") }
    }

    @Test fun `pending writes disable mutations and cancellation`() {
        compose.setContent {
            MaterialTheme {
                HighlightEditorSheet(HighlightEditor.Existing(saved), true, null,
                    onSave = { _, _ -> }, onRemove = {}, onDismiss = {})
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
                    uiText(R.string.reader_highlight_save_failed), onSave = { _, _ -> }, onRemove = { retries++ }, onDismiss = {})
            }
        }
        compose.onNodeWithText("Couldn’t save the change. Try again.").assertIsDisplayed()
        compose.onNodeWithText("Saved passage").assertIsDisplayed()
        compose.onNodeWithText("Remove highlight").performScrollTo().assertIsDisplayed().assertIsEnabled()
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
