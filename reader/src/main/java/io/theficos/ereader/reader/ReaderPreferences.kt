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
) {
    init {
        require(fontScale in 0.5..2.0) { "fontScale out of range: $fontScale" }
        require(lineSpacing in 1.0..1.8) { "lineSpacing out of range: $lineSpacing" }
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
)
