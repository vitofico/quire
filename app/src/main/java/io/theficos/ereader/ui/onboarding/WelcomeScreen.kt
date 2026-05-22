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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.theficos.ereader.ui.theme.EReaderTheme

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
                    text = "Welcome to Quire",
                    style = MaterialTheme.typography.displaySmall,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "How would you like to read?",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(40.dp))

                Button(
                    onClick = onConnectServer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Connect to my server" },
                ) {
                    Text("Connect to my server")
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onCloudSignIn,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Sign in to Quire Cloud" },
                ) {
                    Text("Sign in to Quire Cloud")
                }
                Spacer(modifier = Modifier.height(24.dp))
                TextButton(
                    onClick = onSkipOffline,
                    modifier = Modifier.semantics {
                        contentDescription = "Skip and use Quire offline only"
                    },
                ) {
                    Text("Skip — use offline only")
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun WelcomeScreenPreview() {
    EReaderTheme {
        WelcomeScreen(
            onConnectServer = {},
            onCloudSignIn = {},
            onSkipOffline = {},
        )
    }
}
