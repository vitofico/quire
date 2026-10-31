package io.theficos.ereader.ui.reader

import com.google.common.truth.Truth.assertThat
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
class StoppingPointTest {
    private fun loc(href: String, position: Int?, total: Double? = null) = Locator(
        href = Url(href)!!, mediaType = MediaType.XHTML,
        locations = Locator.Locations(position = position, totalProgression = total))
    private val spine = listOf("one.xhtml", "two.xhtml")
    private val positions = listOf(loc("one.xhtml", 1), loc("one.xhtml", 2),
        loc("one.xhtml#part", 3), loc("two.xhtml", 4), loc("two.xhtml", 5))

    @Test fun `nested mapped section wins before resource without revealing title`() {
        val toc = listOf(Link(Url("one.xhtml")!!, MediaType.XHTML,
            children = listOf(Link(Url("one.xhtml#part")!!, MediaType.XHTML, title = "Spoiler"))))
        val result = estimateStoppingPoint(spine, toc, positions[0], positions)!!
        assertThat(result.boundary).isEqualTo(StopBoundary.SECTION)
        assertThat(result.locations).isEqualTo(2)
        assertThat(result.fractionOfBook).isWithin(0.001).of(0.4)
    }

    @Test fun `unmapped fragment falls back honestly to resource`() {
        val toc = listOf(Link(Url("one.xhtml#missing")!!, MediaType.XHTML))
        val result = estimateStoppingPoint(spine, toc, positions[1], positions)!!
        assertThat(result.boundary).isEqualTo(StopBoundary.RESOURCE)
        assertThat(result.locations).isEqualTo(2)
    }

    @Test fun `idle and jump use position rather than elapsed time and rotation does not change distance`() {
        val before = estimateStoppingPoint(spine, emptyList(), positions[0], positions)
        assertThat(estimateStoppingPoint(spine, emptyList(), positions[0].copy(title = "resize"), positions)).isEqualTo(before)
        assertThat(estimateStoppingPoint(spine, emptyList(), positions[2], positions)!!.locations).isEqualTo(1)
    }

    @Test fun `last resource estimates end but last location is not automatically finished`() {
        val result = estimateStoppingPoint(spine, emptyList(), positions.last(), positions)!!
        assertThat(result.boundary).isEqualTo(StopBoundary.END)
        assertThat(result.locations).isEqualTo(1)
        assertThat(estimateStoppingPoint(spine, emptyList(), loc("two.xhtml", 5, 1.0), positions)!!.locations).isEqualTo(0)
    }

    @Test fun `missing malformed or unordered positions are unavailable`() {
        assertThat(estimateStoppingPoint(spine, emptyList(), null, positions)).isNull()
        assertThat(estimateStoppingPoint(spine, emptyList(), loc("one.xhtml", null), positions)).isNull()
        assertThat(estimateStoppingPoint(spine, emptyList(), loc("missing.xhtml", 1), positions)).isNull()
        assertThat(estimateStoppingPoint(spine, emptyList(), loc("one.xhtml", 99), positions)).isNull()
        assertThat(estimateStoppingPoint(spine, emptyList(), positions[0], positions.reversed())).isNull()
        assertThat(estimateStoppingPoint(spine, emptyList(), positions[0], emptyList())).isNull()
        assertThat(estimateStoppingPoint(spine, emptyList(), positions[0], listOf(loc("one.xhtml", null)))).isNull()
    }

    @Test fun `account namespace separates users servers and local notes without credential material`() {
        assertThat(memoryAccountKey("https://a/", "reader")).isEqualTo(memoryAccountKey("https://a", "reader"))
        assertThat(memoryAccountKey("https://a", "reader")).isNotEqualTo(memoryAccountKey("https://b", "reader"))
        assertThat(memoryAccountKey("https://a", "reader")).isNotEqualTo(memoryAccountKey("https://a", "other"))
        assertThat(memoryAccountKey("https://a", "reader")).doesNotContain("reader")
        assertThat(memoryAccountKey("https://a", "reader")).isNotEqualTo("device-local")
    }
}
