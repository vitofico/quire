package io.theficos.ereader.reader

import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Theme

enum class ReaderTheme { LIGHT, DARK, SEPIA }

data class ReaderPreferences(
    val fontScale: Double = 1.0,
    val theme: ReaderTheme = ReaderTheme.LIGHT,
) {
    init {
        require(fontScale in 0.5..2.0) { "fontScale out of range: $fontScale" }
    }
}

fun ReaderPreferences.toEpubPreferences(): EpubPreferences = EpubPreferences(
    fontSize = fontScale,
    theme = when (theme) {
        ReaderTheme.LIGHT -> Theme.LIGHT
        ReaderTheme.DARK -> Theme.DARK
        ReaderTheme.SEPIA -> Theme.SEPIA
    },
)
