package io.theficos.ereader.ui

import androidx.annotation.StringRes
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.theficos.ereader.reader.ReaderFontFamily
import io.theficos.quire.R

@Composable
fun toneLabel(code: String): String = when (code) {
    "neutral" -> stringResource(R.string.tone_neutral)
    "enthusiastic" -> stringResource(R.string.tone_enthusiastic)
    "scholarly" -> stringResource(R.string.tone_scholarly)
    "casual" -> stringResource(R.string.tone_casual)
    else -> code
}

@Composable
fun ReaderFontFamily.displayLabel(): String = when (this) {
    ReaderFontFamily.SYSTEM -> stringResource(R.string.font_system)
    ReaderFontFamily.LORA -> "Lora"
    ReaderFontFamily.LITERATA -> "Literata"
    ReaderFontFamily.CHARIS -> "Charis SIL"
    ReaderFontFamily.OPEN_DYSLEXIC -> "OpenDyslexic"
}

@Composable
fun readingStatusLabel(code: String): String = when (code) {
    "unread" -> stringResource(R.string.status_unread)
    "in_progress" -> stringResource(R.string.common_in_progress_2)
    "finished" -> stringResource(R.string.common_finished_2)
    "abandoned" -> stringResource(R.string.common_abandoned)
    else -> code
}

/** The stable band codes returned by the affinity API. */
fun affinityBandLabel(code: String): UiText = when (code) {
    "strong" -> uiText(R.string.band_strong)
    "moderate" -> uiText(R.string.band_moderate)
    "weak" -> uiText(R.string.band_weak)
    else -> UiText.Dynamic(code.replaceFirstChar { it.uppercase() })
}

/** Resolve before entering the semantics callback, which is not composable. */
@Composable
fun Modifier.localizedDescription(@StringRes id: Int): Modifier {
    val description = stringResource(id)
    return semantics { contentDescription = description }
}
