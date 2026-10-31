package io.theficos.ereader.ui.reader

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
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
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class HighlightsScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun item(id: String, text: String = "A saved passage", canJump: Boolean = true) = HighlightListItem(
        highlight = Highlight(id, DocumentIdentity(contentHash = "hash"), "{malformed locator", text,
            HighlightColour.GREEN, "Book title", "Author", 0, 0),
        locationLabel = uiText(R.string.reader_highlight_chapter, 2),
        canJump = canJump,
    )

    @Test fun `identical quotes keep their UUID callback after list reordering`() {
        var rows by mutableStateOf(listOf(item("first"), item("second")))
        val opened = mutableListOf<String>()
        compose.setContent {
            MaterialTheme { HighlightsScreen("Book title", rows, false, null, null, { opened += it }, {}) }
        }
        compose.onNodeWithText("Highlights").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithText("Book title").assertIsDisplayed()
        compose.runOnIdle { rows = rows.reversed() }
        compose.onNodeWithTag("highlight-row-second").assertIsEnabled().performClick()
        compose.runOnIdle { assertThat(opened).containsExactly("second") }
    }

    @Test fun `loading does not claim the book has no highlights`() {
        var loading by mutableStateOf(true)
        compose.setContent {
            MaterialTheme { HighlightsScreen("Book title", emptyList(), loading, null, null, {}, {}) }
        }
        compose.onNodeWithText("Loading highlights…").assertIsDisplayed()
        compose.onNodeWithText("No highlights yet").assertDoesNotExist()
        compose.runOnIdle { loading = false }
        compose.onNodeWithText("Loading highlights…").assertDoesNotExist()
        compose.onNodeWithText("No highlights yet").assertIsDisplayed()
        compose.onNodeWithText("Select text in Highlight mode to save passages from this book.").assertIsDisplayed()
    }

    @Test fun `busy navigation disables other jumps while Back remains available`() {
        var backs = 0
        val opened = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                HighlightsScreen("Book title", listOf(item("first"), item("second")), false, "first", null,
                    { opened += it }, { backs++ })
            }
        }
        compose.onNodeWithTag("highlight-row-first").assertIsNotEnabled()
        compose.onNodeWithTag("highlight-row-second").assertIsNotEnabled()
        compose.onNodeWithText("Opening highlight…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertThat(backs).isEqualTo(1)
            assertThat(opened).isEmpty()
        }
    }

    @Test fun `an unavailable locator keeps the stored quote and semantic colour visible`() {
        compose.setContent {
            MaterialTheme {
                HighlightsScreen("Book title", listOf(item("stale", "The retained quotation", false)), false, null, null, {}, {})
            }
        }
        compose.onNodeWithText("The retained quotation").assertIsDisplayed()
        compose.onNodeWithText("Green").assertIsDisplayed()
        compose.onNodeWithText("Chapter 2").assertIsDisplayed()
        compose.onNodeWithText("Location unavailable in this edition").assertIsDisplayed()
        compose.onNodeWithTag("highlight-row-stale").assertIsNotEnabled()
    }

    @Test fun `a failed load offers retry without claiming there are no saved quotes`() {
        var retries = 0
        compose.setContent {
            MaterialTheme {
                HighlightsScreen("Book title", emptyList(), false, null, uiText(R.string.reader_highlights_load_error),
                    {}, {}, onRetry = { retries++ })
            }
        }
        compose.onNodeWithText("Couldn’t load highlights. Try again.").assertIsDisplayed()
        compose.onNodeWithText("No highlights yet").assertDoesNotExist()
        compose.onNodeWithText("Retry").assertIsEnabled().performClick()
        compose.runOnIdle { assertThat(retries).isEqualTo(1) }
    }

    @Test fun `a jump error keeps its quote and lets the row retry the same UUID`() {
        val opened = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                HighlightsScreen("Book title", listOf(item("target")), false, null,
                    uiText(R.string.reader_highlight_jump_failed), { opened += it }, {})
            }
        }
        compose.onNodeWithText("Couldn’t open this highlight. Try again.").assertIsDisplayed()
        compose.onNodeWithText("A saved passage").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.onNodeWithTag("highlight-row-target").assertIsEnabled().performClick()
        compose.runOnIdle { assertThat(opened).containsExactly("target") }
    }

    @Test fun `a long unavailable quote can expand independently of its disabled jump`() {
        // Explicit lines make overflow independent of Robolectric's font-width metrics.
        val quote = (1..24).joinToString("\n") { "Retained quotation line $it." }
        assertUnavailableQuoteCanExpand(quote)
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun `wrapped ellipsized prose exposes its full retained quotation`() {
        val quote = (
            "The copied record describes a quiet room where an old clock marks each hour and a patient observer writes the same account. ".repeat(3) +
                "The mirrored passage keeps the same promise. " +
                "The copied record continues beside a closed window while the evening light falls across the table and the silent shelves. ".repeat(3)
            ).trimEnd()
        assertThat(quote).hasLength(782)
        assertUnavailableQuoteCanExpand(quote)
    }

    private fun assertUnavailableQuoteCanExpand(quote: String) {
        val opened = mutableListOf<String>()
        compose.setContent {
            MaterialTheme { HighlightsScreen("Book title", listOf(item("long", quote, false)), false, null, null, { opened += it }, {}) }
        }
        compose.onNodeWithTag("highlight-row-long").assertIsNotEnabled()
        var collapsedLines = 0
        compose.onNodeWithText(quote, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                val results = mutableListOf<TextLayoutResult>()
                assertThat(action(results)).isTrue()
                collapsedLines = results.single().lineCount
            }
        compose.runOnIdle { assertThat(collapsedLines).isEqualTo(5) }
        compose.onNodeWithText("Show full quote").performScrollTo().assertIsEnabled().performClick()
        var lines = 0
        compose.onNodeWithText(quote, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                val results = mutableListOf<TextLayoutResult>()
                assertThat(action(results)).isTrue()
                lines = results.single().lineCount
            }
        compose.runOnIdle { assertThat(lines).isGreaterThan(5) }
        compose.onNodeWithText("Show less").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Show full quote").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertThat(opened).isEmpty() }
    }

    @Test fun `large text keeps the screen title and Back accessible`() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme {
                    HighlightsScreen("A long book title that wraps onto a second line", emptyList(), false, null, null, {}, {})
                }
            }
        }
        compose.onNodeWithText("Highlights").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").assertIsDisplayed().assertIsEnabled()
    }
}
