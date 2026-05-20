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

    suspend fun getCachedInsight(identity: DocumentIdentity): BookInsightResponse? =
        try {
            client.getInsight(identity)
        } catch (_: InsightNotCachedException) {
            null
        }

    suspend fun invalidate(identity: DocumentIdentity) {
        client.invalidateInsight(identity)
    }

    /**
     * PR-ζ — best-effort promote. Returns `true` on a confirmed promotion
     * (fresh OR idempotent). Returns `false` on 204 (nothing to promote) or
     * any thrown error — the caller degrades to today's "regenerate on open"
     * behavior. Boolean return shape is load-bearing: PR-η wraps this call
     * site to chain a sync trigger on `true` (coordinator §3.17).
     */
    suspend fun promoteInsight(
        from: DocumentIdentity,
        to: DocumentIdentity,
        tone: String,
        language: String,
    ): Boolean =
        runCatching { client.promoteInsight(from, to, tone, language)?.promoted == true }
            .getOrElse { false }

    /**
     * One-shot fetch of the server's AI-provider and retrieval-source health.
     *
     * Returns `null` on any HTTP error (including a 404 when the server runs
     * with AI disabled). The Settings screen treats `null` as "hide the
     * status row" — never surfaces a stack trace.
     */
    suspend fun fetchHealth(): AiHealthResponse? =
        runCatching { client.getHealth() }.getOrNull()
}
