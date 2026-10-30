package io.theficos.ereader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.theficos.ereader.core.model.HighlightColour
import io.theficos.ereader.reader.highlightTint
import io.theficos.ereader.ui.UiText
import io.theficos.quire.R

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun HighlightEditorSheet(
    editor: HighlightEditor,
    busy: Boolean,
    error: UiText?,
    onColour: (HighlightColour) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val currentBusy by rememberUpdatedState(busy)
    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { !currentBusy || it != SheetValue.Hidden },
        ),
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(if (editor is HighlightEditor.Existing) R.string.reader_edit_highlight else R.string.reader_highlight_passage),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(editor.text, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HighlightColour.entries.forEach { colour ->
                    FilterChip(
                        selected = (editor as? HighlightEditor.Existing)?.highlight?.colour == colour,
                        onClick = { onColour(colour) },
                        enabled = !busy,
                        label = { Text(stringResource(colour.labelResource)) },
                        leadingIcon = {
                            Box(Modifier.size(16.dp).background(Color(colour.highlightTint()), CircleShape)
                                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), CircleShape))
                        },
                    )
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let {
                Text(it.asString(), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.common_cancel)) }
                if (editor is HighlightEditor.Existing) {
                    TextButton(onClick = onRemove, enabled = !busy) {
                        Text(stringResource(R.string.reader_remove_highlight), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

private val HighlightColour.labelResource: Int get() = when (this) {
    HighlightColour.YELLOW -> R.string.reader_highlight_yellow
    HighlightColour.GREEN -> R.string.reader_highlight_green
    HighlightColour.PINK -> R.string.reader_highlight_pink
}
