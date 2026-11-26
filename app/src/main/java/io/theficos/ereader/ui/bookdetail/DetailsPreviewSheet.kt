package io.theficos.ereader.ui.bookdetail

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.theficos.ereader.core.model.Document
import io.theficos.ereader.data.ai.COVER_DRAW
import io.theficos.ereader.data.ai.COVER_FIND
import io.theficos.ereader.data.ai.COVER_KEEP
import io.theficos.ereader.data.ai.FIELD_AUTHORS
import io.theficos.ereader.data.ai.FIELD_DESCRIPTION
import io.theficos.ereader.data.ai.FIELD_SERIES
import io.theficos.ereader.data.ai.FIELD_TITLE
import io.theficos.ereader.data.ai.ORIGIN_OPEN_LIBRARY
import io.theficos.ereader.ui.components.BookDescription
import io.theficos.ereader.ui.components.CoverImage
import io.theficos.ereader.ui.components.RadioOptionRow
import io.theficos.quire.R

/**
 * What "Improve details" would change, each field ticked to keep beside what the book shows
 * now, and the cover to show. [shown] is the book as it shows now; its own cover is
 * [ownCover].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailsPreviewSheet(
    preview: DetailsPreview,
    shown: Document,
    ownCover: String?,
    onToggle: (String) -> Unit,
    onChooseCover: (String) -> Unit,
    onKeepCover: (Boolean) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
) {
    // While Apply runs the sheet stays: hiding it would reject what is being kept.
    val applying by rememberUpdatedState(preview.applying)
    ModalBottomSheet(
        onDismissRequest = { if (!applying) onCancel() },
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden || !applying },
        ),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            Text(
                stringResource(R.string.details_improve),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(if (preview.changes.isEmpty()) R.string.details_nothing_to_tidy else R.string.details_preview_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            preview.changes.forEach { change ->
                Spacer(Modifier.height(16.dp))
                Ticked(stringResource(fieldLabel(change.field)), change.field in preview.ticked) { onToggle(change.field) }
                Value(R.string.details_current) { FieldText(change.current, change.field, html = change.currentIsHtml) }
                Spacer(Modifier.height(8.dp))
                Value(R.string.details_proposed) { FieldText(change.proposed, change.field, html = false) }
            }
            if (preview.coverOptions.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.details_cover), style = MaterialTheme.typography.titleSmall)
                Column(Modifier.selectableGroup()) {
                    preview.coverOptions.forEach { option ->
                        RadioOptionRow(
                            label = stringResource(coverLabel(option, ownCover != null)),
                            selected = option == preview.choice,
                            onSelect = { onChooseCover(option) },
                        )
                        coverHint(option, ownCover != null)?.let { hint ->
                            Text(
                                stringResource(hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 48.dp, bottom = 4.dp),
                            )
                        }
                    }
                }
                Covers(preview, shown, ownCover)
            } else if (preview.newCover) {
                Spacer(Modifier.height(16.dp))
                Ticked(stringResource(R.string.details_cover), preview.keepCover) { onKeepCover(!preview.keepCover) }
                Covers(preview, shown, ownCover)
            }
            preview.failed?.let {
                Spacer(Modifier.height(12.dp))
                Text(it.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onCancel, enabled = !preview.applying) { Text(stringResource(R.string.common_cancel)) }
                Button(onClick = onApply, enabled = preview.canApply) { Text(stringResource(R.string.details_apply)) }
            }
        }
    }
}

@Composable
private fun Ticked(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() }),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.minimumInteractiveComponentSize())
        Text(label, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun Value(@StringRes label: Int, indent: Boolean = true, content: @Composable () -> Unit) {
    Column(Modifier.padding(start = if (indent) 48.dp else 0.dp)) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

@Composable
private fun FieldText(value: String, field: String, html: Boolean) {
    when {
        value.isBlank() -> Text(
            stringResource(R.string.details_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Collapsed with More and Less when it runs long; only the book's own may be HTML.
        field == FIELD_DESCRIPTION -> BookDescription(value, plainText = !html)
        else -> Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The cover shown now beside the one the choice gives. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Covers(preview: DetailsPreview, shown: Document, ownCover: String?) {
    Spacer(Modifier.height(8.dp))
    // Wraps to two lines when large text leaves no room for both side by side.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(start = 48.dp),
    ) {
        Value(R.string.details_current, indent = false) { Cover(shown.coverPath, shown) }
        Value(R.string.details_proposed, indent = false) {
            val load = preview.cover
            val cover = load?.preview
            when {
                load?.failed != null -> Text(
                    load.failed.asString(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(96.dp),
                )
                cover == null -> Box(Modifier.width(96.dp).aspectRatio(2f / 3f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                else -> {
                    // No image of its own: the book's cover, when it has one, else none.
                    Cover(cover.coverPath ?: ownCover.takeIf { cover.response.cover.url == null }, shown)
                    val note = when {
                        cover.coverPath == null -> null
                        cover.response.cover.generated -> R.string.details_generated_cover
                        cover.response.cover.source == ORIGIN_OPEN_LIBRARY -> R.string.details_from_open_library
                        else -> null
                    }
                    note?.let {
                        Text(stringResource(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun Cover(path: String?, shown: Document) {
    Box(Modifier.width(96.dp).aspectRatio(2f / 3f)) {
        CoverImage(source = path, title = shown.title, author = shown.author, modifier = Modifier.fillMaxSize())
    }
}

@StringRes
private fun fieldLabel(field: String): Int = when (field) {
    FIELD_TITLE -> R.string.common_title
    FIELD_AUTHORS -> R.string.details_authors
    FIELD_SERIES -> R.string.common_series
    else -> R.string.details_description
}

@StringRes
private fun coverLabel(option: String, own: Boolean): Int = when (option) {
    COVER_KEEP -> if (own) R.string.details_cover_keep else R.string.details_cover_find
    COVER_FIND -> R.string.details_cover_find
    COVER_DRAW -> R.string.details_cover_draw
    else -> R.string.details_cover_paint
}

@StringRes
private fun coverHint(option: String, own: Boolean): Int? = when (option) {
    COVER_KEEP -> if (own) null else R.string.details_cover_find_hint
    COVER_FIND -> R.string.details_cover_find_hint
    COVER_DRAW -> null
    else -> R.string.details_cover_paint_hint
}
