package io.theficos.ereader.reader

import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.FontFamily as ReadiumFontFamily
import org.readium.r2.navigator.preferences.Theme

enum class ReaderTheme { LIGHT, DARK, SEPIA }

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
        ReaderTheme.DARK -> Theme.DARK
        ReaderTheme.SEPIA -> Theme.SEPIA
    },
    fontFamily = fontFamily.readium,
    lineHeight = lineSpacing,
    pageMargins = pageMargins,
    publisherStyles = usePublisherStyles,
    paragraphIndent = paragraphIndent,
    paragraphSpacing = paragraphSpacing,
)
