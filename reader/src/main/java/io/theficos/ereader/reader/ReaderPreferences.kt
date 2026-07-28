package io.theficos.ereader.reader

import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Color as ReadiumColor
import org.readium.r2.navigator.preferences.FontFamily as ReadiumFontFamily
import org.readium.r2.navigator.preferences.Theme

/**
 * A reader colour scheme.
 *
 * [textColor]/[backgroundColor] are null for the three schemes Readium ships natively — those
 * emit no colour override, so their rendering is unchanged. A scheme that sets them layers custom
 * colours on top of a base appearance: ReadiumCSS applies `--USER__textColor`/`--USER__backgroundColor`
 * unconditionally (unlike line height or paragraph indent, which are gated behind
 * `readium-advanced-on`), so they hold whether or not publisher styles are on.
 *
 * [isDark] drives the system bars, not the page: it must be true whenever the background is dark,
 * which is not the same question as "is the Readium theme DARK".
 */
enum class ReaderTheme(
    val isDark: Boolean,
    internal val textColor: Int? = null,
    internal val backgroundColor: Int? = null,
) {
    LIGHT(isDark = false),
    DARK(isDark = true),
    SEPIA(isDark = false),

    /**
     * Warm text on a warm-dark background, for reading in the dark without the glare of SEPIA or
     * the cold high contrast of DARK (issue #91). Colours are Calibre's own "sepia dark" scheme,
     * which is what the request asked for by name; the pair is 11.36:1, comfortably past WCAG AAA.
     */
    DARK_SEPIA(
        isDark = true,
        textColor = 0xFFF6F3E9.toInt(),
        backgroundColor = 0xFF39322B.toInt(),
    ),
    ;
    // Deliberately no Readium type in this enum: Readium's Theme initialises itself through
    // android.graphics.Color, so holding one here would drag the Android framework into the class
    // initializer and force every plain JVM test that so much as names a theme onto Robolectric.
    // The mapping lives in toEpubPreferences() instead, where the exhaustive `when` still fails
    // compilation if a new theme forgets to declare its appearance.
}

enum class ReaderFontFamily(val readium: ReadiumFontFamily?) {
    SYSTEM(null),
    LORA(ReadiumFontFamily("Lora")),
    LITERATA(ReadiumFontFamily("Literata")),
    CHARTER(ReadiumFontFamily("Charter")),
    OPEN_DYSLEXIC(ReadiumFontFamily("OpenDyslexic")),
}

data class ReaderPreferences(
    val fontScale: Double = 1.0,
    val theme: ReaderTheme = ReaderTheme.LIGHT,
    val fontFamily: ReaderFontFamily = ReaderFontFamily.SYSTEM,
    val lineSpacing: Double = 1.4,
    val tapNavigationEnabled: Boolean = true,
    val pageMargins: Double = 1.4,
    // Nullable: null means "leave the book's / ReadiumCSS default" (Readium emits no
    // override). A concrete value force-applies the override, so 0.0 is NOT "off" — it
    // would collapse the book's paragraph spacing. Defaulting to null keeps upgrades a
    // no-op until the reader explicitly drags the slider.
    val paragraphIndent: Double? = null,
    val paragraphSpacing: Double? = null,
    // When true, keep the book's publisher paragraph styling. Readium gates lineHeight,
    // paragraphIndent and paragraphSpacing behind advancedSettings = !publisherStyles, so
    // those controls only take effect when this is false (the default).
    val usePublisherStyles: Boolean = false,
    val immersiveReading: Boolean = true,
) {
    init {
        require(fontScale in 0.5..2.0) { "fontScale out of range: $fontScale" }
        require(lineSpacing in 1.0..1.8) { "lineSpacing out of range: $lineSpacing" }
        require(pageMargins in 0.5..2.0) { "pageMargins out of range: $pageMargins" }
        require(paragraphIndent == null || paragraphIndent in 0.0..3.0) {
            "paragraphIndent out of range: $paragraphIndent"
        }
        require(paragraphSpacing == null || paragraphSpacing in 0.0..2.0) {
            "paragraphSpacing out of range: $paragraphSpacing"
        }
    }
}

fun ReaderPreferences.toEpubPreferences(): EpubPreferences = EpubPreferences(
    fontSize = fontScale,
    theme = when (theme) {
        ReaderTheme.LIGHT -> Theme.LIGHT
        ReaderTheme.SEPIA -> Theme.SEPIA
        // DARK_SEPIA rides night mode and repaints it with its own colours below. Readium's SEPIA
        // appearance would be the intuitive base, but it blends images with
        // `mix-blend-mode: multiply`, which assumes a light backdrop and smears them on a dark one.
        ReaderTheme.DARK, ReaderTheme.DARK_SEPIA -> Theme.DARK
    },
    textColor = theme.textColor?.let { ReadiumColor(it) },
    backgroundColor = theme.backgroundColor?.let { ReadiumColor(it) },
    fontFamily = fontFamily.readium,
    lineHeight = lineSpacing,
    pageMargins = pageMargins,
    publisherStyles = usePublisherStyles,
    paragraphIndent = paragraphIndent,
    paragraphSpacing = paragraphSpacing,
)
