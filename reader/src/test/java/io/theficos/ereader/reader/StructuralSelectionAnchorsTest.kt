package io.theficos.ereader.reader

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class StructuralSelectionAnchorsTest {
    @Test fun `render locator cannot reach stock quote or fragment fallbacks before adapter is ready`() {
        val saved = Locator(
            href = Url("chapter.xhtml")!!,
            mediaType = MediaType.XHTML,
            text = Locator.Text(highlight = "repeated quote"),
            locations = Locator.Locations(
                fragments = listOf("wrong-first"),
                otherLocations = mapOf("cssSelector" to "body", StructuralSelectionAnchors.KEY to "{\"version\":1}"),
            ),
        )
        val rendered = StructuralSelectionAnchors.decoration("id", saved, 0xFFA5D6A7.toInt()).locator
        val restored = Locator.fromJSON(JSONObject(rendered.toJSON().toString()))!!
        assertThat(restored.text.highlight).isNull()
        assertThat(restored.locations.fragments).isEmpty()
        assertThat(restored.locations.otherLocations["cssSelector"]).isEqualTo(":not(*)")
        assertThat(restored.locations.otherLocations[StructuralSelectionAnchors.KEY]).isEqualTo("{\"version\":1}")
        assertThat(saved.text.highlight).isEqualTo("repeated quote")
    }

    @Test fun `decodes direct and WebView-encoded JSON but rejects null and malformed results`() {
        assertThat(StructuralSelectionAnchors.parseObject("{\"ready\":true}")?.getBoolean("ready")).isTrue()
        assertThat(StructuralSelectionAnchors.parseObject(JSONObject.quote("{\"ready\":true}"))?.getBoolean("ready")).isTrue()
        listOf(null, "null", "", "bad JSON").forEach { assertThat(StructuralSelectionAnchors.parseObject(it)).isNull() }
    }
}
