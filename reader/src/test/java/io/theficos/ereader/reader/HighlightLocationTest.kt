package io.theficos.ereader.reader

import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HighlightLocationTest {
    private val locator = Locator(Url("chapter.xhtml")!!, MediaType.XHTML)
    private fun location(path: List<Int>, offset: Int = 0) = HighlightLocation(locator, path, offset)
    private fun saved(anchor: JSONObject): String = locator.copy(locations = Locator.Locations(
        otherLocations = mapOf(StructuralSelectionAnchors.KEY to anchor.toString()))).toJSON().toString()
    private fun anchor(): JSONObject = JSONObject().put("version", 3).put("href", "chapter.xhtml")
        .put("documentHash", "a".repeat(64)).put("quote", "quote").put("rangeText", "quote")
        .put("start", JSONObject().put("nodeType", 3).put("path", JSONArray(listOf(2, 0))).put("offset", 5))
        .put("end", JSONObject().put("nodeType", 3).put("path", JSONArray(listOf(2, 0))).put("offset", 10))

    @Test fun `persisted v3 location exposes its canonical numeric position`() {
        val parsed = HighlightLocation.parse(saved(anchor()))!!
        assertThat(parsed.path).containsExactly(2, 0).inOrder()
        assertThat(parsed.offset).isEqualTo(5)
    }

    @Test fun `malformed legacy wrong resource and noninteger endpoints are unavailable`() {
        assertThat(HighlightLocation.parse("{" )).isNull()
        assertThat(HighlightLocation.parse(saved(anchor().put("version", 2)))).isNull()
        assertThat(HighlightLocation.parse(saved(anchor().put("href", "other.xhtml")))).isNull()
        val bad = anchor().apply { getJSONObject("start").put("offset", 1.5) }
        assertThat(HighlightLocation.parse(saved(bad))).isNull()
        val negative = anchor().apply { getJSONObject("start").put("path", JSONArray(listOf(-1))) }
        assertThat(HighlightLocation.parse(saved(negative))).isNull()
    }

    @Test fun `numeric child indexes are ordered numerically rather than as strings`() {
        assertThat(compareHighlightLocations(location(listOf(2)), location(listOf(10)))).isLessThan(0)
        assertThat(compareHighlightLocations(location(listOf(2, 0), 5), location(listOf(2, 0), 10))).isLessThan(0)
    }

    @Test fun `persisted valid or stale ranges cannot carry trusted rollback metadata`() {
        for (hash in listOf("a".repeat(64), "0".repeat(64))) {
            val row = JSONObject(saved(anchor().put("documentHash", hash)))
            row.getJSONObject("locations").put(VIEWPORT_POSITION_KEY, "origin snapshot")
            assertThat(HighlightLocation.parse(row.toString())).isNull()
        }
    }

    @Test fun `ancestor element offsets are boundaries around their descendant children`() {
        val child = location(listOf(2, 3, 0), 4)
        assertThat(compareHighlightLocations(location(listOf(2), 3), child)).isLessThan(0)
        assertThat(compareHighlightLocations(location(listOf(2), 4), child)).isGreaterThan(0)
        assertThat(compareHighlightLocations(child, location(listOf(2), 3))).isGreaterThan(0)
        assertThat(compareHighlightLocations(child, location(listOf(2), 4))).isLessThan(0)
    }
}
