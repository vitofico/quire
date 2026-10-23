package io.theficos.ereader.ui.reader

import io.theficos.ereader.reader.ReaderTheme
import io.theficos.ereader.ui.UiText
import io.theficos.ereader.ui.uiText
import io.theficos.quire.R

/** Keep labels in the app; the reader module owns only preference values. */
fun ReaderTheme.displayLabel(): UiText = uiText(when (this) {
    ReaderTheme.LIGHT -> R.string.common_light
    ReaderTheme.DARK -> R.string.common_dark
    ReaderTheme.SEPIA -> R.string.reader_sepia
    ReaderTheme.DARK_SEPIA -> R.string.reader_dark_sepia
})
