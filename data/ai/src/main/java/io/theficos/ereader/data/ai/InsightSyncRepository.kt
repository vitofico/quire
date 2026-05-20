package io.theficos.ereader.data.ai

import android.util.Log
import io.theficos.ereader.data.local.db.InsightDao
import io.theficos.ereader.data.local.db.InsightEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * PR-η: orchestrates `GET /ai/v1/insights/sync` against the local cache.
 *
 * Two entry points:
 *  - [requestSync] — fire-and-forget, coalesces bursts within
 *    [DEBOUNCE_MS]. Used by app start, post-upload, post-promote.
 *  - [syncNow] — in-band, immediate. Used by the Settings "Refresh
 *    insights" button.
 *
 * Single-flight: [syncNow] is guarded by a [Mutex]. Concurrent callers
 * block on the in-flight call rather than running a parallel burst.
 *
 * Cursor: derived from the local cache tip (`latestGeneratedAt`,
 * `latestServerId`). The server's tuple cursor (Lock #23) is strict
 * `>`, so re-syncing from the local tip never re-fetches what we already
 * have. We do NOT persist the cursor in SharedPreferences — deriving from
 * `dao` is simpler and self-healing (clearing the cache resyncs everything).
 */
class InsightSyncRepository(
    private val client: AiClient,
    private val dao: InsightDao,
    private val aiRepo: AiRepository,
    private val scope: CoroutineScope,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    private val clock: () -> Long = System::currentTimeMillis,
    private val debounceMs: Long = DEBOUNCE_MS,
) {
    private val mutex = Mutex()

    @Volatile
    private var pendingJob: Job? = null

    sealed interface SyncResult {
        data class Synced(val pages: Int, val items: Int) : SyncResult
        data class Skipped(val reason: String) : SyncResult
        data class Failed(val error: Throwable) : SyncResult
    }

    /**
     * Coalescing trigger. Multiple calls within [debounceMs] collapse to a
     * single eventual [syncNow]. Returns immediately.
     */
    fun requestSync(reason: String) {
        val existing = pendingJob
        if (existing != null && existing.isActive) return
        pendingJob = scope.launch {
            delay(debounceMs)
            runCatching { syncNow() }
                .onFailure { Log.w(TAG, "syncNow($reason) failed", it) }
        }
    }

    /**
     * Immediate, in-band sync. Walks every page until `next_cursor=null`.
     * Returns [SyncResult.Skipped] when AI is disabled or the user is opted
     * out — never makes a network call in those cases.
     */
    suspend fun syncNow(): SyncResult = mutex.withLock {
        val cfg = aiRepo.config.value
        val prefs = aiRepo.preferences.value
        if (cfg?.configured != true || prefs?.aiEnabled != true) {
            return SyncResult.Skipped("not_enabled")
        }

        var cursor: InsightSyncCursor? = run {
            val gen = dao.latestGeneratedAt() ?: return@run null
            val id = dao.latestServerId() ?: return@run null
            InsightSyncCursor(generatedAt = Instant.ofEpochMilli(gen).toString(), id = id)
        }

        var pages = 0
        var items = 0
        while (true) {
            val resp = try {
                client.syncInsights(cursor = cursor, limit = SYNC_PAGE_LIMIT)
            } catch (e: Throwable) {
                return SyncResult.Failed(e)
            }
            if (resp.items.isNotEmpty()) {
                val now = clock()
                dao.upsertAll(resp.items.map { it.toEntity(now, json) })
            }
            pages += 1
            items += resp.items.size
            cursor = resp.nextCursor ?: break
        }
        SyncResult.Synced(pages = pages, items = items)
    }

    private companion object {
        const val TAG = "InsightSyncRepo"
        const val DEBOUNCE_MS = 2_000L
        const val SYNC_PAGE_LIMIT = 200
    }
}

/**
 * Map a sync-API item to a Room row. The identityKey rule mirrors
 * `AiRepository.readLocal`: `metadataId ?: contentHash`. Server-side
 * `LibraryItem.content_hash` is NOT NULL, so the fallback always resolves.
 */
internal fun InsightSyncItem.toEntity(syncedAtMs: Long, json: Json): InsightEntity {
    val key = identity.metadataId ?: identity.contentHash
        ?: error("InsightSyncItem identity has neither metadataId nor contentHash")
    return InsightEntity(
        identityKey = key,
        metadataId = identity.metadataId,
        contentHash = identity.contentHash,
        modelId = modelId,
        promptVersion = promptVersion,
        tone = tone,
        language = language,
        payloadJson = json.encodeToString(BookInsightPayload.serializer(), payload),
        sourcesJson = json.encodeToString(ListSerializer(Citation.serializer()), sources),
        schemaVersion = schemaVersion,
        serverId = id,
        generatedAt = parseIsoMillis(generatedAt) ?: syncedAtMs,
        syncedAt = syncedAtMs,
    )
}
