package io.theficos.ereader.ui.reader

import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.core.model.DocumentIdentity
import io.theficos.ereader.core.model.Highlight
import io.theficos.ereader.core.model.HighlightColour
import io.theficos.ereader.ui.UiText
import io.theficos.ereader.ui.uiText
import io.theficos.quire.R
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class HighlightListItemsTest {
    private val order = listOf(Link(Url("z.xhtml")!!, MediaType.XHTML, title = "Opening"), Link(Url("a.xhtml")!!, MediaType.XHTML))
    private fun row(id: String, href: String, path: List<Int>, offset: Int = 0, created: Long = 0): Highlight {
        fun point(value: Int) = JSONObject().put("path", JSONArray(path)).put("offset", value).put("nodeType", 3)
        val anchor = JSONObject().put("version", 3).put("href", href).put("documentHash", "a".repeat(64))
            .put("quote", "Saved quote").put("rangeText", "Saved quote").put("start", point(offset)).put("end", point(offset + 1))
        val locator = Locator(Url(href)!!, MediaType.XHTML, locations = Locator.Locations(otherLocations = mapOf("quireDomRange" to anchor.toString())))
        return Highlight(id, DocumentIdentity(contentHash = "hash"), locator.toJSON().toString(), "Saved quote", HighlightColour.GREEN,
            "Book", null, created, created)
    }

    @Test fun `rows follow publication chapter order and numeric structural order rather than creation time`() {
        val rows = listOf(row("second-chapter", "a.xhtml", listOf(0)), row("late", "z.xhtml", listOf(10)),
            row("middle", "z.xhtml", listOf(2), 8), row("early", "z.xhtml", listOf(2), 1, 100))
        val items = highlightListItems(rows, order)
        assertThat(items.map { it.highlight.id }).containsExactly("early", "middle", "late", "second-chapter").inOrder()
        assertThat(items.first().locationLabel).isEqualTo(UiText.Dynamic("Opening"))
        assertThat(items.last().locationLabel).isEqualTo(uiText(R.string.reader_highlight_chapter, 2))
    }

    @Test fun `retained malformed and unknown chapter quotes remain visible after navigable rows`() {
        val valid = row("valid", "z.xhtml", listOf(1))
        val missing = row("missing", "not-in-this-edition.xhtml", listOf(0))
        val malformed = valid.copy(id = "malformed", locator = "{", text = "Keep this full quotation")
        val items = highlightListItems(listOf(missing, malformed, valid), order)
        assertThat(items.first().highlight.id).isEqualTo("valid")
        assertThat(items.drop(1).all { !it.canJump && it.locationLabel == null }).isTrue()
        assertThat(items.single { it.highlight.id == "malformed" }.highlight.text).isEqualTo("Keep this full quotation")
    }

    @Test fun `tombstones are absent and identical positions have stable UUID ties`() {
        val first = row("a", "z.xhtml", listOf(1))
        val second = first.copy(id = "b")
        val deleted = first.copy(id = "deleted", deletedAt = 1)
        assertThat(highlightListItems(listOf(second, deleted, first), order).map { it.highlight.id }).containsExactly("a", "b").inOrder()
    }
}
