package io.theficos.ereader.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.theficos.quire.R

/** The same quiet ink identity as the shelf, reserved for full-screen waits. */
@Composable
fun QuireLoading(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Box(Modifier.size(width = 192.dp, height = 128.dp), contentAlignment = Alignment.Center) {
            MarbledInk(Modifier.matchParentSize(), intensity = 0.55f)
            Image(
                painter = painterResource(R.drawable.quire_mark),
                contentDescription = null,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.size(width = 40.dp, height = 52.dp),
            )
        }
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        Text(
            text = stringResource(R.string.common_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
