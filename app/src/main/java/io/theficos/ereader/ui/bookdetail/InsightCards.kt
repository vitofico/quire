package io.theficos.ereader.ui.bookdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.theficos.ereader.data.ai.AuthorInsight
import io.theficos.ereader.data.ai.Citation
import io.theficos.ereader.data.ai.ComparativeAnchor
import io.theficos.ereader.data.ai.SeriesInsight
import io.theficos.ereader.ui.components.QuireCard
import io.theficos.quire.R

@Composable
fun InsightSection(state: InsightUiState, onRetry: () -> Unit) {
    when (state) {
        InsightUiState.Hidden -> Unit
        InsightUiState.Loading -> LoadingCard()
        is InsightUiState.Error -> ErrorCard(state.message.asString(), onRetry)
        is InsightUiState.Loaded -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.payload.intro?.takeIf { it.isNotBlank() }?.let { IntroCard(it) }
            state.payload.author?.let { AuthorCard(it) }
            state.payload.series?.let { SeriesCard(it) }
            state.payload.analysis?.takeIf { it.isNotBlank() }?.let { AnalysisCard(it) }
            state.payload.contentWarnings?.takeIf { it.isNotEmpty() }?.let { ContentWarningsCard(it) }
            // PR-ε / schema v4 cards. Each gates on non-null + non-empty/non-blank;
            // server-side validator rejects >2 theme_analysis entries so take(2) is
            // defense-in-depth only.
            state.payload.themeAnalysis
                ?.takeIf { it.isNotEmpty() }
                ?.let { ThemeAnalysisCard(it.entries.take(2)) }
            state.payload.craftNotes?.takeIf { it.isNotBlank() }?.let { CraftCard(it) }
            state.payload.comparativeAnchors
                ?.filter { it.book.isNotBlank() && it.author.isNotBlank() && it.similarIn.isNotBlank() }
                ?.takeIf { it.isNotEmpty() }
                ?.let { ComparativeAnchorsCard(it.take(4)) }
            state.payload.distinctiveTake?.takeIf { it.isNotBlank() }?.let { DistinctiveTakeCard(it) }
            state.payload.curiosities
                ?.filter { it.isNotBlank() }
                ?.takeIf { it.isNotEmpty() }
                ?.let { CuriositiesCard(it) }
            state.payload.discussionPrompts
                ?.filter { it.isNotBlank() }
                ?.takeIf { it.isNotEmpty() }
                ?.let { DiscussionPromptsCard(it) }
            if (state.sources.isNotEmpty()) SourcesFooter(state.sources)
        }
    }
}

@Composable
private fun LoadingCard() {
    QuireCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(stringResource(R.string.common_generating_insights), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    QuireCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
        }
    }
}

@Composable
private fun IntroCard(intro: String) {
    QuireCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(stringResource(R.string.common_about_this_book), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(intro, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun AuthorCard(a: AuthorInsight) {
    if (a.bio.isNullOrBlank() && a.notableWorks.isNullOrEmpty()) return
    QuireCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(stringResource(R.string.common_about_the_author), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            a.bio?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            a.notableWorks?.takeIf { it.isNotEmpty() }?.let {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.common_notable_works, it.joinToString(", ")), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SeriesCard(s: SeriesInsight) {
    QuireCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(stringResource(R.string.common_series), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            val header = s.position?.let {
                stringResource(R.string.bookdetail_series_book, s.name, it)
            } ?: s.name
            Text(header, style = MaterialTheme.typography.bodyMedium)
            s.context?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun AnalysisCard(analysis: String) {
    QuireCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(stringResource(R.string.common_themes_analysis), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(analysis, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ContentWarningsCard(warnings: List<String>) {
    QuireCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(stringResource(R.string.common_content_warnings), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(warnings.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------------------------------------------------------------------------
// PR-ε / schema v4 cards.
// ---------------------------------------------------------------------------

@Composable
private fun ThemeAnalysisCard(entries: List<Map.Entry<String, String>>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    QuireCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(stringResource(R.string.common_how_these_themes_show_up), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            val visible = if (expanded || entries.size <= 1) entries else entries.take(1)
            visible.forEachIndexed { index, entry ->
                if (index > 0) Spacer(Modifier.height(6.dp))
                Text(
                    entry.key.replace('_', ' ').replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(entry.value, style = MaterialTheme.typography.bodyMedium)
            }
            if (entries.size > 1) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) stringResource(R.string.common_show_less) else stringResource(R.string.common_more))
                }
            }
        }
    }
}

@Composable
private fun CraftCard(notes: String) {
    QuireCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(stringResource(R.string.common_craft), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(notes, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ComparativeAnchorsCard(anchors: List<ComparativeAnchor>) {
    // Display-only: no hyperlink, no "Open in browser". The server drops
    // titles Open Library cannot find, but keeps them unchecked when it
    // cannot reach Open Library.
    QuireCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(stringResource(R.string.common_if_you_liked), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            anchors.forEachIndexed { i, a ->
                if (i > 0) Spacer(Modifier.height(8.dp))
                Text("${a.book} — ${a.author}", style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.common_similar_in, a.similarIn), style = MaterialTheme.typography.bodySmall)
                a.differentIn?.takeIf { it.isNotBlank() }?.let {
                    Text(stringResource(R.string.common_different_in, it), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun DistinctiveTakeCard(take: String) {
    QuireCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(stringResource(R.string.common_what_sets_this_apart), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(take, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// Schema v5.
@Composable
private fun CuriositiesCard(curiosities: List<String>) {
    QuireCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(stringResource(R.string.common_curiosities), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            curiosities.forEachIndexed { i, c ->
                if (i > 0) Spacer(Modifier.height(4.dp))
                Text("• $c", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun DiscussionPromptsCard(prompts: List<String>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    QuireCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(stringResource(R.string.common_discussion_prompts), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            if (expanded) {
                prompts.forEachIndexed { i, q ->
                    if (i > 0) Spacer(Modifier.height(4.dp))
                    Text("${i + 1}. $q", style = MaterialTheme.typography.bodyMedium)
                }
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) stringResource(R.string.common_hide) else pluralStringResource(R.plurals.common_show_prompts, prompts.size, prompts.size))
            }
        }
    }
}

@Composable
private fun SourcesFooter(sources: List<Citation>) {
    val uriHandler = LocalUriHandler.current
    val labels = sources.mapNotNull { c ->
        when (c.kind) {
            "wikipedia" -> "Wikipedia" to c.url
            "openlibrary" -> "OpenLibrary" to c.url
            "model" -> stringResource(R.string.common_ai_model, c.title) to null
            "opf" -> stringResource(R.string.common_book_metadata) to null
            else -> c.title to c.url
        }
    }
    val text = buildAnnotatedString {
        append(stringResource(R.string.common_based_on))
        labels.forEachIndexed { i, (label, url) ->
            if (i > 0) append(" · ")
            if (url != null) {
                pushStringAnnotation("URL", url)
                withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
                    append(label)
                }
                pop()
            } else {
                append(label)
            }
        }
    }
    ClickableText(
        text = text,
        modifier = Modifier.padding(vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        onClick = { offset ->
            text.getStringAnnotations("URL", offset, offset).firstOrNull()?.let {
                uriHandler.openUri(it.item)
            }
        },
    )
}
