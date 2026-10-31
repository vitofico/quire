package io.theficos.ereader.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.theficos.ereader.data.local.db.MemoryBookmark
import io.theficos.quire.R

/** Deliberately opened from reader controls; never interrupts opening or rotating a book. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingPauseSheet(
    memory: MemoryBookmark?,
    canReturn: Boolean,
    canSave: Boolean,
    error: Boolean,
    stoppingPoint: StoppingPoint?,
    onSave: (String, Boolean) -> Unit,
    onReturn: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
    busy: Boolean = false,
    accountScope: String = "device-local",
) {
    var draft by rememberSaveable(accountScope, memory?.updatedAt, memory?.documentId, memory?.accountKey) {
        mutableStateOf(memory?.note.orEmpty())
    }
    // This scrollable form needs the available viewport, particularly at large font sizes.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.reader_pause_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.reader_stop_title), style = MaterialTheme.typography.titleMedium)
            Text(if (stoppingPoint == null) stringResource(R.string.reader_stop_unavailable) else {
                val label = when (stoppingPoint.boundary) {
                    StopBoundary.SECTION -> R.string.reader_stop_section
                    StopBoundary.RESOURCE -> R.string.reader_stop_resource
                    StopBoundary.END -> R.string.reader_stop_end
                }
                stringResource(label, kotlin.math.ceil(stoppingPoint.fractionOfBook * 100).toInt())
            })
            Text(stringResource(R.string.reader_stop_basis), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.reader_memory_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.reader_memory_local), style = MaterialTheme.typography.bodySmall)
            if (memory != null) {
                Text(stringResource(R.string.reader_memory_saved), style = MaterialTheme.typography.labelLarge)
                if (memory.passage.isNotBlank()) Text(memory.passage)
                else Text(stringResource(R.string.reader_memory_no_passage))
                if (!canReturn) Text(stringResource(R.string.reader_memory_unavailable))
                TextButton(onClick = onReturn, enabled = canReturn && !busy) {
                    Text(stringResource(R.string.reader_memory_return))
                }
            }
            Text(stringResource(R.string.reader_memory_note), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = draft, onValueChange = { draft = it.take(2000) },
                label = { Text(stringResource(R.string.reader_memory_note_label)) },
                enabled = !busy, minLines = 2, maxLines = 6, modifier = Modifier.fillMaxWidth())
            if (error) Text(stringResource(R.string.reader_memory_error), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { onSave(draft, false) }, enabled = canSave && !busy && (memory == null || canReturn)) {
                Text(stringResource(if (memory == null) R.string.reader_memory_save else R.string.reader_memory_edit))
            }
            if (memory != null) {
                TextButton(onClick = { onSave(draft, true) }, enabled = canSave && !busy) {
                    Text(stringResource(R.string.reader_memory_replace))
                }
                TextButton(onClick = onRemove, enabled = !busy) { Text(stringResource(R.string.reader_memory_remove)) }
            }
        }
    }
}
