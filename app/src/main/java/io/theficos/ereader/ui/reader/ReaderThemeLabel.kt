package io.theficos.ereader.ui.reader

import io.theficos.ereader.reader.ReaderTheme

/**
 * Human-readable name for a theme: `DARK_SEPIA` -> "Dark sepia".
 *
 * Mirrors how the font-family pickers format [io.theficos.ereader.reader.ReaderFontFamily], and
 * keeps display strings out of the reader module, which has no business knowing about labels.
 */
fun ReaderTheme.displayLabel(): String =
    name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
