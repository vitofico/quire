package io.theficos.ereader.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.theficos.ereader.reader.ReaderFontFamily
import io.theficos.ereader.reader.ReaderPreferences
import io.theficos.ereader.reader.ReaderTheme

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
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Font size: ${"%.1fx".format(prefs.fontScale)}", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = prefs.fontScale.toFloat(),
                onValueChange = { onChange(prefs.copy(fontScale = it.toDouble().coerceIn(0.5, 2.0))) },
                valueRange = 0.5f..2.0f,
                steps = 14,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Line spacing: ${"%.1f".format(prefs.lineSpacing)}", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = prefs.lineSpacing.toFloat(),
                onValueChange = { onChange(prefs.copy(lineSpacing = it.toDouble().coerceIn(1.0, 1.8))) },
                valueRange = 1.0f..1.8f,
                steps = 7,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Theme", style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                ReaderTheme.values().forEach { t ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 16.dp),
                    ) {
                        RadioButton(
                            selected = prefs.theme == t,
                            onClick = { onChange(prefs.copy(theme = t)) },
                        )
                        Text(t.name.lowercase().replaceFirstChar { it.uppercase() })
                    }
                }
            }
            Text("Font family", style = MaterialTheme.typography.bodyMedium)
            Column {
                ReaderFontFamily.values().forEach { f ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 2.dp),
                    ) {
                        RadioButton(
                            selected = prefs.fontFamily == f,
                            onClick = { onChange(prefs.copy(fontFamily = f)) },
                        )
                        Text(f.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() })
                    }
                }
            }
        }
    }
}
