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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.theficos.ereader.data.ai.AuthorInsight
import io.theficos.ereader.data.ai.Citation
import io.theficos.ereader.data.ai.SeriesInsight
import io.theficos.ereader.ui.components.QuireCard

@Composable
fun InsightSection(state: InsightUiState, onRetry: () -> Unit) {
    when (state) {
        InsightUiState.Hidden -> Unit
        InsightUiState.Loading -> LoadingCard()
        is InsightUiState.Error -> ErrorCard(state.message, onRetry)
        is InsightUiState.Loaded -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.payload.intro?.takeIf { it.isNotBlank() }?.let { IntroCard(it) }
            state.payload.author?.let { AuthorCard(it) }
            state.payload.series?.let { SeriesCard(it) }
            state.payload.analysis?.takeIf { it.isNotBlank() }?.let { AnalysisCard(it) }
            state.payload.contentWarnings?.takeIf { it.isNotEmpty() }?.let { ContentWarningsCard(it) }
            if (state.sources.isNotEmpty()) SourcesFooter(state.sources)
        }
    }
}

@Composable
private fun LoadingCard() {
    QuireCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Column {
            Text("Generating insights…", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    QuireCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Column {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun IntroCard(intro: String) {
    QuireCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Column {
            Text("About this book", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(intro, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun AuthorCard(a: AuthorInsight) {
    if (a.bio.isNullOrBlank() && a.notableWorks.isNullOrEmpty()) return
    QuireCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Column {
            Text("About the author", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            a.bio?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            a.notableWorks?.takeIf { it.isNotEmpty() }?.let {
                Spacer(Modifier.height(6.dp))
                Text("Notable works: ${it.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SeriesCard(s: SeriesInsight) {
    QuireCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Column {
            Text("Series", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            val header = buildString {
                append(s.name)
                s.position?.let { append(" — book $it") }
            }
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
    QuireCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Column {
            Text("Themes & analysis", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(analysis, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ContentWarningsCard(warnings: List<String>) {
    QuireCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Column {
            Text("Content warnings", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(warnings.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
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
            "model" -> "AI model: ${c.title}" to null
            "opf" -> "Book metadata" to null
            else -> c.title to c.url
        }
    }
    val text = buildAnnotatedString {
        append("Based on: ")
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
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        onClick = { offset ->
            text.getStringAnnotations("URL", offset, offset).firstOrNull()?.let {
                uriHandler.openUri(it.item)
            }
        },
    )
}
