package io.theficos.ereader.data.ai

import io.theficos.ereader.core.metadata.MetadataBundle
import io.theficos.ereader.core.model.DocumentIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-app cache + facade over [AiClient]. Holds the latest server config and
 * user preferences, and exposes simple suspend functions the UI uses.
 *
 * Caching: AiConfig and AiPreferences are cached in-process; the UI
 * subscribes to the StateFlows. They re-fetch on app launch via [refresh].
 */
class AiRepository(
    private val client: AiClient,
) {
    private val _config = MutableStateFlow<AiConfig?>(null)
    val config: StateFlow<AiConfig?> = _config.asStateFlow()

    private val _prefs = MutableStateFlow<AiPreferences?>(null)
    val preferences: StateFlow<AiPreferences?> = _prefs.asStateFlow()

    private val refreshMutex = Mutex()

    /** Pull the latest config + preferences from the server. Silent on failure. */
    suspend fun refresh() = refreshMutex.withLock {
        runCatching { client.getConfig() }.getOrNull()?.let { _config.value = it }
        runCatching { client.getPreferences() }.getOrNull()?.let { _prefs.value = it }
    }

    suspend fun setEnabled(enabled: Boolean) {
        val out = client.setPreferences(enabled = enabled)
        _prefs.value = out
    }

    suspend fun setStyleTone(tone: String) {
        // Preserve `language` (and any other future style knobs) when only
        // tone is being changed. Falls back to defaults if no prefs are
        // loaded yet.
        val current = _prefs.value?.style ?: AiStyle()
        val out = client.setPreferences(style = current.copy(tone = tone))
        _prefs.value = out
    }

    suspend fun setStyleLanguage(language: String) {
        val current = _prefs.value?.style ?: AiStyle()
        val out = client.setPreferences(style = current.copy(language = language))
        _prefs.value = out
    }

    suspend fun lookupInsight(
        identity: DocumentIdentity,
        bundle: MetadataBundle,
    ): BookInsightResponse = client.lookupInsight(identity, bundle)

    /** User requested a re-generation with a reason. Counts against regen daily limit. */
    suspend fun regenerateInsight(
        identity: DocumentIdentity,
        bundle: MetadataBundle,
        reason: String,
    ): BookInsightResponse = client.regenerateInsight(identity, bundle, reason)

    suspend fun getCachedInsight(identity: DocumentIdentity): BookInsightResponse? =
        try {
            client.getInsight(identity)
        } catch (_: InsightNotCachedException) {
            null
        }

    suspend fun invalidate(identity: DocumentIdentity) {
        client.invalidateInsight(identity)
    }
}
