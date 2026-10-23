package io.theficos.ereader.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.theficos.ereader.ui.theme.QuireTheme
import io.theficos.ereader.ui.localizedDescription
import io.theficos.quire.R

/**
 * First-launch landing screen. Three paths, in the order specified by the
 * monetization spec:
 *
 *   1. **Connect to my server** — primary action. Goes to [ConnectServerScreen],
 *      which auto-detects calibre-web vs `quire_server` Native auth.
 *   2. **Sign in to Quire Cloud** — secondary. Quire Cloud is not built yet
 *      so this leads to a "coming soon" placeholder.
 *   3. **Skip — use offline only** — small text link at the bottom. Persists
 *      the `welcomeCompleted` flag so we don't re-prompt the user on every
 *      launch.
 *
 * Sideload (A-3) is mounted on LibraryScreen and is available regardless of
 * which path the user chose; we don't surface it here to keep the choice
 * cognitively light.
 */
@Composable
fun WelcomeScreen(
    onConnectServer: () -> Unit,
    onCloudSignIn: () -> Unit,
    onSkipOffline: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.common_welcome_to_quire),
                    style = MaterialTheme.typography.displaySmall,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.onboarding_how_would_you_like_to_read),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(40.dp))

                Button(
                    onClick = onConnectServer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .localizedDescription(R.string.common_connect_to_my_server),
                ) {
                    Text(stringResource(R.string.common_connect_to_my_server))
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onCloudSignIn,
                    modifier = Modifier
                        .fillMaxWidth()
                        .localizedDescription(R.string.common_sign_in_to_quire_cloud),
                ) {
                    Text(stringResource(R.string.common_sign_in_to_quire_cloud))
                }
                Spacer(modifier = Modifier.height(24.dp))
                TextButton(
                    onClick = onSkipOffline,
                    modifier = Modifier.localizedDescription(R.string.onboarding_skip_and_use_quire_offline_only),
                ) {
                    Text(stringResource(R.string.common_skip_use_offline_only))
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun WelcomeScreenPreview() {
    QuireTheme {
        WelcomeScreen(
            onConnectServer = {},
            onCloudSignIn = {},
            onSkipOffline = {},
        )
    }
}
