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
@Config(sdk = [33])
class LocatorSerializationTest {

    @Test fun `serialize then parse yields equivalent locator`() {
        val original = Locator(
            href = Url("/chapter01.xhtml")!!,
            mediaType = MediaType.XHTML,
            title = "Chapter 1",
            locations = Locator.Locations(
                progression = 0.42,
                totalProgression = 0.13,
                position = 7,
            ),
            text = Locator.Text(before = "before", highlight = "hl", after = "after"),
        )

        val encoded = ProgressTracker.serialize(original)
        val parsed = Locator.fromJSON(JSONObject(encoded))

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.href.toString()).isEqualTo("/chapter01.xhtml")
        assertThat(parsed.locations.progression).isEqualTo(0.42)
        assertThat(parsed.locations.totalProgression).isEqualTo(0.13)
        assertThat(parsed.locations.position).isEqualTo(7)
        assertThat(parsed.text.highlight).isEqualTo("hl")
    }

    @Test fun `legacy phase 1 format is rejected`() {
        // {"href":"/x","percent":0.5} is the format the Phase 1 stub wrote; it has no "locations" key.
        val legacy = """{"href":"/x","percent":0.5}"""
        assertThat(ProgressTracker.parseOrNull(legacy)).isNull()
    }

    @Test fun `parseOrNull returns null on garbage input`() {
        assertThat(ProgressTracker.parseOrNull("not json at all")).isNull()
        assertThat(ProgressTracker.parseOrNull("{}")).isNull()
    }
}
