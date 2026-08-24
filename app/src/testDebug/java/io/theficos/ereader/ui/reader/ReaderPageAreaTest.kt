package io.theficos.ereader.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The reader's page has to be held clear of whatever the window draws under — a punch-hole camera
 * above all — and to keep a margin of its own above and below the text.
 *
 * Issue #97: turning off Readium's inset padding to stop it re-paginating the chapter under the
 * reader (#95) also took away the only thing that ever applied either, so the text ran the full
 * height of the screen and, on a phone with a centred camera, straight under it. Quire applies
 * both now and reports what is left as the viewport — the height a Readium column is laid out in,
 * which is what the re-anchor machinery keys on.
 *
 * The insets are passed in rather than read off the window: a Robolectric window has none, and
 * what matters here is the arithmetic on the way to the page, not where a cutout comes from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class ReaderPageAreaTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test fun `the page clears the cutout and keeps its own margin`() {
        val page = measurePage(insets = WindowInsets(0, CUTOUT_TOP_PX, 0, 0))

        assertThat(page.viewport.height)
            .isEqualTo(page.available.height - CUTOUT_TOP_PX - 2 * page.marginPx)
        // A top cutout costs height, never width.
        assertThat(page.viewport.width).isEqualTo(page.available.width)
    }

    @Test fun `with nothing to clear the page still keeps its margin`() {
        // A phone with no cutout at all: the text must not sit against the top and bottom edges
        // just because there is no inset pushing it away from them.
        val page = measurePage(insets = WindowInsets(0, 0, 0, 0))

        assertThat(page.viewport.height).isEqualTo(page.available.height - 2 * page.marginPx)
    }

    @Test fun `an inset an ancestor already applied is not applied twice`() {
        // With full-screen reading off, ReaderScreen pads the whole reader subtree by the system
        // bars, which in portrait cover the cutout. The page asks for the cutout regardless, and
        // has to come away with nothing left to clear rather than insetting the text a second time.
        val page = measurePage(
            insets = WindowInsets(0, CUTOUT_TOP_PX, 0, 0),
            consumedByAncestor = WindowInsets(0, STATUS_BAR_PX, 0, 0),
        )

        assertThat(page.viewport.height)
            .isEqualTo(page.available.height - STATUS_BAR_PX - 2 * page.marginPx)
    }

    /** What one render measured: the room it started with, and the viewport it reported. */
    private class Page(val available: IntSize, val viewport: IntSize, val marginPx: Int)

    /**
     * Renders a page area filling the whole test root. [consumedByAncestor] stands in for padding
     * an enclosing composable applied before the page got its turn.
     */
    private fun measurePage(
        insets: WindowInsets,
        consumedByAncestor: WindowInsets? = null,
    ): Page {
        var available = IntSize.Zero
        var viewport = IntSize.Zero
        var marginPx = 0
        composeRule.setContent {
            marginPx = with(LocalDensity.current) { MARGIN.roundToPx() }
            Box(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { available = it }
                    .then(consumedByAncestor?.let { Modifier.windowInsetsPadding(it) } ?: Modifier)
            ) {
                ReaderPageArea(
                    insets = insets,
                    verticalMargin = MARGIN,
                    background = Color.White,
                    onViewportChanged = { viewport = it },
                ) {
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        composeRule.waitForIdle()
        assertThat(available.height).isGreaterThan(0)
        return Page(available, viewport, marginPx)
    }

    private companion object {
        val MARGIN = 40.dp
        const val CUTOUT_TOP_PX = 136
        const val STATUS_BAR_PX = 142
    }
}
