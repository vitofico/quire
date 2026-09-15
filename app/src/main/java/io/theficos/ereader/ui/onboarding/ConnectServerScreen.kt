package io.theficos.ereader.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.theficos.ereader.data.opds.ServerProbeResult
import io.theficos.ereader.ui.components.QuireCard
import io.theficos.ereader.ui.components.SectionLabel
import io.theficos.ereader.ui.theme.EReaderTheme

/**
 * URL-input + auto-probe + credential-entry screen.
 *
 * Render logic is driven by [ConnectServerViewModel.UiState]. Credential
 * input is gated on a successful probe; for an ambiguous `Both` result the
 * user picks which kind of account to use before the credential card is
 * shown. On `Unknown` / `Error` we surface the diagnostic and let the user
 * retry — we deliberately do NOT offer a "save anyway" override in v1; if
 * the probe can't recognize the server, the user has no reliable way to
 * know which scheme to pick, and a wrong choice will fail to authenticate
 * a few keystrokes later.
 */
@Composable
fun ConnectServerScreen(
    viewModel: ConnectServerViewModel,
    onBack: () -> Unit,
    onCompleted: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    var url by rememberSaveable { mutableStateOf("https://") }

    LaunchedEffect(state) {
        if (state is ConnectServerViewModel.UiState.Completed) onCompleted()
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Connect to your server",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = "Paste the URL of your calibre-web or Quire server. " +
                    "We'll figure out which one it is.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = url,
                onValueChange = {
                    // Reset the result panel whenever the URL changes so
                    // stale probe outcomes can't be acted on.
                    if (state !is ConnectServerViewModel.UiState.Idle &&
                        state !is ConnectServerViewModel.UiState.Probing
                    ) {
                        viewModel.resetToIdle()
                    }
                    url = it
                },
                label = { Text("Server URL") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                    autoCorrect = false,
                    capitalization = KeyboardCapitalization.None,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Server URL" },
            )

            val canProbe = state !is ConnectServerViewModel.UiState.Probing &&
                state !is ConnectServerViewModel.UiState.Verifying &&
                url.isNotBlank()
            Button(
                onClick = { viewModel.probe(url) },
                enabled = canProbe,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Probe server")
            }

            ResultPanel(
                state = state,
                onVerifyBasic = { base, u, p -> viewModel.verifyAndSaveBasic(base, u, p) },
                onVerifyBearer = { base, e, p -> viewModel.verifyAndSaveBearer(base, e, p) },
                onVerifyOpds = { catalog, u, p -> viewModel.verifyAndSaveOpds(catalog, u, p) },
            )

            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Back to start")
            }
        }
    }
}

@Composable
private fun ResultPanel(
    state: ConnectServerViewModel.UiState,
    onVerifyBasic: (String, String, String) -> Unit,
    onVerifyBearer: (String, String, String) -> Unit,
    onVerifyOpds: (String, String?, String?) -> Unit,
) {
    when (state) {
        ConnectServerViewModel.UiState.Idle -> Unit
        is ConnectServerViewModel.UiState.Probing -> {
            QuireCard(modifier = Modifier.fillMaxWidth()) {
                ProbingRow(rawUrl = state.rawUrl)
            }
        }
        is ConnectServerViewModel.UiState.Result -> {
            when (val probe = state.probe) {
                is ServerProbeResult.Calibre -> BasicCredentialCard(
                    canonicalBaseUrl = probe.canonicalBaseUrl,
                    headline = "Calibre-web detected",
                    onSubmit = onVerifyBasic,
                )
                is ServerProbeResult.NativeAuth -> BearerCredentialCard(
                    canonicalBaseUrl = probe.canonicalBaseUrl,
                    headline = "Quire server detected",
                    onSubmit = onVerifyBearer,
                )
                is ServerProbeResult.Both -> AmbiguousChoiceCard(
                    canonicalBaseUrl = probe.canonicalBaseUrl,
                    onVerifyBasic = onVerifyBasic,
                    onVerifyBearer = onVerifyBearer,
                )
                is ServerProbeResult.GenericOpds -> GenericOpdsCard(
                    canonicalCatalogUrl = probe.canonicalCatalogUrl,
                    requiresAuth = probe.requiresAuth,
                    onSubmit = onVerifyOpds,
                )
                is ServerProbeResult.Unknown -> QuireCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "We couldn't recognize that server",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            probe.diagnostics,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Check the URL (including any subpath) and try again.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                is ServerProbeResult.Error -> QuireCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Couldn't reach the server",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            probeErrorMessage(probe.reason, probe.message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        ConnectServerViewModel.UiState.Verifying -> QuireCard(modifier = Modifier.fillMaxWidth()) {
            ProbingRow(rawUrl = "Signing you in…")
        }
        is ConnectServerViewModel.UiState.VerificationFailed -> QuireCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Couldn't sign in",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ConnectServerViewModel.UiState.Completed -> Unit
    }
}

@Composable
private fun ProbingRow(rawUrl: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        CircularProgressIndicator(modifier = Modifier.height(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            rawUrl,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BasicCredentialCard(
    canonicalBaseUrl: String,
    headline: String,
    onSubmit: (String, String, String) -> Unit,
) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    QuireCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel(headline)
            Text(
                canonicalBaseUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("calibre-web username") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next,
                    autoCorrect = false,
                    capitalization = KeyboardCapitalization.None,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Username" },
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Go,
                    autoCorrect = false,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Password" },
            )
            Button(
                onClick = { onSubmit(canonicalBaseUrl, username, password) },
                enabled = username.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Sign in") }
        }
    }
}

@Composable
private fun BearerCredentialCard(
    canonicalBaseUrl: String,
    headline: String,
    onSubmit: (String, String, String) -> Unit,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    QuireCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel(headline)
            Text(
                canonicalBaseUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                    autoCorrect = false,
                    capitalization = KeyboardCapitalization.None,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Email" },
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Go,
                    autoCorrect = false,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Password" },
            )
            Button(
                onClick = { onSubmit(canonicalBaseUrl, email, password) },
                enabled = email.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Sign in") }
        }
    }
}

@Composable
private fun GenericOpdsCard(
    canonicalCatalogUrl: String,
    requiresAuth: Boolean,
    onSubmit: (String, String?, String?) -> Unit,
) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    QuireCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("OPDS catalog detected", style = MaterialTheme.typography.titleMedium)
            Text(
                "Quire can browse, download and read from this catalog, and it " +
                    "will remember your place on this device.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Reading progress won't sync between devices, and the AI features " +
                    "stay off: both of those need a Quire server, which this " +
                    "catalog doesn't provide.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "If this is a calibre-web server, go back and enter its address " +
                    "instead of the feed URL, and you'll get sync too.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (requiresAuth) {
                Text(
                    "This catalog asked for a username and password.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username (optional)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next,
                    autoCorrect = false,
                    capitalization = KeyboardCapitalization.None,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Catalog username" },
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password (optional)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Go,
                    autoCorrect = false,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Catalog password" },
            )
            Button(
                onClick = {
                    onSubmit(
                        canonicalCatalogUrl,
                        username.takeIf { it.isNotBlank() },
                        password.takeIf { it.isNotBlank() },
                    )
                },
                // One credential without the other would send no header and
                // then fail with an unexplained 401.
                enabled = username.isBlank() == password.isBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Connect") }
        }
    }
}

@Composable
private fun AmbiguousChoiceCard(
    canonicalBaseUrl: String,
    onVerifyBasic: (String, String, String) -> Unit,
    onVerifyBearer: (String, String, String) -> Unit,
) {
    var choice by rememberSaveable { mutableStateOf<AmbiguousChoice?>(null) }
    QuireCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("Two account types detected")
            Text(
                "This URL looks like both a calibre-web and a Quire server. " +
                    "Which account do you want to use?",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = choice == AmbiguousChoice.Basic,
                    onClick = { choice = AmbiguousChoice.Basic },
                )
                Text("calibre-web (username + password)")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = choice == AmbiguousChoice.Bearer,
                    onClick = { choice = AmbiguousChoice.Bearer },
                )
                Text("Quire server (email + password)")
            }
            when (choice) {
                AmbiguousChoice.Basic -> BasicCredentialCard(
                    canonicalBaseUrl = canonicalBaseUrl,
                    headline = "calibre-web",
                    onSubmit = onVerifyBasic,
                )
                AmbiguousChoice.Bearer -> BearerCredentialCard(
                    canonicalBaseUrl = canonicalBaseUrl,
                    headline = "Quire server",
                    onSubmit = onVerifyBearer,
                )
                null -> Unit
            }
        }
    }
}

private enum class AmbiguousChoice { Basic, Bearer }

@Preview(showBackground = true)
@Composable
private fun ConnectServerScreenIdlePreview() {
    EReaderTheme {
        Box(
            modifier = Modifier
                .padding(8.dp)
                .fillMaxSize(),
        ) {
            // Static preview: we can't instantiate a ViewModel here. Render
            // the same panel pieces with hand-built state.
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Connect to your server", style = MaterialTheme.typography.headlineMedium)
                    OutlinedTextField(value = "https://", onValueChange = {}, label = { Text("Server URL") })
                    Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Probe server") }
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "Result — calibre-web")
@Composable
private fun ConnectServerScreenCalibrePreview() {
    EReaderTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                BasicCredentialCard(
                    canonicalBaseUrl = "https://calibre.example.com",
                    headline = "Calibre-web detected",
                    onSubmit = { _, _, _ -> },
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "Result — Quire server")
@Composable
private fun ConnectServerScreenBearerPreview() {
    EReaderTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                BearerCredentialCard(
                    canonicalBaseUrl = "https://quire.example.com",
                    headline = "Quire server detected",
                    onSubmit = { _, _, _ -> },
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "Result - OPDS catalog")
@Composable
private fun ConnectServerScreenGenericOpdsPreview() {
    EReaderTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                GenericOpdsCard(
                    canonicalCatalogUrl = "https://catalog.example.com/opds",
                    requiresAuth = false,
                    onSubmit = { _, _, _ -> },
                )
            }
        }
    }
}
