package io.theficos.ereader.reader

import android.content.Context
import android.os.PatternMatcher
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.css.FontStyle
import org.readium.r2.navigator.epub.css.FontWeight
import org.readium.r2.navigator.preferences.FontFamily as ReadiumFontFamily
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.util.Either
import org.readium.r2.shared.util.Url
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

// Robolectric: a navigator Configuration and Readium's Theme both initialise through Android's
// colour parsing, and the asset and served-path checks need a real AssetManager and PatternMatcher.
@OptIn(ExperimentalReadiumApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReaderFontsTest {

    private data class DeclaredFace(val sources: List<String>, val style: FontStyle?, val weights: IntRange?)

    private val config = EpubNavigatorFragment.Configuration().apply { declareReaderFonts() }
    private val assets = ApplicationProvider.getApplicationContext<Context>().assets

    /**
     * The `@font-face` declarations Readium will inject into every page, by family name.
     *
     * Readium 3.0 keeps them in an internal field with no public getter, so this reads them back
     * reflectively: it is the only way to test what the navigator was actually handed, rather than
     * what Quire meant to hand it. A Readium upgrade that moves the field fails every test here,
     * loudly, and this is the one place to update.
     */
    private fun declaredFamilies(): Map<String, List<DeclaredFace>> {
        fun Any.read(getter: String): Any? = javaClass.getMethod(getter).invoke(this)
        val declarations = EpubNavigatorFragment.Configuration::class.java
            .getDeclaredField("fontFamilyDeclarations")
            .apply { isAccessible = true }
            .get(config) as List<*>
        return declarations.filterNotNull().associate { family ->
            val faces = (family.read("getFontFaces") as List<*>).filterNotNull().map { face ->
                DeclaredFace(
                    sources = (face.read("getSources") as List<*>).filterNotNull()
                        .map { (it.read("getHref") as Url).path.orEmpty() },
                    style = face.read("getFontStyle") as FontStyle?,
                    weights = when (val weight = face.read("getFontWeight") as Either<*, *>?) {
                        is Either.Left<*, *> -> (weight.value as FontWeight).value.let { it..it }
                        is Either.Right<*, *> -> (weight.value as ClosedRange<*>)
                            .let { (it.start as Int)..(it.endInclusive as Int) }
                        null -> null
                    },
                )
            }
            family.read("getFontFamily") as String to faces
        }
    }

    /** The version a font file reports: the `fontRevision` field of its `head` table. */
    private fun fontRevision(path: String): Int {
        val font = ByteBuffer.wrap(assets.open(path).use { it.readBytes() })
        val head = (0 until font.getShort(4).toInt()).map { 12 + 16 * it }
            .first { font.getInt(it) == 0x68656164 } // "head"
        return font.getInt(font.getInt(head + 8) + 4)
    }

    @Test fun `every font in the picker asks the page for a family it can draw`() {
        assertThat(declaredFamilies().keys)
            .containsExactly("Lora", "Literata", "Charis SIL", ReadiumFontFamily.OPEN_DYSLEXIC.name)

        val drawable = declaredFamilies().keys
        ReaderFontFamily.entries.filter { it != ReaderFontFamily.SYSTEM }.forEach { family ->
            val requested = ReaderPreferences(fontFamily = family).toEpubPreferences().fontFamily?.name
            assertWithMessage("family $family asks the page for").that(requested).isIn(drawable)
        }
        assertThat(ReaderPreferences(fontFamily = ReaderFontFamily.SYSTEM).toEpubPreferences().fontFamily)
            .isNull()
    }

    @Test fun `each bundled family has a real regular, italic, bold and bold italic`() {
        // Missing one, the web view fakes it by slanting or thickening the regular face.
        declaredFamilies().forEach { (family, faces) ->
            for (style in FontStyle.entries) for (weight in listOf(400, 700)) {
                // Readium declares OpenDyslexic's regular itself; see the test below.
                val readiumsRegular = family == ReadiumFontFamily.OPEN_DYSLEXIC.name &&
                    style == FontStyle.NORMAL && weight == 400
                if (readiumsRegular) continue
                assertWithMessage("$family $style $weight")
                    .that(faces.any { it.style == style && it.weights?.contains(weight) == true })
                    .isTrue()
            }
        }
    }

    @Test fun `OpenDyslexic's own faces complete the regular Readium ships, from the same release`() {
        // Readium appends its regular after Quire's faces and the later @font-face wins, so a
        // regular declared here would ship in the APK and never be drawn.
        val faces = declaredFamilies().getValue(ReadiumFontFamily.OPEN_DYSLEXIC.name)
        assertThat(faces.map { it.style to it.weights }).containsExactly(
            FontStyle.ITALIC to 400..400,
            FontStyle.NORMAL to 700..700,
            FontStyle.ITALIC to 700..700,
        )

        // OpenDyslexic 3 is a wider redesign. If a Readium upgrade swaps its regular for it, these
        // 2.001 faces would draw a visibly different font beside it: replace them to match.
        val regular = fontRevision("readium/fonts/OpenDyslexic-Regular.otf")
        faces.flatMap { it.sources }.forEach { path ->
            assertWithMessage(path).that(fontRevision(path)).isEqualTo(regular)
        }
    }

    @Test fun `every declared font file ships in the APK and is served to the page`() {
        val served = config.servedAssets.map { PatternMatcher(it, PatternMatcher.PATTERN_SIMPLE_GLOB) }
        val sources = declaredFamilies().values.flatten().flatMap { it.sources }

        assertThat(sources).hasSize(11)
        sources.forEach { path ->
            assets.open(path).use { assertWithMessage("$path is empty").that(it.read()).isNotEqualTo(-1) }
            assertWithMessage("$path is not served").that(served.any { it.match(path) }).isTrue()
        }
    }
}
