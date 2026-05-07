package io.theficos.ereader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.theficos.ereader.reader.ReaderTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val calibre by viewModel.calibre.collectAsState()
    val reader by viewModel.readerPreferences.collectAsState()
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
        )
    }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("calibre-web", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = calibre.baseUrl,
                onValueChange = viewModel::onBaseUrlChange,
                label = { Text("calibre-web URL") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = calibre.username,
                onValueChange = viewModel::onUsernameChange,
                label = { Text("Username") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = calibre.password,
                onValueChange = viewModel::onPasswordChange,
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = viewModel::saveCalibre,
                enabled = calibre.baseUrl.isNotBlank() && calibre.username.isNotBlank() && calibre.password.isNotBlank(),
            ) {
                Text(if (calibre.saved) "Saved" else "Save")
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("Reader", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)

            Text("Font size: ${"%.1fx".format(reader.fontScale)}")
            Slider(
                value = reader.fontScale.toFloat(),
                onValueChange = { viewModel.setFontScale(it.toDouble()) },
                valueRange = 0.5f..2.0f,
                steps = 14, // (2.0 - 0.5) / 0.1 - 1
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Theme")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReaderTheme.values().forEach { t ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 16.dp),
                    ) {
                        RadioButton(
                            selected = reader.theme == t,
                            onClick = { viewModel.setTheme(t) },
                        )
                        Text(t.name.lowercase().replaceFirstChar { it.uppercase() })
                    }
                }
            }
        }
    }
}
