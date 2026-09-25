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

// Robolectric: a navigator Configuration and Readium's Theme both initialise through Android's
// colour parsing, and the asset and served-path checks need a real AssetManager and PatternMatcher.
@OptIn(ExperimentalReadiumApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReaderFontsTest {

    private data class DeclaredFace(val sources: List<String>, val style: FontStyle?, val weights: IntRange?)

    private val config = EpubNavigatorFragment.Configuration().apply { declareReaderFonts() }

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

    @Test fun `every font in the picker asks the page for a family it can draw`() {
        assertThat(declaredFamilies().keys).containsExactly("Lora", "Literata", "Charis SIL")

        // Readium declares OpenDyslexic itself; every other family has to be declared by Quire.
        val drawable = declaredFamilies().keys + ReadiumFontFamily.OPEN_DYSLEXIC.name
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
                assertWithMessage("$family $style $weight")
                    .that(faces.any { it.style == style && it.weights?.contains(weight) == true })
                    .isTrue()
            }
        }
    }

    @Test fun `every declared font file ships in the APK and is served to the page`() {
        val assets = ApplicationProvider.getApplicationContext<Context>().assets
        val served = config.servedAssets.map { PatternMatcher(it, PatternMatcher.PATTERN_SIMPLE_GLOB) }
        val sources = declaredFamilies().values.flatten().flatMap { it.sources }

        assertThat(sources).hasSize(8)
        sources.forEach { path ->
            assets.open(path).use { assertWithMessage("$path is empty").that(it.read()).isNotEqualTo(-1) }
            assertWithMessage("$path is not served").that(served.any { it.match(path) }).isTrue()
        }
    }
}
