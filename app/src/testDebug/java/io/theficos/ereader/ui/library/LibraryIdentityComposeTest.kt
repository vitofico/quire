package io.theficos.ereader.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.ui.components.QuireLoading
import io.theficos.ereader.ui.theme.QuireTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryIdentityComposeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun normalPhoneKeepsTitleAndFiveActionsOnOneRow() {
        header(fontScale = 1f)
        val title = composeRule.onNodeWithText("Quire").fetchSemanticsNode().boundsInRoot
        val first = composeRule.onNodeWithTag("action0").fetchSemanticsNode().boundsInRoot
        assertThat(title.right).isAtMost(first.left)
        assertThat(title.center.y).isWithin(1f).of(first.center.y)
        assertActionsFit()
    }

    @Test fun largeTextMovesActionsBelowTitleWithoutShrinkingTargets() {
        header(fontScale = 2f)
        val title = composeRule.onNodeWithText("Quire").fetchSemanticsNode().boundsInRoot
        val first = composeRule.onNodeWithTag("action0").fetchSemanticsNode().boundsInRoot
        assertThat(title.bottom).isAtMost(first.top)
        assertActionsFit()
    }

    @Test fun loadingKeepsLocalizedStatusAndProgressSemantics() {
        composeRule.setContent { QuireTheme { QuireLoading() } }
        composeRule.onNodeWithText("Loading…").assertIsDisplayed()
        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
    }

    private fun header(fontScale: Float) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                QuireTheme {
                    Box(Modifier.width(328.dp).testTag("header")) {
                        LibraryHeader(actionCount = 5) {
                            repeat(5) { index ->
                                IconButton(onClick = {}, modifier = Modifier.testTag("action$index")) {
                                    Text("$index")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun assertActionsFit() {
        val header = composeRule.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot
        val density = composeRule.density.density
        repeat(5) { index ->
            val action = composeRule.onNodeWithTag("action$index").assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
            val touch = composeRule.onNodeWithTag("action$index").fetchSemanticsNode().touchBoundsInRoot
            assertThat(touch.width / density).isAtLeast(48f)
            assertThat(touch.height / density).isAtLeast(48f)
            assertThat(action.left).isAtLeast(header.left)
            assertThat(action.right).isAtMost(header.right)
        }
    }
}
