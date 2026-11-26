package io.theficos.ereader.ui.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.theficos.ereader.core.model.Highlight
import io.theficos.ereader.core.model.HighlightColour
import io.theficos.ereader.reader.highlightTint
import io.theficos.ereader.ui.UiText
import io.theficos.quire.R

/** Presentation supplied in publication order; the quote is independent of its saved locator. */
internal data class HighlightListItem(
    val highlight: Highlight,
    val locationLabel: UiText? = null,
    val canJump: Boolean = true,
)

@Composable
internal fun HighlightsScreen(
    bookTitle: String,
    items: List<HighlightListItem>,
    isLoading: Boolean,
    jumpingId: String?,
    error: UiText?,
    onHighlightClick: (String) -> Unit,
    onBack: () -> Unit,
    onRetry: (() -> Unit)? = null,
    /** How many passages "Think it through" takes; null while the server does not offer it. */
    selectLimit: Int? = null,
    onThinkThrough: (List<Highlight>) -> Unit = {},
) {
    // Ids in the order they were picked; a highlight that goes away, or loses its text, drops out.
    var picked by rememberSaveable { mutableStateOf<List<String>?>(null) }
    val selectable = items.filter { it.highlight.text.isNotBlank() }.map { it.highlight.id }.toSet()
    val selection = if (selectLimit == null) null else picked?.filter { it in selectable }
    var atLimit by remember { mutableStateOf(false) }
    if (selection == null) atLimit = false
    BackHandler(enabled = selection != null) { picked = null }
    fun toggle(id: String) {
        val current = selection ?: return
        when {
            id in current -> { picked = current - id; atLimit = false }
            current.size >= selectLimit!! -> atLimit = true
            else -> picked = current + id
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selection != null) {
                    IconButton(onClick = { picked = null }) {
                        Icon(Icons.Filled.Close, stringResource(R.string.discuss_cancel_selection))
                    }
                    Text(stringResource(R.string.discuss_selected_count, selection.size),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).semantics { heading(); liveRegion = LiveRegionMode.Polite })
                    Button(
                        onClick = { onThinkThrough(items.filter { it.highlight.id in selection }.map { it.highlight }) },
                        enabled = selection.isNotEmpty(),
                    ) { Text(stringResource(R.string.discuss_title)) }
                } else {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.reader_highlights_title), style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.semantics { heading() })
                        Text(bookTitle, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (selectLimit != null && selectable.isNotEmpty()) {
                        TextButton(onClick = { picked = emptyList() }) { Text(stringResource(R.string.discuss_select)) }
                    }
                }
            }
            HorizontalDivider()
            if (atLimit && selectLimit != null) {
                Text(pluralStringResource(R.plurals.discuss_select_limit, selectLimit, selectLimit),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let { message ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(message.asString(), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            color = MaterialTheme.colorScheme.onErrorContainer)
                        if (onRetry != null) TextButton(onClick = onRetry, enabled = !isLoading && jumpingId == null) {
                            Text(stringResource(R.string.reader_highlights_retry))
                        }
                    }
                }
            }
            when {
                isLoading && items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.reader_highlights_loading))
                    }
                }
                items.isEmpty() && error == null -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.reader_highlights_empty), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.reader_highlights_empty_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items.isNotEmpty() -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(items, key = { it.highlight.id }) { item ->
                        val id = item.highlight.id
                        HighlightListCard(
                            item, jumpingId == id, isLoading || jumpingId != null, onHighlightClick,
                            selected = selection?.let { id in it },
                            selectable = id in selectable,
                            onToggle = { toggle(id) },
                            onLongPress = if (selectLimit != null && selection == null && id in selectable) {
                                { picked = listOf(id) }
                            } else null,
                        )
                    }
                }
                else -> Spacer(Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun HighlightListCard(
    item: HighlightListItem,
    opening: Boolean,
    busy: Boolean,
    onOpen: (String) -> Unit,
    /** Null outside selection mode. */
    selected: Boolean? = null,
    selectable: Boolean = false,
    onToggle: () -> Unit = {},
    /** Enters selection mode with this highlight picked; null when that is not offered. */
    onLongPress: (() -> Unit)? = null,
) {
    val highlight = item.highlight
    var expanded by rememberSaveable(highlight.id, highlight.text) { mutableStateOf(false) }
    var canExpand by remember(highlight.id, highlight.text) { mutableStateOf(false) }
    val action = when {
        selected != null -> Modifier.toggleable(value = selected, enabled = selectable, role = Role.Checkbox) { onToggle() }
        onLongPress != null -> Modifier.combinedClickable(
            enabled = !busy,
            role = Role.Button,
            onClickLabel = if (item.canJump) stringResource(R.string.reader_highlight_open) else null,
            onLongClickLabel = stringResource(R.string.discuss_select),
            onLongClick = onLongPress,
        ) { if (item.canJump) onOpen(highlight.id) }
        else -> Modifier.clickable(
            enabled = item.canJump && !busy,
            role = Role.Button,
            onClickLabel = stringResource(R.string.reader_highlight_open),
        ) { onOpen(highlight.id) }
    }
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().testTag("highlight-row-${highlight.id}").then(action),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selected != null) {
                    // The card is the toggle; the box only shows its state.
                    Checkbox(checked = selected, onCheckedChange = null, enabled = selectable,
                        modifier = Modifier.padding(end = 12.dp).clearAndSetSemantics { })
                }
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(14.dp).background(Color(highlight.colour.highlightTint()), CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), CircleShape))
                        Text(stringResource(highlight.colour.listLabelResource), style = MaterialTheme.typography.labelLarge)
                    }
                    item.locationLabel?.let {
                        Text(it.asString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (item.canJump && selected == null) Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            // Text selection would take the taps that pick passages.
            CopyableText(selected == null) {
                Text(highlight.text, style = MaterialTheme.typography.bodyLarge,
                    maxLines = if (expanded) Int.MAX_VALUE else 5,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (!expanded) canExpand = it.hasHiddenQuoteText() })
            }
            if (canExpand) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(stringResource(if (expanded) R.string.reader_highlight_show_less else R.string.reader_highlight_show_more))
                }
            }
            highlight.comment?.takeIf { it.isNotBlank() }?.let { comment ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.reader_highlight_comment), style = MaterialTheme.typography.labelLarge)
                    CopyableText(selected == null) { Text(comment, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            if (!item.canJump) {
                Text(stringResource(R.string.reader_highlight_location_unavailable),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (opening) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.reader_highlight_opening), style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
    }
}

@Composable
private fun CopyableText(copyable: Boolean, text: @Composable () -> Unit) {
    if (copyable) SelectionContainer { text() } else text()
}

private fun TextLayoutResult.hasHiddenQuoteText(): Boolean {
    // Wrapped text can be ellipsized even when hasVisualOverflow is false.
    if (hasVisualOverflow || (0 until lineCount).any { isLineEllipsized(it) }) return true
    if (lineCount == 0) return false
    val text = layoutInput.text.text
    val visibleEnd = getLineEnd(lineCount - 1, visibleEnd = true).coerceIn(0, text.length)
    return text.substring(visibleEnd).isNotBlank()
}

private val HighlightColour.listLabelResource: Int get() = when (this) {
    HighlightColour.YELLOW -> R.string.reader_highlight_yellow
    HighlightColour.GREEN -> R.string.reader_highlight_green
    HighlightColour.PINK -> R.string.reader_highlight_pink
}
