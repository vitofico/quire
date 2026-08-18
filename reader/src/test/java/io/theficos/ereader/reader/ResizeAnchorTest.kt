package io.theficos.ereader.reader

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the reader goes back to after its viewport changes.
 *
 * The stakes are in [resizeAnchor]'s own documentation: Readium only restores a locator
 * precisely when it carries `text.highlight`, and the locator Readium itself publishes never
 * does, so a locator that reaches Readium without those fields is restored by mapping a
 * fraction onto a page grid — the behaviour that walked the reader 20 paragraphs down the
 * chapter over three rotations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ResizeAnchorTest {

    private val chapter = Url("ch1.xhtml")!!

    private fun live(progression: Double = 0.42) = Locator(
        href = chapter,
        mediaType = MediaType.XHTML,
        title = "Chapter 1",
        locations = Locator.Locations(
            progression = progression,
            position = 9,
            totalProgression = 0.084,
        ),
    )

    private fun dom(
        href: Url = chapter,
        selector: String? = ":root > :nth-child(2) > :nth-child(23)",
        highlight: String? = "Chapter 1 paragraph 22 sentence 1",
    ) = Locator(
        href = href,
        mediaType = MediaType.XHTML,
        locations = Locator.Locations(
            otherLocations = selector?.let { mapOf("cssSelector" to it) } ?: emptyMap(),
        ),
        text = Locator.Text(highlight = highlight),
    )

    @Test fun `carries the DOM fields Readium needs to restore precisely`() {
        val anchor = resizeAnchor(live(), dom())

        // Both are load-bearing: the highlight is what makes Readium choose scrollToLocator at
        // all, and the selector is what makes that landing exact.
        assertThat(anchor.text.highlight).isEqualTo("Chapter 1 paragraph 22 sentence 1")
        assertThat(anchor.locations.otherLocations["cssSelector"])
            .isEqualTo(":root > :nth-child(2) > :nth-child(23)")
    }

    @Test fun `keeps the live progression, so the fallback and the percentages still work`() {
        val anchor = resizeAnchor(live(progression = 0.42), dom())

        // If the selector ever fails to resolve, Readium falls back to `progression ?: 0.0` and
        // would otherwise jump to the top of the chapter. These same fields are what the HUD
        // percentage and the saved progress row are read from.
        assertThat(anchor.locations.progression).isEqualTo(0.42)
        assertThat(anchor.locations.totalProgression).isEqualTo(0.084)
        assertThat(anchor.locations.position).isEqualTo(9)
        assertThat(anchor.href).isEqualTo(chapter)
        assertThat(anchor.title).isEqualTo("Chapter 1")
    }

    @Test fun `falls back to the live locator when there is no DOM anchor`() {
        assertThat(resizeAnchor(live(), null)).isEqualTo(live())
    }

    @Test fun `falls back when the DOM anchor has no text for Readium to match`() {
        // Without a highlight Readium never reaches scrollToLocator, so a selector alone would
        // be dead weight on the locator.
        assertThat(resizeAnchor(live(), dom(highlight = null))).isEqualTo(live())
        assertThat(resizeAnchor(live(), dom(highlight = "   "))).isEqualTo(live())
    }

    @Test fun `refuses a DOM anchor from another resource`() {
        // The dangerous case. These selectors are positional, so one captured in a different
        // chapter resolves perfectly happily against whatever paragraph sits at that index here.
        val strayChapter = resizeAnchor(live(), dom(href = Url("ch2.xhtml")!!))
        assertThat(strayChapter).isEqualTo(live())
    }
}
