package io.theficos.ereader.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf

/** Independent of the app palette and the reader's page colours. */
enum class BackgroundStyle(val label: String) {
    PLAIN("Plain"),
    ARTWORK("Artwork"),
    ;

    companion object {
        fun fromStored(value: String?): BackgroundStyle =
            entries.firstOrNull { it.name == value } ?: ARTWORK
    }
}

val LocalBackgroundStyle = staticCompositionLocalOf { BackgroundStyle.ARTWORK }
