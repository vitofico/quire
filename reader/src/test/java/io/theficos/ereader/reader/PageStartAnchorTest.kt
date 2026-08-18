package io.theficos.ereader.reader

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Reading back what [PAGE_START_ANCHOR_JS] found.
 *
 * The script itself runs in Readium's WebView and is verified on a device; this covers the
 * Kotlin side of the boundary, which has to survive the script declining to answer — it returns
 * `null` for a page nothing starts on, and for layouts it bows out of — without turning that
 * into an anchor Readium cannot resolve.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PageStartAnchorTest {

    private val chapter = Url("ch1.xhtml")!!

    private fun parse(json: String?) = parsePageStartAnchor(json, chapter, MediaType.XHTML)

    @Test fun `builds the locator shape Readium restores from`() {
        val anchor = parse(
            """{"cssSelector":":root > :nth-child(2) > :nth-child(23)","text":"Paragraph 22."}"""
        )

        assertThat(anchor).isNotNull()
        assertThat(anchor!!.locations.otherLocations["cssSelector"])
            .isEqualTo(":root > :nth-child(2) > :nth-child(23)")
        assertThat(anchor.text.highlight).isEqualTo("Paragraph 22.")
        // The script has no idea which resource it is running in; the caller supplies that, and
        // it is what later lets a stale anchor from another chapter be rejected.
        assertThat(anchor.href).isEqualTo(chapter)
        assertThat(anchor.mediaType).isEqualTo(MediaType.XHTML)
    }

    @Test fun `declines the script's own null`() {
        // What the script returns for a page with nothing starting on it, and for a scrolled or
        // right-to-left layout. The caller falls back to Readium's own answer.
        assertThat(parse("null")).isNull()
        assertThat(parse(null)).isNull()
        assertThat(parse("")).isNull()
        assertThat(parse("   ")).isNull()
    }

    @Test fun `declines anything unparseable rather than throwing`() {
        assertThat(parse("{oops")).isNull()
        assertThat(parse("\"a string\"")).isNull()
    }

    @Test fun `declines a half-built anchor`() {
        // Readium needs both halves: no selector means it would search the whole document for
        // the text, no text means it never takes the precise path at all.
        assertThat(parse("""{"text":"Paragraph 22."}""")).isNull()
        assertThat(parse("""{"cssSelector":":root > :nth-child(2)"}""")).isNull()
        assertThat(parse("""{"cssSelector":"","text":"Paragraph 22."}""")).isNull()
        assertThat(parse("""{"cssSelector":":root","text":"  "}""")).isNull()
    }
}
