package io.theficos.ereader.ui.catalog

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.theficos.ereader.data.opds.CatalogSyncStatus
import io.theficos.ereader.ui.settings.CatalogsViewModel
import io.theficos.quire.R
import java.util.Locale

/**
 * The free catalogs Quire suggests, those in the reader's language first. One tap tests and adds
 * one like a typed address; one already in the list is marked as added and can be removed again.
 */
@Composable
fun OpenCatalogsSection(viewModel: CatalogsViewModel, modifier: Modifier = Modifier) {
    val saved by viewModel.catalogs.collectAsState()
    val adding by viewModel.openAdding.collectAsState()
    val status by viewModel.status.collectAsState()
    val uriHandler = LocalUriHandler.current
    val locale = LocalConfiguration.current.locales[0]
    val ordered = remember(viewModel.openCatalogs, locale) { viewModel.openCatalogs.orderedFor(locale.language) }
    if (ordered.isEmpty()) return
    var confirmRemove by remember { mutableStateOf<OpenCatalog?>(null) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.open_catalogs_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.open_catalogs_privacy) +
                if (status == CatalogSyncStatus.SYNCED) " " + stringResource(R.string.open_catalogs_synced) else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ordered.forEach { open ->
            HorizontalDivider()
            val added = saved.any { it.catalog?.baseUrl == open.url }
            val state = adding?.takeIf { it.id == open.id }
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(open.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        (open.languages.map { languageName(it, locale) } + stringResource(offerLabel(open.offers)))
                            .joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        open.description(locale.language),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // The catalog's own site, to see who runs it before adding it.
                    Text(
                        Uri.parse(open.home).host.orEmpty().removePrefix("www."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.clickable(role = Role.Button) { uriHandler.openUri(open.home) },
                    )
                    state?.error?.let {
                        Text(it.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (state?.tested != null && !state.busy) {
                        TextButton(onClick = viewModel::keepOpenOnDevice) {
                            Text(stringResource(R.string.catalogs_keep_device_only))
                        }
                    }
                }
                when {
                    state?.busy == true -> CircularProgressIndicator(Modifier.padding(start = 8.dp).size(20.dp))
                    added -> Column(Modifier.padding(start = 8.dp), horizontalAlignment = Alignment.End) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(stringResource(R.string.open_catalogs_added), style = MaterialTheme.typography.labelLarge)
                        }
                        val description = stringResource(R.string.open_catalogs_remove_named, open.name)
                        TextButton(
                            onClick = { confirmRemove = open },
                            enabled = adding?.busy != true,
                            modifier = Modifier.semantics { contentDescription = description },
                        ) { Text(stringResource(R.string.catalogs_remove)) }
                    }
                    else -> {
                        val description = stringResource(R.string.open_catalogs_add_named, open.name)
                        TextButton(
                            onClick = { viewModel.addOpen(open) },
                            enabled = adding?.busy != true,
                            modifier = Modifier.semantics { contentDescription = description },
                        ) { Text(stringResource(R.string.open_catalogs_add)) }
                    }
                }
            }
        }
    }
    confirmRemove?.let { open ->
        val onAccount = saved.firstOrNull { it.catalog?.baseUrl == open.url }?.onAccount == true
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(stringResource(R.string.catalogs_remove_title, open.name)) },
            text = {
                Text(stringResource(if (onAccount) R.string.catalogs_remove_account else R.string.catalogs_remove_device))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = null
                    viewModel.removeOpen(open)
                }) { Text(stringResource(R.string.catalogs_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

private fun offerLabel(offers: String): Int = when (offers) {
    "open_licence" -> R.string.open_catalogs_open_licence
    "public_domain_us" -> R.string.open_catalogs_public_domain_us
    else -> R.string.open_catalogs_public_domain
}

/** "Deutsch" for `de` in German, "tedesco" in Italian: the language named as the reader reads. */
private fun languageName(code: String, locale: Locale): String =
    Locale.forLanguageTag(code).getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
