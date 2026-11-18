package io.theficos.ereader.ui.bookdetail

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.theficos.ereader.data.ai.BookInsightPayload
import io.theficos.ereader.data.ai.ComparativeAnchor
import io.theficos.ereader.ui.englishContext
import io.theficos.quire.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class InsightSectionVisibilityTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun `insight cards hide absent or blank fields and show populated fields`() {
        val context = englishContext()
        val headings = listOf(
            R.string.common_how_these_themes_show_up, R.string.common_craft,
            R.string.common_if_you_liked, R.string.common_what_sets_this_apart,
            R.string.common_curiosities, R.string.common_discussion_prompts,
        ).map { context.getString(it) }
        val current = mutableStateOf(BookInsightPayload())
        composeRule.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    InsightSection(InsightUiState.Loaded(current.value, emptyList()), onRetry = {})
                }
            }
        }

        val empty = BookInsightPayload(
            themeAnalysis = emptyMap(), craftNotes = "", comparativeAnchors = emptyList(),
            distinctiveTake = "   ", curiosities = listOf(" ", ""), discussionPrompts = listOf("", " "),
        )
        val invalidAnchors = BookInsightPayload(comparativeAnchors = listOf(
            ComparativeAnchor("", "Author", "Style"),
            ComparativeAnchor("Book", "  ", "Style"),
            ComparativeAnchor("Book", "Author", "  "),
        ))
        for (payload in listOf(BookInsightPayload(), empty, empty.copy(craftNotes = "  "), invalidAnchors)) {
            composeRule.runOnIdle { current.value = payload }
            headings.forEach { composeRule.onNodeWithText(it).assertDoesNotExist() }
        }

        composeRule.runOnIdle {
            current.value = BookInsightPayload(
                themeAnalysis = mapOf("theme" to "Theme explanation"),
                craftNotes = "Writing technique",
                comparativeAnchors = listOf(ComparativeAnchor("Book", "Author", "Style")),
                distinctiveTake = "Unusual take",
                curiosities = listOf("Trivia"),
                discussionPrompts = listOf("Question?"),
            )
        }
        headings.forEach { composeRule.onNodeWithText(it).assertExists() }
    }

    @Test fun `expanded theme analysis shows only the first two entries`() {
        val context = englishContext()
        val payload = BookInsightPayload(themeAnalysis = linkedMapOf(
            "First theme" to "First explanation",
            "Second theme" to "Second explanation",
            "Third theme" to "Third explanation",
        ))
        composeRule.setContent {
            MaterialTheme {
                InsightSection(InsightUiState.Loaded(payload, emptyList()), onRetry = {})
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.common_more)).performClick()

        composeRule.onNodeWithText("First explanation").assertExists()
        composeRule.onNodeWithText("Second explanation").assertExists()
        composeRule.onNodeWithText("Third explanation").assertDoesNotExist()
    }
}
