package io.theficos.ereader.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.theficos.ereader.reader.ReaderFontFamily
import io.theficos.ereader.reader.ReaderPreferences
import io.theficos.ereader.reader.ReaderTheme
import io.theficos.ereader.ui.components.RadioOptionRow
import io.theficos.ereader.ui.displayLabel
import io.theficos.quire.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FontSettingsSheet(
    prefs: ReaderPreferences,
    onChange: (ReaderPreferences) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            // Scrollable + nav-bar inset so every control stays reachable — the sheet is
            // taller than the screen once all sliders are shown, and the last item
            // (stringResource(R.string.common_full_screen_reading)) otherwise falls off the bottom / under the nav bar.
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.common_font_size, "%.1fx".format(prefs.fontScale)), style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = prefs.fontScale.toFloat(),
                onValueChange = { onChange(prefs.copy(fontScale = it.toDouble().coerceIn(0.5, 2.0))) },
                valueRange = 0.5f..2.0f,
                steps = 14,
                modifier = Modifier.fillMaxWidth(),
            )
            val advancedEnabled = !prefs.usePublisherStyles

            val lineSpacingLabel = stringResource(R.string.common_line_spacing, "%.1f".format(prefs.lineSpacing))
            Text(lineSpacingLabel, style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = prefs.lineSpacing.toFloat(),
                onValueChange = { onChange(prefs.copy(lineSpacing = it.toDouble().coerceIn(1.0, 1.8))) },
                valueRange = 1.0f..1.8f,
                steps = 7,
                enabled = advancedEnabled,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = lineSpacingLabel },
            )

            val marginLabel = stringResource(R.string.common_page_margins, "%.1f".format(prefs.pageMargins))
            Text(marginLabel, style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = prefs.pageMargins.toFloat(),
                onValueChange = { onChange(prefs.copy(pageMargins = it.toDouble().coerceIn(0.5, 2.0))) },
                valueRange = 0.5f..2.0f,
                steps = 14,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = marginLabel },
            )

            val indentLabel = prefs.paragraphIndent
                ?.let { stringResource(R.string.common_paragraph_indent, "%.1f".format(it)) } ?: stringResource(R.string.reader_paragraph_indent_default)
            Text(indentLabel, style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = (prefs.paragraphIndent ?: 0.0).toFloat(),
                onValueChange = { v ->
                    onChange(prefs.copy(paragraphIndent = v.toDouble().takeIf { it > 0.0 }?.coerceIn(0.0, 3.0)))
                },
                valueRange = 0.0f..3.0f,
                steps = 5,
                enabled = advancedEnabled,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = indentLabel },
            )

            val spacingLabel = prefs.paragraphSpacing
                ?.let { stringResource(R.string.common_paragraph_spacing, "%.1f".format(it)) } ?: stringResource(R.string.reader_paragraph_spacing_default)
            Text(spacingLabel, style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = (prefs.paragraphSpacing ?: 0.0).toFloat(),
                onValueChange = { v ->
                    onChange(prefs.copy(paragraphSpacing = v.toDouble().takeIf { it > 0.0 }?.coerceIn(0.0, 2.0)))
                },
                valueRange = 0.0f..2.0f,
                steps = 7,
                enabled = advancedEnabled,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = spacingLabel },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.reader_use_the_book_s_paragraph_styling), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.reader_keeps_the_book_s_own_line_spacing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = prefs.usePublisherStyles,
                    onCheckedChange = { onChange(prefs.copy(usePublisherStyles = it)) },
                )
            }

            Text(stringResource(R.string.reader_page_theme), style = MaterialTheme.typography.bodyMedium)
            // A column, not a row: four themes with labels as long as stringResource(R.string.reader_dark_sepia) overflow one
            // line on a narrow screen, and this matches the font-family picker just below.
            Column(Modifier.selectableGroup()) {
                ReaderTheme.values().forEach { t ->
                    RadioOptionRow(
                        label = t.displayLabel().asString(),
                        selected = prefs.theme == t,
                        onSelect = { onChange(prefs.copy(theme = t)) },
                    )
                }
            }
            Text(stringResource(R.string.common_font_family), style = MaterialTheme.typography.bodyMedium)
            Column(Modifier.selectableGroup()) {
                ReaderFontFamily.values().forEach { f ->
                    RadioOptionRow(
                        label = f.displayLabel(),
                        selected = prefs.fontFamily == f,
                        onSelect = { onChange(prefs.copy(fontFamily = f)) },
                    )
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.common_tap_to_turn_pages), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.reader_tap_left_or_right_edges_to_flip),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = prefs.tapNavigationEnabled,
                    onCheckedChange = { onChange(prefs.copy(tapNavigationEnabled = it)) },
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.common_full_screen_reading), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.reader_hide_the_status_and_navigation_bars_while),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = prefs.immersiveReading,
                    onCheckedChange = { onChange(prefs.copy(immersiveReading = it)) },
                )
            }
        }
    }
}
