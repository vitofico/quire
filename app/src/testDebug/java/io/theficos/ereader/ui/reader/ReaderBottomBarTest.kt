package io.theficos.ereader.ui.reader

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTouchInput
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A quick tap on the page slider has to seek to where it landed.
 *
 * Under Compose foundation 1.7, Material3's slider noted where the finger went down in a task it
 * queued on the main thread. When the finger came up before that task ran, as a fast tap on a
 * reader still busy laying out a chapter does, the slider reported 0 and the book jumped to its
 * cover. Foundation 1.8 notes the press at once. The test injects the tap's down and up back to
 * back, with nothing run in between, which is the order that went wrong.
 *
 * The rule's default dispatcher runs a launched task on the spot, which hides the bug: this test
 * passed on foundation 1.7 until it got [StandardTestDispatcher], which queues tasks the way the
 * main thread does. With it, 1.7 fails here with the reported 0.0.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class ReaderBottomBarTest {

    @OptIn(ExperimentalTestApi::class)
    @get:Rule
    val composeRule = createComposeRule(effectContext = StandardTestDispatcher())

    @Test fun `a quick tap on the slider seeks to where it landed, not to the start`() {
        val seeks = mutableListOf<Double>()
        var finished = 0
        composeRule.setContent {
            MaterialTheme {
                ReaderBottomBar(
                    visible = true,
                    chapterTitle = "Chapter 1",
                    percent = 0.1,
                    enabled = true,
                    isDragging = false,
                    locationIndex = 1,
                    locationTotal = 10,
                    onSeekChange = { seeks += it },
                    onSeekFinished = { finished++ },
                )
            }
        }

        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performTouchInput {
                down(percentOffset(0.5f, 0.5f))
                up()
            }
        composeRule.waitForIdle()

        assertThat(finished).isEqualTo(1)
        assertThat(seeks).isNotEmpty()
        seeks.forEach { assertThat(it).isWithin(0.05).of(0.5) }
    }
}
