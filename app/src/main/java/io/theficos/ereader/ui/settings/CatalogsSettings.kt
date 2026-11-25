package io.theficos.ereader.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.theficos.ereader.auth.SavedCatalog
import io.theficos.ereader.data.opds.CatalogSyncStatus
import io.theficos.ereader.ui.catalog.OpenCatalogsSection
import io.theficos.ereader.ui.components.QuireCard
import io.theficos.ereader.ui.components.SectionLabel
import io.theficos.quire.R

/** Settings → Catalogs: where the list lives, one row per catalog, and the add or edit form. */
@Composable
internal fun CatalogsSettings(viewModel: CatalogsViewModel) {
    val catalogs by viewModel.catalogs.collectAsState()
    val status by viewModel.status.collectAsState()
    val form by viewModel.form.collectAsState()
    val message by viewModel.message.collectAsState()

    SectionLabel(stringResource(R.string.catalogs_title))
    QuireCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(
                    when (status) {
                        CatalogSyncStatus.SYNCED -> R.string.catalogs_status_synced
                        CatalogSyncStatus.NOT_SUPPORTED -> R.string.catalogs_status_not_supported
                        CatalogSyncStatus.CHECKING -> R.string.catalogs_status_checking
                        CatalogSyncStatus.DEVICE_ONLY -> R.string.catalogs_status_device
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            message?.let { Text(it.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            val current = form
            if (current != null) {
                CatalogFormContent(
                    form = current,
                    existing = catalogs.find { it.id == current.id },
                    synced = status == CatalogSyncStatus.SYNCED,
                    viewModel = viewModel,
                )
            } else {
                if (catalogs.isEmpty()) {
                    Text(stringResource(R.string.catalogs_empty), style = MaterialTheme.typography.bodyMedium)
                } else {
                    CatalogRows(catalogs, synced = status == CatalogSyncStatus.SYNCED, viewModel = viewModel)
                }
                Button(onClick = viewModel::add) { Text(stringResource(R.string.catalogs_add)) }
            }
        }
    }
    if (form == null && viewModel.openCatalogs.isNotEmpty()) {
        QuireCard(modifier = Modifier.fillMaxWidth()) { OpenCatalogsSection(viewModel) }
    }
}

@Composable
private fun CatalogRows(catalogs: List<SavedCatalog>, synced: Boolean, viewModel: CatalogsViewModel) {
    val movable = catalogs.filterNot { it.pinned }
    Column {
        catalogs.forEachIndexed { index, catalog ->
            if (index > 0) HorizontalDivider()
            val position = movable.indexOf(catalog)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(enabled = !catalog.pinned, role = Role.Button) { viewModel.edit(catalog.id) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(catalog.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        catalog.host,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val note = when {
                        catalog.pinned -> R.string.catalogs_row_sign_in
                        catalog.needsLogin -> R.string.catalogs_row_needs_login
                        synced && !catalog.onAccount -> R.string.catalogs_row_device_only
                        else -> null
                    }
                    note?.let {
                        Text(
                            stringResource(it),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (catalog.needsLogin) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (!catalog.pinned && movable.size > 1) {
                    IconButton(onClick = { viewModel.move(catalog.id, -1) }, enabled = position > 0) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.catalogs_move_up, catalog.name))
                    }
                    IconButton(onClick = { viewModel.move(catalog.id, 1) }, enabled = position < movable.lastIndex) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.catalogs_move_down, catalog.name))
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogFormContent(form: CatalogForm, existing: SavedCatalog?, synced: Boolean, viewModel: CatalogsViewModel) {
    // The address is masked like a password: a Kavita address carries the account's key.
    var revealAddress by rememberSaveable(form.id) { mutableStateOf(false) }
    var confirmRemove by remember(form.id) { mutableStateOf(false) }
    val enabled = !form.busy
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(if (form.isNew) R.string.catalogs_add else R.string.catalogs_edit),
            style = MaterialTheme.typography.titleMedium,
        )
        OutlinedTextField(
            value = form.address,
            onValueChange = { viewModel.onFormChange(form.copy(address = it)) },
            label = { Text(stringResource(R.string.catalogs_address)) },
            supportingText = { Text(stringResource(R.string.catalogs_address_help)) },
            enabled = enabled,
            singleLine = true,
            visualTransformation = if (revealAddress) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                autoCorrectEnabled = false,
                capitalization = KeyboardCapitalization.None,
            ),
            trailingIcon = {
                TextButton(onClick = { revealAddress = !revealAddress }) {
                    Text(stringResource(if (revealAddress) R.string.common_hide else R.string.common_show))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.username,
            onValueChange = { viewModel.onFormChange(form.copy(username = it)) },
            label = { Text(stringResource(R.string.common_username_optional)) },
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.password,
            onValueChange = { viewModel.onFormChange(form.copy(password = it)) },
            label = { Text(stringResource(R.string.common_password_optional)) },
            enabled = enabled,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.name,
            onValueChange = { viewModel.onFormChange(form.copy(name = it.take(SavedCatalog.MAX_NAME_LENGTH))) },
            label = { Text(stringResource(R.string.catalogs_name)) },
            placeholder = { Text(SavedCatalog.defaultName(form.address)) },
            enabled = enabled,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        form.error?.let { Text(it.asString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        if (form.busy) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp))
                Text(stringResource(R.string.catalogs_checking), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.saveForm() },
                enabled = enabled && form.address.isNotBlank() && form.username.isBlank() == form.password.isBlank(),
            ) { Text(stringResource(R.string.common_save)) }
            TextButton(onClick = viewModel::dismissForm) { Text(stringResource(R.string.common_cancel)) }
        }
        if (form.offerDeviceOnly) {
            OutlinedButton(onClick = { viewModel.saveForm(deviceOnly = true) }, enabled = enabled) {
                Text(stringResource(R.string.catalogs_keep_device_only))
            }
        }
        if (existing != null && synced && !existing.onAccount) {
            OutlinedButton(onClick = { viewModel.saveForm(deviceOnly = false) }, enabled = enabled) {
                Text(stringResource(R.string.catalogs_save_to_account))
            }
        }
        if (existing != null) {
            TextButton(
                onClick = { confirmRemove = true },
                enabled = enabled,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.catalogs_remove)) }
        }
    }
    if (confirmRemove && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.catalogs_remove_title, existing.name)) },
            text = {
                Text(stringResource(if (existing.onAccount) R.string.catalogs_remove_account else R.string.catalogs_remove_device))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    viewModel.removeEditing()
                }) { Text(stringResource(R.string.catalogs_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

/**
 * The question after signing in to a server with catalog sync: which of this device's catalogs
 * to save to the Quire account. Each is ticked to begin with; unticked ones stay on this device.
 */
@Composable
internal fun CatalogUploadQuestion(viewModel: CatalogsViewModel) {
    val pending by viewModel.uploadQuestion.collectAsState()
    if (pending.isEmpty()) return
    var declined by rememberSaveable { mutableStateOf(emptyList<String>()) }
    AlertDialog(
        onDismissRequest = viewModel::postponeUpload,
        title = { Text(stringResource(R.string.catalogs_upload_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(pluralStringResource(R.plurals.catalogs_upload_question, pending.size, pending.size))
                pending.forEach { catalog ->
                    val ticked = catalog.id !in declined
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Checkbox) {
                                declined = if (ticked) declined + catalog.id else declined - catalog.id
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = ticked, onCheckedChange = null)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(catalog.name, style = MaterialTheme.typography.bodyLarge)
                            Text(catalog.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text(
                    stringResource(R.string.catalogs_upload_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                viewModel.answerUpload(pending.map { it.id }.filterNot { it in declined }.toSet())
            }) { Text(stringResource(R.string.catalogs_save_to_account)) }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.answerUpload(emptySet()) }) {
                Text(stringResource(R.string.catalogs_keep_device_only))
            }
        },
    )
}
