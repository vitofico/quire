package io.theficos.ereader.ui.components

import io.theficos.quire.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextOverflow

/**
 * A book's blurb, collapsed to six lines with More/Less when it runs longer. Catalogs and OPF
 * files often carry HTML here; it is rendered as styled text, never shown as tags.
 */
@Composable
fun BookDescription(raw: String?, modifier: Modifier = Modifier) {
    if (raw.isNullOrBlank()) return
    val description = remember(raw) {
        val text = if (Regex("<[a-zA-Z/!]").containsMatchIn(raw)) {
            // fromHtml ends a paragraph with a single line break, which runs paragraphs together;
            // a blank line between them is how a blurb is laid out on the page.
            AnnotatedString.fromHtml(raw.replace(Regex("</p>", RegexOption.IGNORE_CASE), "</p><br>"))
        } else {
            AnnotatedString(raw)
        }
        cleanDescription(text)
    }
    var expanded by rememberSaveable(raw) { mutableStateOf(false) }
    var overflows by rememberSaveable(raw) { mutableStateOf(false) }

    Column(modifier = modifier) {
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (expanded) Int.MAX_VALUE else 6,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded && it.hasVisualOverflow) overflows = true },
        )
        if (overflows) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) stringResource(R.string.common_less) else stringResource(R.string.common_more))
            }
        }
    }
}

/** Slices of the original keep HTML styles and links attached after text cleanup. */
internal fun cleanDescription(raw: AnnotatedString): AnnotatedString {
    val cleaned = buildAnnotatedString {
        var start = 0
        var newlines = 0
        raw.text.forEachIndexed { index, char ->
            if (char != '\uFFFC') newlines = if (char == '\n') newlines + 1 else 0
            if (char == '\uFFFC' || newlines > 2) {
                append(raw.subSequence(start, index))
                start = index + 1
            }
        }
        append(raw.subSequence(start, raw.length))
    }
    val start = cleaned.text.indexOfFirst { !it.isWhitespace() }
    if (start == -1) return AnnotatedString("")
    return cleaned.subSequence(start, cleaned.text.indexOfLast { !it.isWhitespace() } + 1)
}
