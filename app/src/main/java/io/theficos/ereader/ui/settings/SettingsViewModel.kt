package io.theficos.ereader.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.ereader.auth.CalibreCredentialStore
import io.theficos.ereader.auth.CalibreCredentials
import io.theficos.ereader.data.ai.AiConfig
import io.theficos.ereader.data.ai.AiHealthResponse
import io.theficos.ereader.data.ai.AiPreferences
import io.theficos.ereader.data.ai.AiRepository
import io.theficos.ereader.data.ai.InsightSyncRepository
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.local.db.InsightDao
import io.theficos.ereader.data.local.db.SyncStateDao
import io.theficos.ereader.data.sync.SyncEnqueuer
import java.io.File
import io.theficos.ereader.reader.ReaderFontFamily
import io.theficos.ereader.reader.ReaderPreferences
import io.theficos.ereader.reader.ReaderPreferencesStore
import io.theficos.ereader.reader.ReaderTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SyncUiState(
    val hasCredentials: Boolean,
    val lastSyncedAtMs: Long?,
)

data class AiState(
    val config: AiConfig? = null,
    val preferences: AiPreferences? = null,
    val toggling: Boolean = false,
    val health: AiHealthResponse? = null,
    /** Wall-clock millis of the last successful local insight upsert; null if never synced. */
    val lastInsightSyncMs: Long? = null,
    val syncStatus: InsightSyncStatus = InsightSyncStatus.Idle,
)

/** UI state for the Settings → "Refresh insights" button. */
sealed interface InsightSyncStatus {
    data object Idle : InsightSyncStatus
    data object Syncing : InsightSyncStatus
    data class Error(val message: String) : InsightSyncStatus
}

/**
 * One-shot events emitted by [SettingsViewModel] for the UI to render as
 * snackbars. pr-δ: profile-delete success / failure outcomes.
 */
sealed interface SettingsEvent {
    data object ProfileDeleted : SettingsEvent
    data class ProfileDeleteFailed(val message: String) : SettingsEvent
}

class SettingsViewModel(
    private val store: CalibreCredentialStore,
    private val readerStore: ReaderPreferencesStore,
    private val syncStateDao: SyncStateDao,
    private val documentRepo: DocumentRepository,
    private val booksDir: File,
    private val aiRepository: AiRepository,
    private val insightSyncRepository: InsightSyncRepository? = null,
    private val insightDao: InsightDao? = null,
    private val syncEnqueuer: (Context) -> Unit = { SyncEnqueuer.enqueue(it, expedited = true, replaceExisting = true) },
) : ViewModel() {
    private val _calibre = MutableStateFlow(loadInitialCalibre())
    val calibre: StateFlow<CalibreUiState> = _calibre.asStateFlow()

    val readerPreferences: StateFlow<ReaderPreferences> = readerStore.flow

    private val _sync = MutableStateFlow(SyncUiState(hasCredentials = store.get() != null, lastSyncedAtMs = null))
    val sync: StateFlow<SyncUiState> = _sync.asStateFlow()

    private val _aiHealth = MutableStateFlow<AiHealthResponse?>(null)
    private val _lastInsightSync = MutableStateFlow<Long?>(null)
    private val _syncStatus = MutableStateFlow<InsightSyncStatus>(InsightSyncStatus.Idle)

    private val _deleteProfileInFlight = MutableStateFlow(false)
    val deleteProfileInFlight: StateFlow<Boolean> = _deleteProfileInFlight.asStateFlow()

    private val _events = MutableSharedFlow<SettingsEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<SettingsEvent> = _events.asSharedFlow()

    val ai: StateFlow<AiState> = combine(
        combine(aiRepository.config, aiRepository.preferences, _aiHealth) { c, p, h ->
            Triple(c, p, h)
        },
        _lastInsightSync,
        _syncStatus,
    ) { (c, p, h), lastSync, syncStatus ->
        AiState(
            config = c,
            preferences = p,
            health = h,
            lastInsightSyncMs = lastSync,
            syncStatus = syncStatus,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AiState(),
    )

    init {
        viewModelScope.launch {
            aiRepository.refresh()
            _aiHealth.value = aiRepository.fetchHealth()
            _lastInsightSync.value = insightDao?.latestSyncedAt()
        }
    }

    /** Refresh the AI health snapshot. Safe to call from the UI on screen entry. */
    fun refreshAiHealth() {
        viewModelScope.launch {
            _aiHealth.value = aiRepository.fetchHealth()
        }
    }

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

    fun resetSync(context: Context) {
        viewModelScope.launch {
            syncStateDao.clearAll()
            _sync.value = _sync.value.copy(lastSyncedAtMs = null)
            syncEnqueuer(context)
        }
    }

    fun removeAllBooks() {
        viewModelScope.launch {
            documentRepo.deleteAll(booksDir)
        }
    }

    fun toggleAi(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { aiRepository.setEnabled(enabled) }
        }
    }

    fun setStyleTone(tone: String) {
        viewModelScope.launch {
            runCatching { aiRepository.setStyleTone(tone) }
        }
    }

    fun setStyleLanguage(language: String) {
        viewModelScope.launch {
            runCatching { aiRepository.setStyleLanguage(language) }
        }
    }

    /**
     * pr-δ: delete the server-side reader profile. Idempotent — DELETE
     * `/ai/v1/profile` returns 204 whether or not a row existed. Re-entrancy
     * is guarded so a button mash can't fire two in-flight requests.
     */
    fun deleteReaderProfile() {
        if (_deleteProfileInFlight.value) return
        _deleteProfileInFlight.value = true
        viewModelScope.launch {
            try {
                aiRepository.deleteProfile()
                _events.tryEmit(SettingsEvent.ProfileDeleted)
            } catch (t: Throwable) {
                _events.tryEmit(
                    SettingsEvent.ProfileDeleteFailed(t.message ?: "Unknown error"),
                )
            } finally {
                _deleteProfileInFlight.value = false
            }
        }
    }

    /**
     * PR-η: user-initiated "Refresh insights" trigger from the AI section. Runs
     * the sync in-band (no debounce) so the spinner is visible. Updates the
     * displayed "last synced" timestamp on success.
     */
    fun refreshInsights() {
        val repo = insightSyncRepository ?: return
        viewModelScope.launch {
            _syncStatus.value = InsightSyncStatus.Syncing
            val result = repo.syncNow()
            _syncStatus.value = when (result) {
                is InsightSyncRepository.SyncResult.Synced,
                is InsightSyncRepository.SyncResult.Skipped -> InsightSyncStatus.Idle
                is InsightSyncRepository.SyncResult.Failed ->
                    InsightSyncStatus.Error(result.error.message ?: "Sync failed")
            }
            _lastInsightSync.value = insightDao?.latestSyncedAt() ?: _lastInsightSync.value
        }
    }
}

data class CalibreUiState(
    val baseUrl: String,
    val username: String,
    val password: String,
    val saved: Boolean,
)
