package io.theficos.ereader.data.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * REST client for the `/library/v1` endpoints on quire-server.
 *
 * Auth: relies on the shared OkHttpClient already carrying Basic auth (the
 * same one used by `:data:sync` and `:data:ai`). This client does not add
 * Authorization headers.
 *
 * Exposed verbs: `getStats` (PR9) and `putItem` (this PR, drives the Android
 * → server upload). `listItems` / `deleteItem` aren't needed yet — the
 * Android library is treated as the source of truth and tombstone delivery
 * will land when multi-device delete arrives.
 */
class LibraryClient(
    private val baseUrlProvider: () -> String?,
    private val http: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /**
     * Json instance used for `/library/v1/sync` (Phase 0, task A-4).
     *
     * Strict mode (`encodeDefaults = true`) so every entry serializes
     * with the full X-1 wire shape — explicit `identity_hash_version`,
     * explicit `status`, empty `authors`/`subjects` lists — rather than
     * relying on the server's default-fill paths. The server uses
     * Pydantic `extra="forbid"` so any unknown field becomes a 422, but
     * missing-with-default fields are accepted; emitting them explicitly
     * keeps the wire trace auditable and decouples Android from any
     * future server default changes.
     *
     * `ignoreUnknownKeys = true` on response decode lets a Phase-1 server
     * add new summary counters without breaking older clients.
     */
    private val syncJson: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun resolveBaseUrl(): String {
        val raw = baseUrlProvider()
        if (raw.isNullOrBlank()) {
            throw LibraryHttpException(0, "baseUrl not configured")
        }
        return raw.trimEnd('/')
    }

    suspend fun getStats(): LibraryStatsResponse = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(resolveBaseUrl() + LibraryApi.PATH_STATS)
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw LibraryHttpException(resp.code, body)
            json.decodeFromString(LibraryStatsResponse.serializer(), body)
        }
    }

    /**
     * Upsert a single library item.
     *
     * Server semantics:
     * - 200 with the persisted row on insert OR update (server keys on
     *   `(user_id, content_hash)` and refreshes `updated_at`).
     * - 401 → caller needs to re-auth; surfaces as `LibraryHttpException(401)`.
     * - 409 → `metadata_id` is already attached to a DIFFERENT content_hash for
     *   this user. PR1's identity-aliases plan will fix this properly; for now
     *   we surface it so the uploader can skip the row without retrying.
     *
     * The wire body wraps the payload as `{"item": {...}}` so a future bulk
     * endpoint (`{"items": [...]}`) can ship without breaking existing
     * clients.
     */
    suspend fun putItem(payload: LibraryItemRequest): LibraryItemResponse = withContext(Dispatchers.IO) {
        val bodyJson = json.encodeToString(LibraryItemPutBody.serializer(), LibraryItemPutBody(payload))
        val req = Request.Builder()
            .url(resolveBaseUrl() + LibraryApi.PATH_ITEMS)
            .put(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw LibraryHttpException(resp.code, body)
            json.decodeFromString(LibraryItemResponse.serializer(), body)
        }
    }

    /**
     * Push a batch of library mirror entries to `POST /library/v1/sync`.
     *
     * Wire contract (Phase 0 / X-1):
     * - Body shape: `{"items": [LibrarySyncEntry, ...]}`.
     * - Identity field is `identity_hash`. Sending `content_hash` here is
     *   rejected with 422 (Pydantic `extra="forbid"`).
     * - Caller is responsible for chunking — server enforces a 500-item
     *   ceiling per request (`library_sync_max_items`); over → 422.
     *
     * Failure modes (callers map these to WorkManager outcomes):
     * - 401 → `LibraryHttpException(401)` — caller should fail without
     *   retry; credentials need attention.
     * - 4xx (other than 401/429) → `LibraryHttpException(code)` — caller
     *   should log + drop the batch, not retry forever.
     * - 429 / 5xx / network — `LibraryHttpException` or `IOException`
     *   propagated; caller should retry with backoff.
     */
    suspend fun syncBatch(payload: LibrarySyncRequest): LibrarySyncSummary = withContext(Dispatchers.IO) {
        val bodyJson = syncJson.encodeToString(LibrarySyncRequest.serializer(), payload)
        val req = Request.Builder()
            .url(resolveBaseUrl() + LibraryApi.PATH_SYNC)
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw LibraryHttpException(resp.code, body)
            syncJson.decodeFromString(LibrarySyncSummary.serializer(), body)
        }
    }

    /**
     * Page through `GET /library/v1/items`. `since=null` returns only live rows
     * (no tombstones); `limit`/`offset` are server-validated (1..1000, >=0).
     */
    suspend fun listItems(
        since: String?,
        limit: Int,
        offset: Int,
    ): LibraryItemListResponse = withContext(Dispatchers.IO) {
        val url = (resolveBaseUrl() + LibraryApi.PATH_ITEMS).toHttpUrl().newBuilder()
            .apply { if (since != null) addQueryParameter("since", since) }
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())
            .build()
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw LibraryHttpException(resp.code, body)
            json.decodeFromString(LibraryItemListResponse.serializer(), body)
        }
    }

    /**
     * Pull the entire live library mirror, paging until a short page. Bounded by
     * [maxPages] so a server that keeps returning full pages can't hang the
     * caller; on hitting the cap it invokes [onTruncated] with the count
     * collected so far (no silent truncation) and returns what it has.
     */
    suspend fun listAllItems(
        limit: Int = 200,
        maxPages: Int = 50,
        onTruncated: (collected: Int) -> Unit = {},
    ): List<LibraryItemResponse> {
        val all = mutableListOf<LibraryItemResponse>()
        var offset = 0
        var page = 0
        while (page < maxPages) {
            val resp = listItems(since = null, limit = limit, offset = offset)
            all += resp.items
            if (resp.items.size < limit) return all
            offset += limit
            page++
        }
        onTruncated(all.size)
        return all
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

class LibraryHttpException(val code: Int, val body: String) :
    RuntimeException("library request failed: $code body=${body.take(200)}")
