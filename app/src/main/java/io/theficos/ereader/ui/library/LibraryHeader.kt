package io.theficos.ereader.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.theficos.quire.R

@Composable
internal fun LibraryHeader(actionCount: Int, actions: @Composable RowScope.() -> Unit) {
    val title = stringResource(R.string.brand_name)
    val style = MaterialTheme.typography.headlineSmall
    val titleWidth = rememberTextMeasurer().measure(AnnotatedString(title), style).size.width
    val requiredWidth = with(LocalDensity.current) { titleWidth.toDp() } + 8.dp + 48.dp * actionCount
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Keep every action's 48dp target when either the window or the text needs more room.
        if (maxWidth >= requiredWidth) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = style, color = MaterialTheme.colorScheme.onSurface)
                Row(verticalAlignment = Alignment.CenterVertically, content = actions)
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                Text(title, style = style, color = MaterialTheme.colorScheme.onSurface)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
    }
}
