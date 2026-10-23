package io.theficos.ereader.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.theficos.ereader.data.ai.AiHealthResponse
import io.theficos.ereader.data.ai.RetrievalSourceHealth
import io.theficos.ereader.reader.ReaderFontFamily
import io.theficos.ereader.reader.ReaderTheme
import io.theficos.ereader.ui.components.QuireCard
import io.theficos.ereader.ui.components.RadioOptionRow
import io.theficos.ereader.ui.components.SectionLabel
import io.theficos.ereader.ui.displayLabel
import io.theficos.ereader.ui.reader.displayLabel
import io.theficos.ereader.ui.theme.AppTheme
import io.theficos.ereader.ui.theme.Palette
import io.theficos.ereader.ui.theme.ThemeMode
import io.theficos.ereader.ui.toneLabel
import io.theficos.quire.R
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Curated languages shown in the AI insight language dropdown. The server
 * accepts ANY ISO 639-1 code, and `auto` now writes each insight in the
 * book's own language (server `_resolve_response_language`), so this list only
 * needs to cover users who want to PIN a preferred language that differs from
 * the book. It is intentionally a curated subset of the most widely-spoken
 * languages rather than the full ISO 639-1 set — a plain, non-searchable
 * dropdown stops being usable much past ~20 entries. Every code here is also
 * one the server's auto-resolver understands, so explicit and auto stay
 * consistent. Labels are the native names so users in the target locale
 * recognize them even before the app itself is localized.
 */
private val LANGUAGE_OPTIONS: List<Pair<String, String>> = listOf(
    "auto" to "Auto",
    "en" to "English",
    "zh" to "中文",
    "hi" to "हिन्दी",
    "es" to "Español",
    "ar" to "العربية",
    "fr" to "Français",
    "pt" to "Português",
    "ru" to "Русский",
    "ja" to "日本語",
    "de" to "Deutsch",
    "ko" to "한국어",
    "it" to "Italiano",
    "tr" to "Türkçe",
    "pl" to "Polski",
    "nl" to "Nederlands",
    "uk" to "Українська",
    "sv" to "Svenska",
    "vi" to "Tiếng Việt",
    "id" to "Bahasa Indonesia",
)

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    contentPadding: PaddingValues,
    onNavigateToLicenses: () -> Unit = {},
) {
    val calibre by viewModel.calibre.collectAsState()
    val opds by viewModel.opds.collectAsState()
    val reader by viewModel.readerPreferences.collectAsState()
    val appTheme by viewModel.appTheme.collectAsState()
    val aiState by viewModel.ai.collectAsState()
    val deleteInFlight by viewModel.deleteProfileInFlight.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val restoreRunning by viewModel.restoreRunning.collectAsState()
    val restoreProgress by viewModel.restoreProgress.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val msg = when (event) {
                SettingsEvent.ProfileDeleted -> context.getString(R.string.common_reader_profile_deleted)
                is SettingsEvent.ProfileDeleteFailed ->
                    context.getString(R.string.settings_couldn_t_delete_reader_profile, event.message.asString(context))
                is SettingsEvent.RestoreFinished -> {
                    val s = event.summary
                    if (s.failed > 0) context.getString(R.string.restore_partial, s.downloaded, s.requested, s.failed)
                    else context.getString(R.string.restore_complete, s.downloaded, s.requested)
                }
                is SettingsEvent.RestoreFailed -> context.getString(R.string.common_restore_failed, event.message)
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
        Text(stringResource(R.string.common_settings), style = MaterialTheme.typography.displaySmall)

        SectionLabel(stringResource(R.string.common_appearance))
        QuireCard(modifier = Modifier.fillMaxWidth()) {
            AppearanceSettings(theme = appTheme, onChange = viewModel::setAppTheme)
        }

        SectionLabel("calibre-web")
        QuireCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = calibre.baseUrl,
                    onValueChange = viewModel::onBaseUrlChange,
                    label = { Text(stringResource(R.string.common_calibre_web_url)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = calibre.username,
                    onValueChange = viewModel::onUsernameChange,
                    label = { Text(stringResource(R.string.common_username)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = calibre.password,
                    onValueChange = viewModel::onPasswordChange,
                    label = { Text(stringResource(R.string.common_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = calibre.quireServerUrl,
                    onValueChange = viewModel::onQuireServerUrlChange,
                    label = { Text(stringResource(R.string.settings_quire_server_url_optional)) },
                    placeholder = { Text(stringResource(R.string.common_same_as_calibre_web_url)) },
                    supportingText = {
                        Text(stringResource(R.string.settings_only_set_this_if_sync_ai_run))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = viewModel::saveCalibre,
                    enabled = calibre.baseUrl.isNotBlank() && calibre.username.isNotBlank() && calibre.password.isNotBlank(),
                ) {
                    Text(if (calibre.saved) stringResource(R.string.common_saved) else stringResource(R.string.common_save))
                }
            }
        }

        SectionLabel(stringResource(R.string.common_opds_catalog))
        QuireCard(modifier = Modifier.fillMaxWidth()) {
            var revealUrl by rememberSaveable { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.settings_connect_to_any_opds_catalog_such_as),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = opds.catalogUrl,
                    onValueChange = viewModel::onOpdsCatalogUrlChange,
                    label = { Text(stringResource(R.string.common_catalog_url)) },
                    isError = opds.error != null,
                    supportingText = opds.error?.let { msg -> { Text(msg.asString()) } },
                    // Some catalogs put an account key in the URL, so it is
                    // masked like a password until the user asks to see it.
                    visualTransformation = if (revealUrl) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(onClick = { revealUrl = !revealUrl }) {
                            Text(if (revealUrl) stringResource(R.string.common_hide) else stringResource(R.string.common_show))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = opds.username,
                    onValueChange = viewModel::onOpdsUsernameChange,
                    label = { Text(stringResource(R.string.common_username_optional)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = opds.password,
                    onValueChange = viewModel::onOpdsPasswordChange,
                    label = { Text(stringResource(R.string.common_password_optional)) },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (opds.isActive) {
                    Text(
                        stringResource(R.string.settings_connected_to_an_opds_catalog_your_place),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = viewModel::saveOpds,
                    // Saving REPLACES the stored account, so a typo here would
                    // wipe a working calibre-web password. Require a scheme
                    // before the button is even tappable (issue #101).
                    enabled = opds.catalogUrl.isNotBlank() &&
                        (opds.catalogUrl.startsWith("http://", ignoreCase = true) ||
                            opds.catalogUrl.startsWith("https://", ignoreCase = true)) &&
                        opds.username.isBlank() == opds.password.isBlank(),
                ) {
                    Text(if (opds.saved) stringResource(R.string.common_saved) else stringResource(R.string.common_save))
                }
            }
        }

        SectionLabel(stringResource(R.string.common_reader_defaults))
        QuireCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column {
                    Text(stringResource(R.string.common_font_size, "%.1fx".format(reader.fontScale)), style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = reader.fontScale.toFloat(),
                        onValueChange = { viewModel.setFontScale(it.toDouble()) },
                        valueRange = 0.5f..2.0f,
                        steps = 14,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Column {
                    Text(stringResource(R.string.common_line_spacing, "%.1f".format(reader.lineSpacing)), style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = reader.lineSpacing.toFloat(),
                        onValueChange = { viewModel.setLineSpacing(it.toDouble()) },
                        valueRange = 1.0f..1.8f,
                        steps = 7,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Column {
                    Text(stringResource(R.string.reader_page_theme), style = MaterialTheme.typography.bodyMedium)
                    // A column, not a row: four themes with labels as long as stringResource(R.string.reader_dark_sepia)
                    // overflow one line on a narrow screen, and this matches the font-family
                    // picker just below.
                    Column(Modifier.selectableGroup()) {
                        ReaderTheme.values().forEach { t ->
                            RadioOptionRow(
                                label = t.displayLabel().asString(),
                                selected = reader.theme == t,
                                onSelect = { viewModel.setTheme(t) },
                            )
                        }
                    }
                }
                Column {
                    Text(stringResource(R.string.common_font_family), style = MaterialTheme.typography.bodyMedium)
                    Column(Modifier.selectableGroup()) {
                        ReaderFontFamily.values().forEach { f ->
                            RadioOptionRow(
                                label = f.displayLabel(),
                                selected = reader.fontFamily == f,
                                onSelect = { viewModel.setFontFamily(f) },
                            )
                        }
                    }
                }
            }
        }

        SectionLabel(stringResource(R.string.common_sync))
        QuireCard(modifier = Modifier.fillMaxWidth()) {
            val syncState by viewModel.sync.collectAsState()
            val context = LocalContext.current

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!syncState.hasCredentials) {
                    Text(
                        if (opds.isActive) {
                            stringResource(R.string.settings_this_opds_catalog_has_no_quire_server)
                        } else {
                            stringResource(R.string.settings_configure_a_server_above_to_enable_sync)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val ts = syncState.lastSyncedAtMs
                    Text(
                        if (ts == null) stringResource(R.string.common_not_synced_yet) else stringResource(R.string.common_last_synced, formatRelative(ts)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Button(
                    onClick = { viewModel.syncNow(context) },
                    enabled = syncState.hasCredentials,
                ) { Text(stringResource(R.string.common_sync_now)) }
            }
        }

        SectionLabel(stringResource(R.string.common_storage_sync))
        QuireCard(modifier = Modifier.fillMaxWidth()) {
            val context = LocalContext.current
            var pendingResetSync by remember { mutableStateOf(false) }
            var pendingRemoveAll by remember { mutableStateOf(false) }

            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column {
                    Text(stringResource(R.string.common_reset_sync), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.settings_re_pull_everything_on_the_next_sync),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { pendingResetSync = true }) { Text(stringResource(R.string.common_reset_sync)) }
                }
                Column {
                    Text(stringResource(R.string.settings_remove_all_downloaded_books), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.settings_delete_all_epub_files_from_this_device),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = { pendingRemoveAll = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text(stringResource(R.string.settings_remove_all_downloaded_books)) }
                }
                if (isConnected) {
                    Column {
                        Text(stringResource(R.string.library_restore_in_progress_books), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.settings_re_download_the_books_you_d_started),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val restoreLabel = when {
                            restoreRunning && restoreProgress != null ->
                                stringResource(R.string.common_restoring, restoreProgress!!.done, restoreProgress!!.total)
                            restoreRunning -> stringResource(R.string.common_restoring_2)
                            else -> stringResource(R.string.library_restore_in_progress_books)
                        }
                        TextButton(
                            onClick = { viewModel.restoreInProgressBooks() },
                            enabled = !restoreRunning,
                        ) { Text(restoreLabel) }
                    }
                }
            }

            if (pendingResetSync) {
                AlertDialog(
                    onDismissRequest = { pendingResetSync = false },
                    title = { Text(stringResource(R.string.common_reset_sync_2)) },
                    text = { Text(stringResource(R.string.settings_next_sync_will_re_pull_everything_from)) },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.resetSync(context)
                            pendingResetSync = false
                        }) { Text(stringResource(R.string.common_reset)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingResetSync = false }) { Text(stringResource(R.string.common_cancel)) }
                    },
                )
            }
            if (pendingRemoveAll) {
                AlertDialog(
                    onDismissRequest = { pendingRemoveAll = false },
                    title = { Text(stringResource(R.string.settings_remove_all_downloaded_books_2)) },
                    text = { Text(stringResource(R.string.settings_delete_all_downloaded_books_from_this_device)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.removeAllBooks()
                                pendingRemoveAll = false
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { Text(stringResource(R.string.common_remove_all)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingRemoveAll = false }) { Text(stringResource(R.string.common_cancel)) }
                    },
                )
            }
        }

        SectionLabel(stringResource(R.string.common_ai_features))
        QuireCard(modifier = Modifier.fillMaxWidth()) {
            if (aiState.config?.configured != true) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.common_ai_not_configured), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.settings_your_administrator_has_not_configured_an_ai),
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
                                stringResource(R.string.settings_enable_ai_features_for_this_account),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                stringResource(R.string.settings_when_enabled_quire_sends_the_title_author, cfg.baseUrlHost ?: stringResource(R.string.settings_ai_endpoint), cfg.modelId ?: stringResource(R.string.common_unknown)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (cfg.sourcesEnabled.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.settings_external_sources_used, cfg.sourcesEnabled.joinToString(", ")),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                stringResource(R.string.settings_nothing_is_sent_until_you_opt_in),
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
                            Text(stringResource(R.string.common_insight_tone), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.settings_how_book_insights_are_written),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = { menuOpen = true }) {
                                Text(toneLabel(tone))
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                            ) {
                                listOf("neutral", "enthusiastic", "scholarly", "casual").forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(toneLabel(option)) },
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
                            Text(stringResource(R.string.common_refresh_insights), style = MaterialTheme.typography.titleMedium)
                            Text(
                                buildString {
                                    val last = aiState.lastInsightSyncMs
                                    if (last != null) {
                                        append(stringResource(R.string.settings_last_synced_sentence, formatRelative(last)))
                                    } else {
                                        append(stringResource(R.string.common_not_yet_synced))
                                    }
                                    when (val s = aiState.syncStatus) {
                                        is InsightSyncStatus.Syncing -> append(stringResource(R.string.common_syncing))
                                        is InsightSyncStatus.Error -> {
                                            append(" ")
                                            append(stringResource(R.string.settings_last_attempt_failed_sentence, s.message.asString()))
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
                            ) { Text(stringResource(R.string.common_refresh_insights)) }
                        }

                        val language = aiState.preferences?.style?.language ?: "auto"
                        var langMenuOpen by remember { mutableStateOf(false) }
                        Column {
                            Text(stringResource(R.string.common_insight_language), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.settings_language_the_model_writes_insights_in_auto),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = { langMenuOpen = true }) {
                                Text(
                                    LANGUAGE_OPTIONS.firstOrNull { it.first == language }?.second
                                        ?.let { if (language == "auto") stringResource(R.string.common_auto) else it }
                                        ?: language,
                                )
                            }
                            DropdownMenu(
                                expanded = langMenuOpen,
                                onDismissRequest = { langMenuOpen = false },
                            ) {
                                LANGUAGE_OPTIONS.forEach { (code, label) ->
                                    DropdownMenuItem(
                                        text = { Text(if (code == "auto") stringResource(R.string.common_auto) else label) },
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
                                stringResource(R.string.common_reader_profile),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                stringResource(R.string.settings_removes_the_ai_generated_profile_from_the),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = { viewModel.deleteReaderProfile() },
                                enabled = !deleteInFlight,
                            ) {
                                Text(
                                    if (deleteInFlight) stringResource(R.string.common_deleting) else stringResource(R.string.common_delete_reader_profile),
                                )
                            }
                        }
                    }
                }
            }
        }

        SectionLabel(stringResource(R.string.common_about))
        QuireCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column {
                    Text(stringResource(R.string.brand_name), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.common_a_reader_for_your_shelf),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(R.string.common_open_source_licenses),
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

@Composable
private fun formatRelative(epochMs: Long): String {
    val deltaSec = (System.currentTimeMillis() - epochMs) / 1000
    return when {
        deltaSec < 60 -> stringResource(R.string.common_just_now)
        deltaSec < 3600 -> stringResource(R.string.common_m_ago, deltaSec / 60)
        deltaSec < 86_400 -> stringResource(R.string.common_h_ago, deltaSec / 3600)
        else -> stringResource(R.string.common_d_ago, deltaSec / 86_400)
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
        Text(stringResource(R.string.common_status_2), style = MaterialTheme.typography.titleMedium)

        // Provider row.
        val providerCheckedMs = parseIsoOrNull(health.providerLastCheckedAt)
        val providerFailureMs = parseIsoOrNull(health.lastFailureAt)
        val (providerText, providerColor) = when (health.providerReachable) {
            null -> stringResource(R.string.settings_provider_not_yet_checked) to MaterialTheme.colorScheme.onSurfaceVariant
            true -> {
                val modelSuffix = health.modelId?.let { stringResource(R.string.common_model_2, it) } ?: ""
                val whenSuffix = providerCheckedMs?.let { stringResource(R.string.common_checked, formatRelative(it)) } ?: ""
                stringResource(R.string.settings_provider_reachable, modelSuffix, whenSuffix) to MaterialTheme.colorScheme.onSurface
            }
            false -> {
                val errClass = health.lastFailureClass ?: stringResource(R.string.common_unknown_error)
                val whenSuffix = providerFailureMs?.let { stringResource(R.string.common_time_suffix, formatRelative(it)) } ?: ""
                stringResource(R.string.settings_provider_unreachable, errClass, whenSuffix) to MaterialTheme.colorScheme.error
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
        null -> stringResource(R.string.common_not_yet_checked, label) to MaterialTheme.colorScheme.onSurfaceVariant
        true -> {
            val whenSuffix = checkedMs?.let { stringResource(R.string.common_checked, formatRelative(it)) } ?: ""
            stringResource(R.string.common_reachable, label, whenSuffix) to MaterialTheme.colorScheme.onSurface
        }
        false -> {
            val whenSuffix = checkedMs?.let { stringResource(R.string.common_time_suffix, formatRelative(it)) } ?: ""
            stringResource(R.string.common_unreachable, label, whenSuffix) to MaterialTheme.colorScheme.error
        }
    }
}

/**
 * Mode, then a light and a dark palette. The row the current mode can never show is dimmed
 * rather than hidden, so switching mode doesn't make the layout jump.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceSettings(theme: AppTheme, onChange: (AppTheme) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.appearance_mode), style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { i, mode ->
                    SegmentedButton(
                        selected = theme.mode == mode,
                        onClick = { onChange(theme.copy(mode = mode)) },
                        shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                    ) { Text(stringResource(mode.label)) }
                }
            }
        }
        PaletteRow(
            title = stringResource(R.string.appearance_light_theme),
            palettes = Palette.entries.filterNot { it.isDark },
            selected = theme.light,
            enabled = theme.mode != ThemeMode.DARK,
            onSelect = { onChange(theme.copy(light = it)) },
        )
        PaletteRow(
            title = stringResource(R.string.appearance_dark_theme),
            palettes = Palette.entries.filter { it.isDark },
            selected = theme.dark,
            enabled = theme.mode != ThemeMode.LIGHT,
            onSelect = { onChange(theme.copy(dark = it)) },
        )
    }
}

@Composable
private fun PaletteRow(
    title: String,
    palettes: List<Palette>,
    selected: Palette,
    enabled: Boolean,
    onSelect: (Palette) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.alpha(if (enabled) 1f else 0.38f),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            palettes.forEach { palette ->
                ThemeSwatch(
                    palette = palette,
                    selected = palette == selected,
                    enabled = enabled,
                    onSelect = { onSelect(palette) },
                )
            }
        }
    }
}

/** A thumbnail of a palette: its page, a card strip, its accent and its type. */
@Composable
private fun ThemeSwatch(palette: Palette, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    val colors = palette.colors
    val shape = MaterialTheme.shapes.medium
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 96.dp, height = 64.dp)
                .clip(shape)
                .background(colors.surface)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    shape = shape,
                ),
        ) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(14.dp)
                    .background(colors.surfaceContainer),
            )
            Text(
                text = "Aa",
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
            )
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .size(14.dp)
                    .background(colors.primary, CircleShape),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(palette.label),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else null,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}
