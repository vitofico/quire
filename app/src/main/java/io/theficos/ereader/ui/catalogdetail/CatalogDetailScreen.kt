package io.theficos.ereader.ui.catalogdetail

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.theficos.ereader.ui.bookdetail.InsightSection
import io.theficos.ereader.ui.bookdetail.InsightUiState
import io.theficos.ereader.ui.components.BookDescription
import io.theficos.ereader.ui.components.BookHeader
import io.theficos.ereader.ui.components.SectionLabel
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import io.theficos.ereader.ui.components.CoverImage
import io.theficos.quire.R

/**
 * Pre-download book detail. Shows cover + title + author, the catalog entry's
 * own description, the same `InsightSection` the book-detail screen uses, and
 * an stringResource(R.string.common_open_in_calibre_web) footer when the OPDS entry exposed a web URL.
 *
 * The screen does NOT trigger downloads — that stays a catalog-tile tap.
 * The catalog screen handles download progress + state.
 */
@Composable
fun CatalogDetailScreen(
    viewModel: CatalogDetailViewModel,
    onBack: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val pub = state.publication

    Box(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.common_back),
                    )
                }
            }

            BookHeader(
                coverSource = pub.coverUrl,
                title = pub.title,
                author = pub.author,
                series = null,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            Spacer(Modifier.height(16.dp))

            if (!pub.description.isNullOrBlank()) {
                BookDescription(pub.description)
                Spacer(Modifier.height(16.dp))
            }

            if (state.insight !is InsightUiState.Hidden) {
                SectionLabel("AI insights")
                Spacer(Modifier.height(8.dp))
                InsightSection(state = state.insight, onRetry = viewModel::retry)
            }

            // Say why the page is bare rather than leaving the reader to guess
            // whether something failed to load.
            if (pub.description.isNullOrBlank() && state.insight is InsightUiState.Hidden) {
                Text(
                    text = stringResource(R.string.catalogdetail_this_catalog_didn_t_include_a_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))

            // Footer: open in calibre-web.
            pub.webUrl?.let { web ->
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(web))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.common_open_in_calibre_web))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
