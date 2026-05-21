package io.theficos.ereader.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import io.theficos.ereader.data.ai.AiHealthResponse
import io.theficos.ereader.data.ai.RetrievalSourceHealth
import io.theficos.ereader.reader.ReaderFontFamily
import io.theficos.ereader.reader.ReaderTheme
import io.theficos.ereader.ui.components.QuireCard
import io.theficos.ereader.ui.components.SectionLabel
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Curated languages shown in the AI insight language dropdown. The server
 * accepts any ISO 639-1 code; this list is the UI surface. Labels are the
 * native names of each language so users in the target locale recognize
 * them even before the app itself is localized.
 */
private val LANGUAGE_OPTIONS: List<Pair<String, String>> = listOf(
    "auto" to "Auto",
    "en" to "English",
    "it" to "Italiano",
    "es" to "Español",
    "fr" to "Français",
    "de" to "Deutsch",
    "pt" to "Português",
    "nl" to "Nederlands",
)

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    contentPadding: PaddingValues,
    onNavigateToLicenses: () -> Unit = {},
) {
    val calibre by viewModel.calibre.collectAsState()
    val reader by viewModel.readerPreferences.collectAsState()
    val aiState by viewModel.ai.collectAsState()
    val deleteInFlight by viewModel.deleteProfileInFlight.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val msg = when (event) {
                SettingsEvent.ProfileDeleted -> "Reader profile deleted."
                is SettingsEvent.ProfileDeleteFailed ->
                    "Couldn't delete reader profile: ${event.message}"
            }
            snackbarHostState.showSnackbar(msg)
        }
    }

    Box(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
    Column(
        modifier = Modifier
            .fillMaxSize()
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

        SectionLabel("AI features")
        QuireCard(modifier = Modifier.fillMaxWidth()) {
            if (aiState.config?.configured != true) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("AI not configured", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Your administrator has not configured an AI endpoint on this server.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val cfg = aiState.config!!
                val enabled = aiState.preferences?.aiEnabled == true
                val health = aiState.health
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (health != null) {
                        AiHealthStatusBlock(health)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                            Text(
                                "Enable AI features for this account",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "When enabled, Quire sends the title, author, and other " +
                                    "EPUB metadata of books you open to ${cfg.baseUrlHost ?: "the AI endpoint"} " +
                                    "(model ${cfg.modelId ?: "unknown"}) to generate insights.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (cfg.sourcesEnabled.isNotEmpty()) {
                                Text(
                                    "External sources used: ${cfg.sourcesEnabled.joinToString(", ")}.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                "Nothing is sent until you opt in.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = { viewModel.toggleAi(it) },
                        )
                    }

                    if (enabled) {
                        val tone = aiState.preferences?.style?.tone ?: "neutral"
                        var menuOpen by remember { mutableStateOf(false) }
                        Column {
                            Text("Insight tone", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "How book insights are written.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = { menuOpen = true }) {
                                Text(tone.replaceFirstChar { it.uppercase() })
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                            ) {
                                listOf("neutral", "enthusiastic", "scholarly", "casual").forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.replaceFirstChar { it.uppercase() }) },
                                        onClick = {
                                            viewModel.setStyleTone(option)
                                            menuOpen = false
                                        },
                                    )
                                }
                            }
                        }

                        // PR-η: user-initiated bulk sync of /ai/v1/insights/sync.
                        Column {
                            Text("Refresh insights", style = MaterialTheme.typography.titleMedium)
                            Text(
                                buildString {
                                    val last = aiState.lastInsightSyncMs
                                    if (last != null) {
                                        append("Last synced ")
                                        append(formatRelative(last))
                                        append('.')
                                    } else {
                                        append("Not yet synced.")
                                    }
                                    when (val s = aiState.syncStatus) {
                                        is InsightSyncStatus.Syncing -> append(" Syncing…")
                                        is InsightSyncStatus.Error -> {
                                            append(" Last attempt failed: ")
                                            append(s.message)
                                        }
                                        InsightSyncStatus.Idle -> Unit
                                    }
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = { viewModel.refreshInsights() },
                                enabled = aiState.syncStatus !is InsightSyncStatus.Syncing,
                            ) { Text("Refresh insights") }
                        }

                        val language = aiState.preferences?.style?.language ?: "auto"
                        var langMenuOpen by remember { mutableStateOf(false) }
                        Column {
                            Text("Insight language", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Language the model writes insights in. " +
                                    "Auto follows the model's default.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = { langMenuOpen = true }) {
                                Text(
                                    LANGUAGE_OPTIONS.firstOrNull { it.first == language }?.second
                                        ?: language,
                                )
                            }
                            DropdownMenu(
                                expanded = langMenuOpen,
                                onDismissRequest = { langMenuOpen = false },
                            ) {
                                LANGUAGE_OPTIONS.forEach { (code, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            viewModel.setStyleLanguage(code)
                                            langMenuOpen = false
                                        },
                                    )
                                }
                            }
                        }

                        // PR-δ: delete server-side reader profile.
                        Column {
                            Text(
                                "Reader profile",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "Removes the AI-generated profile from the server. " +
                                    "A new one is built next time you refresh insights.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = { viewModel.deleteReaderProfile() },
                                enabled = !deleteInFlight,
                            ) {
                                Text(
                                    if (deleteInFlight) "Deleting…" else "Delete reader profile",
                                )
                            }
                        }
                    }
                }
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
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
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

/** Parse an ISO-8601 timestamp into epoch millis, returning null on failure. */
private fun parseIsoOrNull(iso: String?): Long? {
    if (iso == null) return null
    return try {
        Instant.parse(iso).toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }
}

@Composable
private fun AiHealthStatusBlock(health: AiHealthResponse) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Status", style = MaterialTheme.typography.titleMedium)

        // Provider row.
        val providerCheckedMs = parseIsoOrNull(health.providerLastCheckedAt)
        val providerFailureMs = parseIsoOrNull(health.lastFailureAt)
        val (providerText, providerColor) = when (health.providerReachable) {
            null -> "Provider: not yet checked" to MaterialTheme.colorScheme.onSurfaceVariant
            true -> {
                val modelSuffix = health.modelId?.let { " (model: $it)" } ?: ""
                val whenSuffix = providerCheckedMs?.let { ", checked ${formatRelative(it)}" } ?: ""
                "Provider: reachable$modelSuffix$whenSuffix" to MaterialTheme.colorScheme.onSurface
            }
            false -> {
                val errClass = health.lastFailureClass ?: "unknown error"
                val whenSuffix = providerFailureMs?.let { ", ${formatRelative(it)}" } ?: ""
                "Provider: unreachable — $errClass$whenSuffix" to MaterialTheme.colorScheme.error
            }
        }
        Text(
            providerText,
            style = MaterialTheme.typography.bodyMedium,
            color = providerColor,
        )

        // Retrieval sources, one row each.
        health.retrievalSources.forEach { source ->
            val (text, color) = formatRetrievalSource(source)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
        }
    }
}

@Composable
private fun formatRetrievalSource(source: RetrievalSourceHealth): Pair<String, androidx.compose.ui.graphics.Color> {
    val label = source.name.replaceFirstChar { it.uppercase() }
    val checkedMs = parseIsoOrNull(source.lastCheckedAt)
    return when (source.reachable) {
        null -> "$label: not yet checked" to MaterialTheme.colorScheme.onSurfaceVariant
        true -> {
            val whenSuffix = checkedMs?.let { ", checked ${formatRelative(it)}" } ?: ""
            "$label: reachable$whenSuffix" to MaterialTheme.colorScheme.onSurface
        }
        false -> {
            val whenSuffix = checkedMs?.let { ", ${formatRelative(it)}" } ?: ""
            "$label: unreachable$whenSuffix" to MaterialTheme.colorScheme.error
        }
    }
}
