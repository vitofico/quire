package io.theficos.ereader.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.theficos.ereader.ui.theme.EReaderTheme

/**
 * Placeholder for the deferred "Sign in to Quire Cloud" path.
 *
 * Why pure static text:
 *   - Quire Cloud doesn't exist yet; touching `cloud.quire.app` would be
 *     dishonest UX and lock in a callsite we'd have to refactor later.
 *   - A mailto / notify-me link would require an email-collection backend
 *     we equally don't have.
 *   - A BuildConfig feature flag would add machinery for a single TODO.
 *
 * When Cloud lands (Phase 1) this screen is replaced wholesale with the
 * real sign-in flow, and the route name stays the same so the welcome
 * screen wiring doesn't churn.
 */
@Composable
fun CloudComingSoonScreen(onBack: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Quire Cloud is coming soon",
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text =
                        "We're not ready to take cloud sign-ups yet. " +
                            "For now, you can connect to a self-hosted calibre-web or " +
                            "quire_server, or use Quire entirely offline.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(32.dp))
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Back")
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun CloudComingSoonPreview() {
    EReaderTheme {
        CloudComingSoonScreen(onBack = {})
    }
}
