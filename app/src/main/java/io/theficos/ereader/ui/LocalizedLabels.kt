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

/**
 * A theme tag of a book insight in the app's language. The server keeps the tags as a fixed
 * English vocabulary so they can be counted across books; a tag outside it reads as itself.
 */
@Composable
fun themeLabel(tag: String): String =
    THEME_LABELS[tag]?.let { stringResource(it) } ?: tag.replace('_', ' ').replaceFirstChar { it.uppercase() }

/** The server's controlled vocabulary, `server/quire_server/core/ai/themes.py`. */
internal val THEME_LABELS: Map<String, Int> = mapOf(
    "science_fiction" to R.string.theme_science_fiction,
    "fantasy" to R.string.theme_fantasy,
    "literary_fiction" to R.string.theme_literary_fiction,
    "contemporary_fiction" to R.string.theme_contemporary_fiction,
    "historical_fiction" to R.string.theme_historical_fiction,
    "young_adult" to R.string.theme_young_adult,
    "middle_grade" to R.string.theme_middle_grade,
    "children" to R.string.theme_children,
    "poetry" to R.string.theme_poetry,
    "drama" to R.string.theme_drama,
    "short_stories" to R.string.theme_short_stories,
    "graphic_novel" to R.string.theme_graphic_novel,
    "dystopia" to R.string.theme_dystopia,
    "post_apocalyptic" to R.string.theme_post_apocalyptic,
    "cyberpunk" to R.string.theme_cyberpunk,
    "space_opera" to R.string.theme_space_opera,
    "first_contact" to R.string.theme_first_contact,
    "time_travel" to R.string.theme_time_travel,
    "alternate_history" to R.string.theme_alternate_history,
    "magical_realism" to R.string.theme_magical_realism,
    "epic_fantasy" to R.string.theme_epic_fantasy,
    "urban_fantasy" to R.string.theme_urban_fantasy,
    "mythology" to R.string.theme_mythology,
    "superheroes" to R.string.theme_superheroes,
    "mystery" to R.string.theme_mystery,
    "thriller" to R.string.theme_thriller,
    "noir" to R.string.theme_noir,
    "horror" to R.string.theme_horror,
    "romance" to R.string.theme_romance,
    "crime" to R.string.theme_crime,
    "coming_of_age" to R.string.theme_coming_of_age,
    "war_fiction" to R.string.theme_war_fiction,
    "adventure" to R.string.theme_adventure,
    "satire" to R.string.theme_satire,
    "western" to R.string.theme_western,
    "biography" to R.string.theme_biography,
    "memoir" to R.string.theme_memoir,
    "history" to R.string.theme_history,
    "science" to R.string.theme_science,
    "philosophy" to R.string.theme_philosophy,
    "essays" to R.string.theme_essays,
    "journalism" to R.string.theme_journalism,
    "business" to R.string.theme_business,
    "economics" to R.string.theme_economics,
    "psychology" to R.string.theme_psychology,
    "self_help" to R.string.theme_self_help,
    "travel" to R.string.theme_travel,
    "cooking" to R.string.theme_cooking,
    "politics" to R.string.theme_politics,
    "religion" to R.string.theme_religion,
    "true_crime" to R.string.theme_true_crime,
    "nature" to R.string.theme_nature,
    "art" to R.string.theme_art,
    "music" to R.string.theme_music,
    "sports" to R.string.theme_sports,
    "education" to R.string.theme_education,
    "technology" to R.string.theme_technology,
    "medicine" to R.string.theme_medicine,
    "health" to R.string.theme_health,
)

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
