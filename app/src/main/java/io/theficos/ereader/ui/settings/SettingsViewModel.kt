package io.theficos.ereader.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.ereader.auth.CalibreCredentialStore
import io.theficos.ereader.auth.CalibreCredentials
import io.theficos.ereader.data.local.db.SyncStateDao
import io.theficos.ereader.data.sync.SyncEnqueuer
import io.theficos.ereader.reader.ReaderFontFamily
import io.theficos.ereader.reader.ReaderPreferences
import io.theficos.ereader.reader.ReaderPreferencesStore
import io.theficos.ereader.reader.ReaderTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SyncUiState(
    val hasCredentials: Boolean,
    val lastSyncedAtMs: Long?,
)

class SettingsViewModel(
    private val store: CalibreCredentialStore,
    private val readerStore: ReaderPreferencesStore,
    private val syncStateDao: SyncStateDao,
) : ViewModel() {
    private val _calibre = MutableStateFlow(loadInitialCalibre())
    val calibre: StateFlow<CalibreUiState> = _calibre.asStateFlow()

    val readerPreferences: StateFlow<ReaderPreferences> = readerStore.flow

    private val _sync = MutableStateFlow(SyncUiState(hasCredentials = store.get() != null, lastSyncedAtMs = null))
    val sync: StateFlow<SyncUiState> = _sync.asStateFlow()

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
            _sync.value = _sync.value.copy(hasCredentials = true)
        }
    }

    fun setFontScale(value: Double) { readerStore.update { it.copy(fontScale = value.coerceIn(0.5, 2.0)) } }
    fun setTheme(theme: ReaderTheme) { readerStore.update { it.copy(theme = theme) } }
    fun setFontFamily(family: ReaderFontFamily) { readerStore.update { it.copy(fontFamily = family) } }
    fun setLineSpacing(value: Double) { readerStore.update { it.copy(lineSpacing = value.coerceIn(1.0, 1.8)) } }

    fun syncNow(context: Context) {
        if (!_sync.value.hasCredentials) return
        SyncEnqueuer.enqueue(context, expedited = true, replaceExisting = true)
        viewModelScope.launch {
            val ts = syncStateDao.lastPulled("progress")
            if (ts != null) _sync.value = _sync.value.copy(lastSyncedAtMs = ts)
        }
    }
}

data class CalibreUiState(
    val baseUrl: String,
    val username: String,
    val password: String,
    val saved: Boolean,
)
