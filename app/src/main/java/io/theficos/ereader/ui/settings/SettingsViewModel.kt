package io.theficos.ereader.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.ereader.auth.AccountCredentials
import io.theficos.ereader.auth.CalibreCredentialStore
import io.theficos.ereader.data.ai.AiConfig
import io.theficos.ereader.data.ai.AiHealthResponse
import io.theficos.ereader.data.ai.AiPreferences
import io.theficos.ereader.data.ai.AiRepository
import io.theficos.ereader.data.ai.InsightSyncRepository
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.local.db.InsightDao
import io.theficos.ereader.data.local.db.SyncStateDao
import io.theficos.ereader.data.opds.normalizeCatalogUrl
import io.theficos.ereader.data.sync.SyncEnqueuer
import io.theficos.ereader.domain.restore.RestoreProgress
import io.theficos.ereader.domain.restore.RestoreSummary
import io.theficos.ereader.reader.ReaderFontFamily
import io.theficos.ereader.reader.ReaderPreferences
import io.theficos.ereader.reader.ReaderPreferencesStore
import io.theficos.ereader.reader.ReaderTheme
import io.theficos.ereader.ui.UiText
import io.theficos.ereader.ui.library.LibraryPreferencesStore
import io.theficos.ereader.ui.onboarding.ConnectServerViewModel
import io.theficos.ereader.ui.onboarding.PairingCode
import io.theficos.ereader.ui.onboarding.requestPairingCode
import io.theficos.ereader.ui.theme.AppTheme
import io.theficos.ereader.ui.theme.AppThemePreferencesStore
import io.theficos.ereader.ui.theme.BackgroundStyle
import io.theficos.ereader.ui.uiText
import io.theficos.quire.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val selectingProvider: Boolean = false,
    val providerChangeFailed: Boolean = false,
)

/** UI state for the Settings → "Refresh insights" button. */
sealed interface InsightSyncStatus {
    data object Idle : InsightSyncStatus
    data object Syncing : InsightSyncStatus
    data class Error(val message: UiText) : InsightSyncStatus
}

/**
 * One-shot events emitted by [SettingsViewModel] for the UI to render as
 * snackbars. pr-δ: profile-delete success / failure outcomes.
 */
sealed interface SettingsEvent {
    data object ProfileDeleted : SettingsEvent
    data class ProfileDeleteFailed(val message: UiText) : SettingsEvent
    data class RestoreFinished(val summary: RestoreSummary) : SettingsEvent
    data class RestoreFailed(val message: String) : SettingsEvent
}

class SettingsViewModel(
    private val store: CalibreCredentialStore,
    private val readerStore: ReaderPreferencesStore,
    private val syncStateDao: SyncStateDao,
    private val documentRepo: DocumentRepository,
    private val booksDir: File,
    private val aiRepository: AiRepository,
    private val appThemeStore: AppThemePreferencesStore,
    private val insightSyncRepository: InsightSyncRepository? = null,
    private val insightDao: InsightDao? = null,
    private val syncEnqueuer: (Context) -> Unit = { SyncEnqueuer.enqueue(it, expedited = true, replaceExisting = true) },
    private val restoreInProgress: (suspend ((Int, Int) -> Unit) -> RestoreSummary)? = null,
    private val libraryPreferencesStore: LibraryPreferencesStore,
    /** True while found book details are stored, so their switch shows even with AI off. */
    hasStoredDetails: Flow<Boolean> = flowOf(false),
) : ViewModel() {
    val account = store.accountFlow

    /** "Tidy up book details": the server may find and tidy details and covers for library books. */
    val tidyDetails: StateFlow<Boolean> = libraryPreferencesStore.flow
        .map { it.tidyDetails }
        .stateIn(viewModelScope, SharingStarted.Eagerly, libraryPreferencesStore.flow.value.tidyDetails)

    val hasStoredDetails: StateFlow<Boolean> = hasStoredDetails.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setTidyDetails(on: Boolean) = libraryPreferencesStore.updateTidyDetails(on)

    private val _pairing = MutableStateFlow<PairingState>(PairingState.Hidden)

    /** The "Add another device" QR code: a short-lived ticket for this Quire account. */
    val pairing: StateFlow<PairingState> = _pairing.asStateFlow()

    fun showPairingCode() {
        val native = account.value as? AccountCredentials.Bearer ?: return
        _pairing.value = PairingState.Loading
        viewModelScope.launch {
            val code = withContext(Dispatchers.IO) {
                requestPairingCode(ConnectServerViewModel.defaultOnboardingClient(), native)
            }
            if (_pairing.value == PairingState.Loading) {
                _pairing.value = code?.let(PairingState::Ready) ?: PairingState.Failed
            }
        }
    }

    fun hidePairingCode() { _pairing.value = PairingState.Hidden }
    private val initialAccount = account.value
    val needsReauth = store.needsReauth

    private val _calibre = MutableStateFlow(loadInitialCalibre())

    /** The legacy Calibre-Web sign-in's own form. Empty unless the device is signed in that way. */
    val calibre: StateFlow<CalibreUiState> = _calibre.asStateFlow()

    val readerPreferences: StateFlow<ReaderPreferences> = readerStore.flow
    val appTheme: StateFlow<AppTheme> = appThemeStore.flow
    val backgroundStyle: StateFlow<BackgroundStyle> = appThemeStore.backgroundStyle

    fun setBackgroundStyle(style: BackgroundStyle) { appThemeStore.updateBackgroundStyle(style) }

    private val _sync = MutableStateFlow(
        SyncUiState(
            hasCredentials = initialAccount?.quireSession() != null,
            lastSyncedAtMs = null,
        )
    )
    val sync: StateFlow<SyncUiState> = _sync.asStateFlow()

    private val _aiHealth = MutableStateFlow<AiHealthResponse?>(null)
    private val _lastInsightSync = MutableStateFlow<Long?>(null)
    private val _syncStatus = MutableStateFlow<InsightSyncStatus>(InsightSyncStatus.Idle)

    private val _deleteProfileInFlight = MutableStateFlow(false)
    val deleteProfileInFlight: StateFlow<Boolean> = _deleteProfileInFlight.asStateFlow()

    private val _events = MutableSharedFlow<SettingsEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<SettingsEvent> = _events.asSharedFlow()

    /**
     * True when a SERVER-BACKED account is configured, gating the restore action.
     *
     * Restore reads the library mirror off quire-server, so a device with only
     * catalogs can never satisfy it: RestoreInProgressUseCase reaches
     * LibraryClient.resolveBaseUrl() on its first statement and throws because
     * quireServerUrlOrNull() is null. Offering the action anyway put an internal
     * exception string in front of a user who had done nothing wrong.
     */
    val isConnected: StateFlow<Boolean> =
        store.accountFlow
            .map { it != null }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                store.accountFlow.value != null,
            )

    private val _restoreRunning = MutableStateFlow(false)
    val restoreRunning: StateFlow<Boolean> = _restoreRunning.asStateFlow()

    private val _restoreProgress = MutableStateFlow<RestoreProgress?>(null)
    val restoreProgress: StateFlow<RestoreProgress?> = _restoreProgress.asStateFlow()

    private val _selectingProvider = MutableStateFlow(false)
    private val _providerChangeFailed = MutableStateFlow(false)

    val ai: StateFlow<AiState> = combine(
        combine(aiRepository.config, aiRepository.preferences, _aiHealth) { c, p, h ->
            Triple(c, p, h)
        },
        _lastInsightSync,
        _syncStatus,
        _selectingProvider,
        _providerChangeFailed,
    ) { (c, p, h), lastSync, syncStatus, selecting, failed ->
        AiState(
            config = c,
            preferences = p,
            health = h,
            lastInsightSyncMs = lastSync,
            syncStatus = syncStatus,
            selectingProvider = selecting,
            providerChangeFailed = failed,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AiState(),
    )

    init {
        viewModelScope.launch {
            var previous = initialAccount
            account.collect { current ->
                if (current == previous) return@collect
                val previousSession = previous?.quireSession()
                previous = current
                _calibre.value = loadInitialCalibre(current)
                val session = current?.quireSession()
                _sync.value = _sync.value.copy(hasCredentials = session != null)
                if (session != previousSession) {
                    _aiHealth.value = null
                    if (session != null) refreshAiHealth()
                }
            }
        }
        viewModelScope.launch {
            aiRepository.refresh()
            _aiHealth.value = aiRepository.fetchHealth()
            _lastInsightSync.value = insightDao?.latestSyncedAt()
        }
    }

    private fun AccountCredentials.quireSession(): AccountCredentials? = when (this) {
        is AccountCredentials.Bearer -> copy(previousHighlightScopes = emptyList())
        is AccountCredentials.Basic -> this
        is AccountCredentials.OpdsOnly -> null
    }

    private fun refreshAiHealth() {
        viewModelScope.launch {
            val session = account.value?.quireSession()
            aiRepository.refresh()
            val health = aiRepository.fetchHealth()
            if (session == account.value?.quireSession()) _aiHealth.value = health
        }
    }

    private fun loadInitialCalibre(current: AccountCredentials? = account.value): CalibreUiState {
        val legacy = current as? AccountCredentials.Basic
        return CalibreUiState(
            baseUrl = legacy?.baseUrl.orEmpty(),
            username = legacy?.username.orEmpty(),
            password = legacy?.password.orEmpty(),
            quireServerUrl = legacy?.quireServerUrl.orEmpty(),
            saved = legacy != null,
        )
    }

    fun onBaseUrlChange(value: String) { _calibre.value = _calibre.value.copy(baseUrl = value, saved = false, error = null) }
    fun onUsernameChange(value: String) { _calibre.value = _calibre.value.copy(username = value, saved = false) }
    fun onPasswordChange(value: String) { _calibre.value = _calibre.value.copy(password = value, saved = false) }
    fun onQuireServerUrlChange(value: String) {
        _calibre.value = _calibre.value.copy(quireServerUrl = value, saved = false, error = null)
    }

    /** Save the legacy sign-in's form. Calibre-Web is no longer a sign-in for anyone else. */
    fun saveCalibre() {
        val form = _calibre.value
        if (account.value !is AccountCredentials.Basic) return
        if (form.username.isBlank() || form.password.isBlank()) return
        val baseUrl = normalizeCatalogUrl(form.baseUrl)?.takeUnless { '?' in it }?.trimEnd('/')
        val quireUrl = normalizeCatalogUrl(form.quireServerUrl)?.takeUnless { '?' in it }?.trimEnd('/')
        if (baseUrl == null || (form.quireServerUrl.isNotBlank() && quireUrl == null)) {
            _calibre.value = form.copy(error = uiText(R.string.onboarding_invalid_quire_url))
            return
        }
        store.saveBasicAccount(baseUrl, form.username, form.password, quireUrl)
        _calibre.value = loadInitialCalibre()
    }

    /**
     * Sign out of the Quire account or the legacy sign-in. With [keepCatalogs] the catalogs stay on
     * this device for reading; without it the device forgets them. The Quire account keeps its copy.
     */
    fun signOut(keepCatalogs: Boolean) {
        store.signOut(keepCatalogs)
    }

    fun setAppTheme(theme: AppTheme) { appThemeStore.update(theme) }
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

    fun setAiProvider(providerId: String) {
        if (_selectingProvider.value) return
        _selectingProvider.value = true
        _providerChangeFailed.value = false
        viewModelScope.launch {
            try {
                aiRepository.setProvider(providerId)
                _aiHealth.value = aiRepository.fetchHealth()
            } catch (_: Exception) {
                _providerChangeFailed.value = true
            } finally {
                _selectingProvider.value = false
            }
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
                    SettingsEvent.ProfileDeleteFailed(t.message?.let(UiText::Dynamic) ?: uiText(R.string.common_unknown_error)),
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
                    InsightSyncStatus.Error(result.error.message?.let(UiText::Dynamic) ?: uiText(R.string.common_sync_failed))
            }
            _lastInsightSync.value = insightDao?.latestSyncedAt() ?: _lastInsightSync.value
        }
    }

    /**
     * Task 5: user-initiated "Restore in-progress books" trigger from the
     * Storage & sync section. Runs [RestoreInProgressUseCase] and emits a
     * one-shot [SettingsEvent.RestoreFinished] (or [SettingsEvent.RestoreFailed])
     * for the UI to render as a snackbar. Re-entrancy is guarded so a button
     * mash can't fire two concurrent restores.
     */
    fun restoreInProgressBooks() {
        val restore = restoreInProgress ?: return
        if (_restoreRunning.value) return
        _restoreRunning.value = true
        viewModelScope.launch {
            try {
                _events.tryEmit(
                    SettingsEvent.RestoreFinished(
                        restore { done, total -> _restoreProgress.value = RestoreProgress(done, total) },
                    ),
                )
            } catch (t: Throwable) {
                _events.tryEmit(SettingsEvent.RestoreFailed(t.message ?: t.javaClass.simpleName))
            } finally {
                _restoreRunning.value = false
                _restoreProgress.value = null
            }
        }
    }
}

sealed interface PairingState {
    data object Hidden : PairingState
    data object Loading : PairingState
    data object Failed : PairingState
    data class Ready(val code: PairingCode) : PairingState
}

data class CalibreUiState(
    val baseUrl: String,
    val username: String,
    val password: String,
    val quireServerUrl: String,
    val saved: Boolean,
    val error: UiText? = null,
)
