package io.theficos.ereader.reader

import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.css.FontStyle
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * The assets folder the reader fonts ship in. It is also the path the page fetches them from:
 * Readium serves app assets to the book's web view under https://readium/assets/.
 */
private const val READER_FONTS_DIR = "fonts"

/**
 * One font file Quire bundles for the reader.
 *
 * [weights] is the span of `font-weight` the file answers for: the whole axis of a variable font,
 * which draws regular through bold from one file, or the single weight of a static one.
 */
internal data class BundledFontFace(val file: String, val italic: Boolean, val weights: IntRange) {
    val path: String get() = "$READER_FONTS_DIR/$file"
}

/** An upright and an italic variable font, `<name>.ttf` and `<name>-Italic.ttf`. */
internal fun variableFaces(name: String, weights: IntRange): List<BundledFontFace> = listOf(
    BundledFontFace("$name.ttf", italic = false, weights = weights),
    BundledFontFace("$name-Italic.ttf", italic = true, weights = weights),
)

/** The four static files a family without a variable build needs. */
internal fun staticFaces(name: String): List<BundledFontFace> = listOf(
    BundledFontFace("$name-Regular.ttf", italic = false, weights = 400..400),
    BundledFontFace("$name-Italic.ttf", italic = true, weights = 400..400),
    BundledFontFace("$name-Bold.ttf", italic = false, weights = 700..700),
    BundledFontFace("$name-BoldItalic.ttf", italic = true, weights = 700..700),
)

/**
 * OpenDyslexic's italic, bold and bold italic. The regular face ships inside Readium, which
 * declares it under the same family name after Quire's faces; a regular declared here would lose
 * to it and never be drawn.
 *
 * These are the 2.001 files that match Readium's regular. OpenDyslexic 3, the maintained release,
 * is a redesign whose letters run up to 31% wider, so its italic would read as another font
 * mid-line.
 */
internal val openDyslexicStyledFaces: List<BundledFontFace> = listOf(
    BundledFontFace("OpenDyslexic-Italic.otf", italic = true, weights = 400..400),
    BundledFontFace("OpenDyslexic-Bold.otf", italic = false, weights = 700..700),
    BundledFontFace("OpenDyslexic-BoldItalic.otf", italic = true, weights = 700..700),
)

/**
 * Declares every bundled reader font to Readium, under the family name the font preference emits.
 *
 * Readium only declares the one font it ships itself, OpenDyslexic's regular. Any other name it is
 * handed goes into the page's CSS as-is, and a web view with no `@font-face` for it silently falls
 * back to its default sans-serif. That is how every serif in the picker used to render in Roboto.
 */
@OptIn(ExperimentalReadiumApi::class)
fun EpubNavigatorFragment.Configuration.declareReaderFonts() {
    servedAssets += "$READER_FONTS_DIR/.*"
    for (family in ReaderFontFamily.entries) {
        val name = family.readium ?: continue
        if (family.bundledFaces.isEmpty()) continue
        addFontFamilyDeclaration(name) {
            family.bundledFaces.forEach { face ->
                addFontFace {
                    addSource(face.path)
                    setFontStyle(if (face.italic) FontStyle.ITALIC else FontStyle.NORMAL)
                    setFontWeight(face.weights)
                }
            }
        }
    }
}
