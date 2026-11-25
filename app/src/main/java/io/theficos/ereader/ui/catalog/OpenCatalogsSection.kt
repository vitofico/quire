package io.theficos.ereader.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.theficos.ereader.ui.settings.CatalogsViewModel
import io.theficos.quire.R
import java.util.Locale

/**
 * The free catalogs Quire suggests, those in the reader's language first. One tap tests and adds
 * one like a typed address; one already in the list is marked instead of offered.
 */
@Composable
fun OpenCatalogsSection(viewModel: CatalogsViewModel, modifier: Modifier = Modifier) {
    val saved by viewModel.catalogs.collectAsState()
    val adding by viewModel.openAdding.collectAsState()
    val locale = LocalConfiguration.current.locales[0]
    val ordered = remember(viewModel.openCatalogs, locale) { viewModel.openCatalogs.orderedFor(locale.language) }
    if (ordered.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.open_catalogs_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.open_catalogs_privacy),
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
                    state?.error?.let {
                        Text(it.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                when {
                    added -> Row(
                        Modifier.padding(start = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.open_catalogs_added), style = MaterialTheme.typography.labelLarge)
                    }
                    state?.busy == true -> CircularProgressIndicator(Modifier.padding(start = 8.dp).size(20.dp))
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
}

private fun offerLabel(offers: String): Int =
    if (offers == "open_licence") R.string.open_catalogs_open_licence else R.string.open_catalogs_public_domain

/** "Deutsch" for `de` in German, "tedesco" in Italian: the language named as the reader reads. */
private fun languageName(code: String, locale: Locale): String =
    Locale.forLanguageTag(code).getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
