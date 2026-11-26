package io.theficos.ereader.ui.reader

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.core.model.DocumentIdentity
import io.theficos.ereader.core.model.Highlight
import io.theficos.ereader.core.model.HighlightColour
import io.theficos.ereader.data.ai.DiscussAnswer
import io.theficos.ereader.data.ai.DiscussLimits
import io.theficos.ereader.data.ai.DiscussRequest
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class HighlightsDiscussionScreenTest {
    @get:Rule val compose = createComposeRule()

    private val sent = mutableListOf<DiscussRequest>()
    private val vm = HighlightsDiscussionViewModel(
        MutableStateFlow(DiscussLimits(maxQuestions = 2)),
    ) { request ->
        sent += request
        DiscussAnswer("**Both** are about control. See https://example.com", listOf(2))
    }

    private fun highlight(id: String, text: String, comment: String? = null) =
        Highlight(id, DocumentIdentity(contentHash = "h"), "{}", text, HighlightColour.GREEN, "Dune", "Frank Herbert", 0, 0, comment = comment)

    /** The list only composes what is on screen, so scroll it to [matcher] first. */
    private fun scrollTo(matcher: SemanticsMatcher) {
        compose.onNodeWithTag("discuss-list").performScrollToNode(matcher)
    }

    private fun show(fontScale: Float = 1f) {
        vm.open(listOf(highlight("a", "Fear is the mind-killer.", "Compare with the Stoics."), highlight("b", "The spice must flow.")))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme { HighlightsDiscussionScreen(vm) }
            }
        }
    }

    @Test fun `a starter asks at once and the answer shows as typed, with its passages a tap away`() {
        show()
        scrollTo(hasText("Include my notes"))
        compose.onNodeWithText("Include my notes").assertIsEnabled()
        scrollTo(hasText("What connects these?"))
        compose.onNodeWithText("What connects these?").performClick()
        scrollTo(hasText("**Both** are about control. See https://example.com"))
        compose.onNodeWithText("**Both** are about control. See https://example.com").assertIsDisplayed()
        compose.runOnIdle { assertThat(sent.single().messages.single().content).isEqualTo("What connects these?") }
        // The status line stays put and changes its text, so TalkBack announces the answer.
        compose.onNodeWithContentDescription("Answer ready")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        // The notes choice holds for the whole conversation.
        scrollTo(hasText("Include my notes"))
        compose.onNodeWithText("Include my notes").assertIsNotEnabled()
        scrollTo(hasText("Questions left: 1"))
        compose.onNodeWithText("Questions left: 1").assertIsDisplayed()
        compose.onNodeWithText("Share").assertExists()
        compose.onNodeWithText("Copy").assertExists()

        scrollTo(hasContentDescription("Passage 2: The spice must flow."))
        // Read once, as the passage, not again as its number.
        compose.onNodeWithContentDescription("Passage 2: The spice must flow.")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Text))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        compose.onNodeWithTag("discuss-passage-2").assertIsDisplayed()
    }

    @Test fun `at the question limit only Start again is offered, and it unlocks the notes`() {
        show()
        repeat(2) {
            scrollTo(hasTestTag("discuss-question"))
            compose.onNodeWithTag("discuss-question").performTextInput("Why $it?")
            scrollTo(hasText("Ask"))
            compose.onNodeWithText("Ask").performClick()
        }
        scrollTo(hasText("This conversation reached its question limit."))
        compose.onNodeWithText("This conversation reached its question limit.").assertIsDisplayed()
        compose.onNodeWithTag("discuss-question").assertDoesNotExist()
        compose.runOnIdle { assertThat(sent.last().messages.map { it.role }).containsExactly("reader", "assistant", "reader").inOrder() }
        scrollTo(hasText("Start again"))
        compose.onNodeWithText("Start again").performClick()
        scrollTo(hasText("What connects these?"))
        compose.onNodeWithText("What connects these?").assertIsDisplayed()
        scrollTo(hasText("Include my notes"))
        compose.onNodeWithText("Include my notes").assertIsEnabled()
    }

    @Test fun `a paste past the question limit keeps what fits`() {
        show()
        scrollTo(hasTestTag("discuss-question"))
        compose.onNodeWithTag("discuss-question").performTextInput("😀".repeat(1_001))
        compose.runOnIdle { assertThat(vm.state.value.draft).isEqualTo("😀".repeat(1_000)) }
    }

    @Test fun `large text keeps the heading, Back and the question box reachable`() {
        show(fontScale = 2f)
        compose.onNodeWithText("Think it through").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").assertIsDisplayed().assertIsEnabled()
        scrollTo(hasTestTag("discuss-question"))
        compose.onNodeWithTag("discuss-question").assertIsDisplayed()
    }
}
