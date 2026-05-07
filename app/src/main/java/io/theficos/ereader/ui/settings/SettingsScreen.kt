package io.theficos.ereader.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.theficos.ereader.reader.ReaderFontFamily
import io.theficos.ereader.reader.ReaderTheme
import io.theficos.ereader.ui.components.QuireCard
import io.theficos.ereader.ui.components.SectionLabel

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    contentPadding: PaddingValues,
    onNavigateToLicenses: () -> Unit = {},
) {
    val calibre by viewModel.calibre.collectAsState()
    val reader by viewModel.readerPreferences.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.displaySmall)

        SectionLabel("calibre-web")
        QuireCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = calibre.baseUrl,
                    onValueChange = viewModel::onBaseUrlChange,
                    label = { Text("calibre-web URL") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = calibre.username,
                    onValueChange = viewModel::onUsernameChange,
                    label = { Text("Username") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = calibre.password,
                    onValueChange = viewModel::onPasswordChange,
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = viewModel::saveCalibre,
                    enabled = calibre.baseUrl.isNotBlank() && calibre.username.isNotBlank() && calibre.password.isNotBlank(),
                ) {
                    Text(if (calibre.saved) "Saved" else "Save")
                }
            }
        }

        SectionLabel("Reader defaults")
        QuireCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column {
                    Text("Font size: ${"%.1fx".format(reader.fontScale)}", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = reader.fontScale.toFloat(),
                        onValueChange = { viewModel.setFontScale(it.toDouble()) },
                        valueRange = 0.5f..2.0f,
                        steps = 14,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Column {
                    Text("Line spacing: ${"%.1f".format(reader.lineSpacing)}", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = reader.lineSpacing.toFloat(),
                        onValueChange = { viewModel.setLineSpacing(it.toDouble()) },
                        valueRange = 1.0f..1.8f,
                        steps = 7,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Column {
                    Text("Theme", style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ReaderTheme.values().forEach { t ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(end = 16.dp),
                            ) {
                                RadioButton(selected = reader.theme == t, onClick = { viewModel.setTheme(t) })
                                Text(t.name.lowercase().replaceFirstChar { it.uppercase() })
                            }
                        }
                    }
                }
                Column {
                    Text("Font family", style = MaterialTheme.typography.bodyMedium)
                    Column {
                        ReaderFontFamily.values().forEach { f ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 2.dp),
                            ) {
                                RadioButton(selected = reader.fontFamily == f, onClick = { viewModel.setFontFamily(f) })
                                Text(f.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() })
                            }
                        }
                    }
                }
            }
        }

        SectionLabel("Sync")
        QuireCard(modifier = Modifier.fillMaxWidth()) {
            val syncState by viewModel.sync.collectAsState()
            val context = LocalContext.current

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!syncState.hasCredentials) {
                    Text(
                        "Configure calibre-web above to enable sync.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val ts = syncState.lastSyncedAtMs
                    Text(
                        if (ts == null) "Not synced yet" else "Last synced: ${formatRelative(ts)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Button(
                    onClick = { viewModel.syncNow(context) },
                    enabled = syncState.hasCredentials,
                ) { Text("Sync now") }
            }
        }

        SectionLabel("Storage & sync")
        QuireCard(modifier = Modifier.fillMaxWidth()) {
            val context = LocalContext.current
            var pendingResetSync by remember { mutableStateOf(false) }
            var pendingRemoveAll by remember { mutableStateOf(false) }

            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column {
                    Text("Reset sync", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Re-pull everything on the next sync. Your books and progress are kept.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { pendingResetSync = true }) { Text("Reset sync") }
                }
                Column {
                    Text("Remove all downloaded books", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Delete all EPUB files from this device. Reading progress is preserved on the server.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = { pendingRemoveAll = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Remove all downloaded books") }
                }
            }

            if (pendingResetSync) {
                AlertDialog(
                    onDismissRequest = { pendingResetSync = false },
                    title = { Text("Reset sync?") },
                    text = { Text("Next sync will re-pull everything from the server. Local books and progress are kept.") },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.resetSync(context)
                            pendingResetSync = false
                        }) { Text("Reset") }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingResetSync = false }) { Text("Cancel") }
                    },
                )
            }
            if (pendingRemoveAll) {
                AlertDialog(
                    onDismissRequest = { pendingRemoveAll = false },
                    title = { Text("Remove all downloaded books?") },
                    text = { Text("Delete all downloaded books from this device? Reading progress is preserved on the server and will sync back if you re-download.") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.removeAllBooks()
                                pendingRemoveAll = false
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { Text("Remove all") }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingRemoveAll = false }) { Text("Cancel") }
                    },
                )
            }
        }

        SectionLabel("About")
        QuireCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column {
                    Text("Quire", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "A reader for your shelf.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "Open-source licenses",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onNavigateToLicenses)
                        .padding(vertical = 8.dp),
                )
            }
        }
    }
}

private fun formatRelative(epochMs: Long): String {
    val deltaSec = (System.currentTimeMillis() - epochMs) / 1000
    return when {
        deltaSec < 60 -> "just now"
        deltaSec < 3600 -> "${deltaSec / 60}m ago"
        deltaSec < 86_400 -> "${deltaSec / 3600}h ago"
        else -> "${deltaSec / 86_400}d ago"
    }
}
