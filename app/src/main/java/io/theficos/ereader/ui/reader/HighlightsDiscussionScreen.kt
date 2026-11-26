package io.theficos.ereader.ui.reader

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.theficos.ereader.data.ai.DiscussLimits
import io.theficos.ereader.data.ai.DiscussPassage
import io.theficos.ereader.data.ai.codePointLength
import io.theficos.ereader.data.ai.excerpt
import io.theficos.ereader.ui.UiText
import io.theficos.ereader.ui.uiText
import io.theficos.quire.R
import kotlinx.coroutines.launch

@Composable
internal fun HighlightsDiscussionScreen(viewModel: HighlightsDiscussionViewModel) {
    val state by viewModel.state.collectAsState()
    val limits by viewModel.limits.collectAsState()
    val context = LocalContext.current
    val heading = stringResource(R.string.discuss_passages_heading)
    val title = stringResource(R.string.discuss_title)
    var notice by remember { mutableStateOf<UiText?>(null) }
    HighlightsDiscussionContent(
        state = state,
        limits = limits,
        notice = notice,
        onClose = viewModel::close,
        onDraft = viewModel::setDraft,
        onAsk = { notice = null; viewModel.ask(it) },
        onIncludeNotes = viewModel::setIncludeNotes,
        onCancel = viewModel::cancel,
        onStartAgain = { notice = null; viewModel.startAgain() },
        onShare = {
            val note = viewModel.shareNote(heading) ?: return@HighlightsDiscussionContent
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, note)
            notice = try {
                context.startActivity(Intent.createChooser(send, null))
                null
            } catch (_: ActivityNotFoundException) {
                uiText(R.string.discuss_share_failed)
            }
        },
        onCopy = {
            val note = viewModel.shareNote(heading) ?: return@HighlightsDiscussionContent
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            notice = if (clipboard == null) uiText(R.string.discuss_share_failed) else {
                clipboard.setPrimaryClip(ClipData.newPlainText(title, note))
                // Android 13 and later confirm a copy themselves.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) uiText(R.string.discuss_copied) else null
            }
        },
    )
}

/** Rows above the passages in the list, so a cited number can scroll to its passage. */
private const val ROWS_BEFORE_PASSAGES = 2

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HighlightsDiscussionContent(
    state: DiscussionState,
    limits: DiscussLimits?,
    notice: UiText?,
    onClose: () -> Unit,
    onDraft: (String) -> Unit,
    onAsk: (String) -> Unit,
    onIncludeNotes: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onStartAgain: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var expanded by rememberSaveable { mutableStateOf(emptyList<Int>()) }
    var flash by remember { mutableStateOf<Int?>(null) }
    var flashes by remember { mutableIntStateOf(0) }
    val asking = state.asking != null
    val latest = state.exchanges.lastOrNull()
    val show: (Int) -> Unit = { n ->
        val index = state.passages.indexOfFirst { it.n == n }
        if (index >= 0) {
            if (n !in expanded) expanded = expanded + n
            flash = n
            flashes++
            scope.launch { list.animateScrollToItem(ROWS_BEFORE_PASSAGES + index) }
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                }
                Text(stringResource(R.string.discuss_title), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() })
            }
            HorizontalDivider()
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize().testTag("discuss-list"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(stringResource(R.string.discuss_nothing_saved), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    Text(stringResource(R.string.discuss_selected_passages), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() })
                }
                itemsIndexed(state.passages, key = { _, p -> "passage-${p.n}" }) { _, passage ->
                    PassageRow(
                        passage = passage,
                        expanded = passage.n in expanded,
                        flash = if (flash == passage.n) flashes else 0,
                        onToggle = { expanded = if (passage.n in expanded) expanded - passage.n else expanded + passage.n },
                    )
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.clipped) {
                            Text(stringResource(R.string.discuss_clipped), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (state.hasNotes) {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                                    value = state.includeNotes,
                                    enabled = !state.notesLocked,
                                    role = Role.Checkbox,
                                    onValueChange = onIncludeNotes,
                                ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = state.includeNotes, onCheckedChange = null, enabled = !state.notesLocked)
                                Text(stringResource(R.string.discuss_include_notes), Modifier.padding(start = 12.dp))
                            }
                        }
                        Text(
                            stringResource(
                                if (state.hasNotes && state.includeNotes) R.string.discuss_privacy_notes else R.string.discuss_privacy,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                itemsIndexed(state.exchanges) { index, exchange ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HorizontalDivider()
                        Text(exchange.question, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        // Plain text only: never Markdown, HTML or links, whatever the model wrote.
                        SelectionContainer {
                            Text(
                                exchange.answer,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.testTag("discuss-answer-$index").then(
                                    if (index == state.exchanges.lastIndex) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
                                ),
                            )
                        }
                        if (exchange.cited.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text(stringResource(R.string.discuss_based_on), style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.align(Alignment.CenterVertically))
                                exchange.cited.forEach { n ->
                                    val passage = state.passages.firstOrNull { it.n == n } ?: return@forEach
                                    val description = stringResource(R.string.discuss_passage_excerpt, n, passage.text.excerpt(60))
                                    AssistChip(
                                        onClick = { show(n) },
                                        label = { Text("$n") },
                                        modifier = Modifier.semantics { contentDescription = description },
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (latest != null) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = onShare) { Text(stringResource(R.string.discuss_share)) }
                                OutlinedButton(onClick = onCopy) { Text(stringResource(R.string.discuss_copy)) }
                            }
                        }
                        if (asking) {
                            Row(
                                Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CircularProgressIndicator(Modifier.sizeIn(maxWidth = 24.dp, maxHeight = 24.dp), strokeWidth = 2.dp)
                                Text(stringResource(R.string.discuss_thinking), Modifier.weight(1f))
                                TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
                            }
                        }
                        (state.error ?: notice)?.let { message ->
                            Text(
                                message.asString(),
                                color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                        when {
                            limits == null -> if (state.error == null) Text(stringResource(R.string.discuss_not_offered))
                            state.questionsLeft(limits) == 0 -> {
                                Text(stringResource(R.string.discuss_limit_reached))
                                Button(onClick = onStartAgain) { Text(stringResource(R.string.discuss_start_again)) }
                            }
                            else -> AskForm(state, limits, onDraft, onAsk)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AskForm(state: DiscussionState, limits: DiscussLimits, onDraft: (String) -> Unit, onAsk: (String) -> Unit) {
    val asking = state.asking != null
    val latest = state.exchanges.lastOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (latest == null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(R.string.discuss_starter_connects, R.string.discuss_starter_disagree, R.string.discuss_starter_note).forEach { id ->
                    val starter = stringResource(id)
                    AssistChip(onClick = { onAsk(starter) }, label = { Text(starter) }, enabled = !asking)
                }
            }
        }
        OutlinedTextField(
            value = state.draft,
            onValueChange = { if (it.codePointLength() <= limits.maxQuestionChars) onDraft(it) },
            label = { Text(stringResource(if (latest == null) R.string.discuss_your_question else R.string.discuss_follow_up)) },
            enabled = !asking,
            minLines = 2,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onAsk(state.draft) }),
            modifier = Modifier.fillMaxWidth().testTag("discuss-question"),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { onAsk(state.draft) }, enabled = !asking && state.draft.isNotBlank()) {
                Text(stringResource(R.string.discuss_ask))
            }
            if (latest != null) {
                Text(stringResource(R.string.discuss_questions_left, state.questionsLeft(limits)),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PassageRow(passage: DiscussPassage, expanded: Boolean, flash: Int, onToggle: () -> Unit) {
    val glow = remember { Animatable(0f) }
    LaunchedEffect(flash) {
        if (flash == 0) return@LaunchedEffect
        glow.snapTo(1f)
        glow.animateTo(0f, tween(durationMillis = 1200))
    }
    val tint = MaterialTheme.colorScheme.primaryContainer.copy(alpha = glow.value)
    Row(
        Modifier.fillMaxWidth().testTag("discuss-passage-${passage.n}")
            .background(tint, MaterialTheme.shapes.medium)
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(if (expanded) R.string.reader_highlight_show_less else R.string.reader_highlight_show_more),
                onClick = onToggle,
            )
            .heightIn(min = 48.dp)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val label = stringResource(R.string.discuss_passage, passage.n)
        Box(
            Modifier.sizeIn(minWidth = 28.dp, minHeight = 28.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                .clearAndSetSemantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Text("${passage.n}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(passage.text, style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
            if (expanded) {
                passage.note?.let {
                    Text(stringResource(R.string.reader_highlight_comment), style = MaterialTheme.typography.labelLarge)
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }
            val source = listOfNotNull(passage.title, passage.authors.joinToString(", ").takeIf { it.isNotEmpty() }).joinToString(", ")
            if (source.isNotEmpty()) {
                Text(source, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
