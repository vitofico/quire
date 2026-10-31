package io.theficos.ereader.ui.reader

import android.app.Application
import io.theficos.ereader.reader.awaitReaderJavascript
import android.webkit.ValueCallback
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.reader.ReaderPreferences
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ReaderAnchorVerificationTest {
    @Test fun `platform bridge wait is cancellable and ignores a late JavaScript callback`() = runTest {
        var callback: ValueCallback<String>? = null
        val result = withTimeoutOrNull(1500) { awaitReaderJavascript { callback = it } }
        assertThat(result).isNull()
        callback!!.onReceiveValue("true")
        assertThat(result).isNull()
    }

    @Test fun `only plain textless locator can use observed coarse progression fallback`() {
        val plain = Locator(Url("EPUB/cover.xhtml")!!, MediaType.XHTML,
            locations = Locator.Locations(progression = 0.0))
        fun input(locator: Locator) = JSONObject(anchorVerificationScript(locator, ReaderPreferences())
            .substringAfter("var input = ").substringBefore(";\n"))
        assertThat(input(plain).getBoolean("allowProgression")).isTrue()
        assertThat(input(plain.copy(text = Locator.Text(highlight = "Original paragraph")))
            .getBoolean("allowProgression")).isFalse()
        assertThat(input(plain.copy(locations = Locator.Locations(otherLocations = mapOf("cssSelector" to "#missing"))))
            .getBoolean("allowProgression")).isFalse()
    }

    @Test fun `bridge accepts only actual JSON true`() {
        assertThat(strictJavascriptTrue("true")).isTrue()
        assertThat(strictJavascriptTrue(" true \n")).isTrue()
        listOf(null, "false", "null", "\"true\"", "1", "{}", "[true]", "undefined").forEach {
            assertThat(strictJavascriptTrue(it)).isFalse()
        }
    }

    @Test fun `requested small font and layout styles use Readium CSS serialization`() {
        val styles = anchorCssProperties(ReaderPreferences(fontScale = 0.7,
            paragraphIndent = 1.2, paragraphSpacing = 0.4))
        assertThat(styles["--USER__fontSize"]).isEqualTo("70%")
        assertThat(styles["--USER__lineHeight"]).isEqualTo("1.4")
        assertThat(styles["--USER__pageMargins"]).isEqualTo("1.4")
        assertThat(styles["--USER__advancedSettings"]).isEqualTo("readium-advanced-on")
        assertThat(styles["--USER__paraIndent"]).isEqualTo("1.2rem")
        assertThat(styles["--USER__paraSpacing"]).isEqualTo("0.4rem")
    }

    @Test fun `selector is JSON escaped and bridge observes resource font loading and individual viewport fragments`() {
        val selector = "p[data-x=\"quote\\\\\"] ; '\n"
        val anchor = Locator(Url("EPUB/part1.xhtml")!!, MediaType.XHTML,
            locations = Locator.Locations(otherLocations = mapOf("cssSelector" to selector)))
        val script = anchorVerificationScript(anchor, ReaderPreferences(fontScale = 0.7))
        val input = JSONObject(script.substringAfter("var input = ").substringBefore(";\n"))
        assertThat(input.getString("selector")).isEqualTo(selector)
        assertThat(input.getString("resource")).isEqualTo("EPUB/part1.xhtml")
        assertThat(input.getJSONObject("styles").getString("--USER__fontSize")).isEqualTo("70%")
        assertThat(script).contains("document.fonts.status")
        assertThat(script).contains("element.getClientRects()")
        assertThat(script).contains("r.left < window.innerWidth")
    }
}
