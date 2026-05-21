package io.theficos.ereader.data.ai

import io.theficos.ereader.core.metadata.MetadataBundle
import io.theficos.ereader.core.model.DocumentIdentity
import io.theficos.ereader.data.local.db.InsightDao
import io.theficos.ereader.data.local.db.InsightEntity
import io.theficos.ereader.data.local.db.ProgressDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * In-app cache + facade over [AiClient]. Holds the latest server config and
 * user preferences, and exposes simple suspend functions the UI uses.
 *
 * Caching: AiConfig and AiPreferences are cached in-process; the UI
 * subscribes to the StateFlows. They re-fetch on app launch via [refresh].
 *
 * PR-η / Lock #14 amendment: this file moved from `:app` to `:data:ai` and
 * gained a local-cache-first read path. The DAO is consulted FIRST for
 * `lookupInsight` and `getCachedInsight`. On a cache hit we never call the
 * network. On a cache miss we call the client and write the result back.
 *
 * Offline-graceful read (`getCachedInsight` only): on `IOException` we fall
 * back to `InsightDao.findAnyForIdentity` so the user can still see SOME
 * cached insight when the network is down. `AiHttpException` (401, 409, 5xx)
 * always propagates — we never serve stale data on auth or opt-out drift.
 */
class AiRepository(
    private val client: AiClient,
    private val insightDao: InsightDao,
    // pr-α (Bundle 3): the AiRepository owns the abandon/un-abandon
    // operations because they conceptually belong to the AI surface
    // (Reader Profile cares about abandoned books). The DAO is optional
    // so existing test sites that only stub `insightDao` keep compiling;
    // production wiring at di/AppContainer passes the real DAO.
    private val progressDao: ProgressDao? = null,
    // PR-γ (Bundle 3): preflight collaborators for refreshProfile(). Both are
    // nullable so test sites that only stub network/DAO keep compiling;
    // production wiring at di/AppContainer passes the real instances. When
    // null, the corresponding preflight step is treated as a no-op success
    // (i.e. it doesn't block refresh).
    private val syncRunner: ProfilePreflightSync? = null,
    private val libraryRunner: ProfilePreflightLibrary? = null,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    private val clock: () -> Long = System::currentTimeMillis,
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

    /**
     * Lookup-or-generate with local-first read.
     *
     * Local cache hit → return immediately, zero client calls.
     * Cache miss → call [AiClient.lookupInsight], write the response back to
     * the local cache, return.
     *
     * `IOException` is NOT swallowed here. `lookupInsight` is the active-fetch
     * path — when the user wants a fresh look we surface the network error
     * rather than substitute stale data. Use [getCachedInsight] for the
     * graceful-degrade variant.
     */
    suspend fun lookupInsight(
        identity: DocumentIdentity,
        bundle: MetadataBundle,
    ): BookInsightResponse {
        readLocal(identity)?.let { return it.toResponse() }
        val resp = client.lookupInsight(identity, bundle)
        writeLocalFromResponse(resp, identity)
        return resp
    }

    /**
     * Cache-only read with local-first short-circuit AND offline fallback.
     *
     *  - Local cache hit → return.
     *  - Server reports "not cached" (404 → [InsightNotCachedException]) → return null.
     *  - Network failure ([IOException], includes [java.net.SocketTimeoutException])
     *    → fall back to `findAnyForIdentity` (any cached row at this identity,
     *    regardless of style). Returns null if nothing is cached.
     *  - Any [AiHttpException] (401, 409, 5xx) → rethrow. Stale data is NEVER
     *    served on auth or opt-out drift.
     */
    suspend fun getCachedInsight(identity: DocumentIdentity): BookInsightResponse? {
        readLocal(identity)?.let { return it.toResponse() }
        return try {
            val resp = client.getInsight(identity)
            writeLocalFromResponse(resp, identity)
            resp
        } catch (_: InsightNotCachedException) {
            null
        } catch (_: IOException) {
            // Offline-graceful: any cached row at this identity, regardless of style.
            val key = identity.metadataId ?: identity.contentHash ?: return null
            insightDao.findAnyForIdentity(key)?.toResponse()
            // AiHttpException (401, 409, 5xx) intentionally propagates — see KDoc.
        }
    }

    suspend fun invalidate(identity: DocumentIdentity) {
        client.invalidateInsight(identity)
        // Eviction policy "none in v1": the local row stays until the next
        // sync (or next same-PK upsert) replaces it.
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

    // ---------- Reader Profile (pr-α / Bundle 3) ----------

    /**
     * Cache-only read of the most recent reader profile. Returns null on
     * 404 (no row exists yet — pr-β's `POST /ai/v1/profile/refresh`
     * writes the first one). Other non-2xx responses propagate.
     */
    suspend fun fetchProfile(): ReaderProfileResponseDto? =
        client.fetchProfile()

    /**
     * PR-γ: refresh the user's reader profile.
     *
     * Best-effort preflight (NTH #9, corrections.md PR-γ Required #6): we
     * drain pending progress sync + library upload BEFORE asking the server
     * to recompute. Preflight is *best-effort* — if either step throws or
     * returns failure we log via the [onPreflightDone] callback and continue
     * to the refresh call. Rationale: the user explicitly asked for a
     * refresh, so failing it on a transient sync error would burn quota and
     * confuse the UX. The caller can surface the preflight failure if it
     * matters.
     *
     * `onPreflightStart` / `onPreflightDone` let the ViewModel drive its
     * two-state Loading skeleton (`PreflightSyncing` → `Generating`) when
     * the screen is in an Empty state. When refreshing in place from a
     * Loaded state the VM ignores these callbacks (architect Finding #1 —
     * don't show a skeleton over an existing profile).
     */
    suspend fun refreshProfile(
        onPreflightStart: () -> Unit = {},
        onPreflightDone: (PreflightOutcome) -> Unit = {},
    ): ReaderProfileResponseDto {
        onPreflightStart()
        val outcome = runPreflightBestEffort()
        onPreflightDone(outcome)
        return client.refreshProfile()
    }

    private suspend fun runPreflightBestEffort(): PreflightOutcome {
        val progressOk = try {
            syncRunner?.runOnce() ?: true
        } catch (_: Throwable) {
            false
        }
        val libraryOk = try {
            libraryRunner?.runOnce() ?: true
        } catch (_: Throwable) {
            false
        }
        return PreflightOutcome(progressSyncOk = progressOk, libraryUploadOk = libraryOk)
    }

    /**
     * PR-γ (Lock #3 surface — exposed for PR-δ's Settings button). Server
     * returns 204 unconditionally; safe to call without a prior fetch.
     */
    suspend fun deleteProfile() {
        client.deleteProfile()
    }

    /**
     * Mark a book abandoned in the local Room DB. The row's `updatedAt`
     * is bumped to `now` so the sync orchestrator pushes it on the next
     * cycle and the server's `client_updated_at` LWW guard accepts the
     * change. No server call here — the abandon-mark travels with the
     * next progress push.
     *
     * Throws [IllegalStateException] if [progressDao] was not wired (test
     * harness that only stubs `insightDao`).
     */
    suspend fun markAbandoned(documentId: Long, now: Long = clock()) {
        val dao = checkNotNull(progressDao) {
            "AiRepository.markAbandoned requires a ProgressDao"
        }
        dao.markAbandoned(documentId, now)
    }

    /**
     * Inverse of [markAbandoned]: clears `abandonedAt` without touching
     * `percent`. Same LWW timestamp semantics as `markAbandoned`.
     */
    suspend fun unmarkAbandoned(documentId: Long, now: Long = clock()) {
        val dao = checkNotNull(progressDao) {
            "AiRepository.unmarkAbandoned requires a ProgressDao"
        }
        dao.unmarkAbandoned(documentId, now)
    }

    // ---------- internals ----------

    /** Exact-identity cache read keyed on the current `(model, prompt, style)`. */
    private suspend fun readLocal(identity: DocumentIdentity): InsightEntity? {
        val cfg = _config.value ?: return null
        val prefs = _prefs.value ?: return null
        val key = identity.metadataId ?: identity.contentHash ?: return null
        val modelId = cfg.modelId ?: return null
        return insightDao.getByIdentity(
            identityKey = key,
            modelId = modelId,
            promptVersion = cfg.promptVersion,
            tone = prefs.style.tone,
            language = prefs.style.language,
        )
    }

    /**
     * Write-back path for both `client.lookupInsight` and `client.getInsight`
     * 200 responses. `BookInsightResponse` doesn't carry a server PK; we use
     * `0L` as a placeholder for `serverId` here — the row is still reachable
     * by its identity-style PK, and the next `/sync` will overwrite it with
     * the real `serverId` if needed for cursor reconstruction.
     *
     * Silently no-ops when prefs aren't bootstrapped (no style to key on).
     */
    private suspend fun writeLocalFromResponse(
        resp: BookInsightResponse,
        identity: DocumentIdentity,
    ) {
        val key = identity.metadataId ?: identity.contentHash ?: return
        val prefs = _prefs.value ?: return
        val entity = InsightEntity(
            identityKey = key,
            metadataId = identity.metadataId,
            contentHash = identity.contentHash,
            modelId = resp.modelId,
            promptVersion = resp.promptVersion,
            tone = prefs.style.tone,
            language = prefs.style.language,
            payloadJson = json.encodeToString(BookInsightPayload.serializer(), resp.payload),
            sourcesJson = json.encodeToString(ListSerializer(Citation.serializer()), resp.sources),
            schemaVersion = resp.payload.schemaVersion,
            serverId = 0L,
            generatedAt = parseIsoMillis(resp.generatedAt) ?: clock(),
            syncedAt = clock(),
        )
        insightDao.upsert(entity)
    }

    private fun InsightEntity.toResponse(): BookInsightResponse {
        val payload = json.decodeFromString(BookInsightPayload.serializer(), payloadJson)
        val sources = json.decodeFromString(
            ListSerializer(Citation.serializer()),
            sourcesJson,
        )
        return BookInsightResponse(
            payload = payload,
            sources = sources,
            modelId = modelId,
            promptVersion = promptVersion,
            generatedAt = Instant.ofEpochMilli(generatedAt).toString(),
        )
    }
}

/**
 * Outcome of the best-effort preflight pass before a profile refresh. Both
 * booleans are `true` on success; either may be `false` if the corresponding
 * runner threw OR was wired and reported a non-success. The refresh proceeds
 * regardless — the ViewModel surfaces [anyFailed] as a soft inline hint.
 */
data class PreflightOutcome(
    val progressSyncOk: Boolean,
    val libraryUploadOk: Boolean,
) {
    val anyFailed: Boolean get() = !progressSyncOk || !libraryUploadOk
}

/**
 * Decoupling boundary: lets `:data:ai` invoke the sync orchestrator without
 * taking a hard dependency on `:data:sync`. AppContainer adapts the real
 * SyncOrchestrator into this shape.
 */
fun interface ProfilePreflightSync {
    /** Returns true on success; false signals a non-fatal failure. */
    suspend fun runOnce(): Boolean
}

/**
 * Decoupling boundary: lets `:data:ai` invoke the library uploader without
 * taking a hard dependency on `:data:library`. AppContainer adapts the real
 * LibraryUploader into this shape.
 */
fun interface ProfilePreflightLibrary {
    /** Returns true on success; false signals a non-fatal failure (e.g. 401). */
    suspend fun runOnce(): Boolean
}

/** Best-effort ISO-8601 → epoch millis. Returns null on malformed input. */
internal fun parseIsoMillis(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    return try {
        Instant.parse(iso).toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }
}
