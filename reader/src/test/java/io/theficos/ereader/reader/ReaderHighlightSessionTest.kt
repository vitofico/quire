package io.theficos.ereader.reader

import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.core.model.DocumentIdentity
import io.theficos.ereader.core.model.Highlight
import io.theficos.ereader.core.model.HighlightColour
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.Decoration
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class ReaderHighlightSessionTest {
    @Test fun `a malformed locator does not suppress valid peers and their selected colours`() {
        val locator = Locator(
            href = Url("chapter.xhtml")!!,
            mediaType = MediaType.XHTML,
            text = Locator.Text(highlight = "selected quote"),
            locations = Locator.Locations(otherLocations = mapOf(StructuralSelectionAnchors.KEY to "{}")),
        ).toJSON().toString()
        val parsed = ReaderHighlightSession.parseHighlights(listOf(
            highlight("green", locator, HighlightColour.GREEN),
            highlight("bad", "not JSON", HighlightColour.YELLOW),
            highlight("pink", locator, HighlightColour.PINK),
        ))

        assertThat(parsed.malformedIds).containsExactly("bad")
        assertThat(parsed.decorations.map { it.id }).containsExactly("green", "pink").inOrder()
        assertThat(parsed.decorations.map { (it.style as Decoration.Style.Highlight).tint })
            .containsExactly(HighlightColour.GREEN.highlightTint(), HighlightColour.PINK.highlightTint()).inOrder()
    }

    @Test fun `the shared semantic palette contains three distinct opaque tints`() {
        val colours = HighlightColour.entries.map { it.highlightTint() }
        assertThat(colours.toSet()).hasSize(3)
        assertThat(colours.all { it ushr 24 == 255 }).isTrue()
    }

    private fun highlight(id: String, locator: String, colour: HighlightColour) = Highlight(
        id = id,
        identity = DocumentIdentity(contentHash = "hash"),
        locator = locator,
        text = "selected quote",
        colour = colour,
        bookTitle = "Book",
        bookAuthor = null,
        createdAt = 1,
        updatedAt = 1,
    )
}
