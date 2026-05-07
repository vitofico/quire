package io.theficos.ereader.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.ereader.auth.CalibreCredentialStore
import io.theficos.ereader.auth.CalibreCredentials
import io.theficos.ereader.reader.ReaderPreferences
import io.theficos.ereader.reader.ReaderPreferencesStore
import io.theficos.ereader.reader.ReaderTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val store: CalibreCredentialStore,
    private val readerStore: ReaderPreferencesStore,
) : ViewModel() {
    private val _calibre = MutableStateFlow(loadInitialCalibre())
    val calibre: StateFlow<CalibreUiState> = _calibre.asStateFlow()

    val readerPreferences: StateFlow<ReaderPreferences> = readerStore.flow

    private fun loadInitialCalibre(): CalibreUiState {
        val creds = store.get()
        return CalibreUiState(
            baseUrl = creds?.baseUrl.orEmpty(),
            username = creds?.username.orEmpty(),
            password = creds?.password.orEmpty(),
            saved = creds != null,
        )
    }

    fun onBaseUrlChange(value: String) { _calibre.value = _calibre.value.copy(baseUrl = value, saved = false) }
    fun onUsernameChange(value: String) { _calibre.value = _calibre.value.copy(username = value, saved = false) }
    fun onPasswordChange(value: String) { _calibre.value = _calibre.value.copy(password = value, saved = false) }

    fun saveCalibre() {
        val s = _calibre.value
        if (s.baseUrl.isBlank() || s.username.isBlank() || s.password.isBlank()) return
        viewModelScope.launch {
            store.put(CalibreCredentials(s.baseUrl.trim().trimEnd('/'), s.username, s.password))
            _calibre.value = s.copy(saved = true)
        }
    }

    fun setFontScale(value: Double) {
        readerStore.update { it.copy(fontScale = value.coerceIn(0.5, 2.0)) }
    }

    fun setTheme(theme: ReaderTheme) {
        readerStore.update { it.copy(theme = theme) }
    }
}

data class CalibreUiState(
    val baseUrl: String,
    val username: String,
    val password: String,
    val saved: Boolean,
)
