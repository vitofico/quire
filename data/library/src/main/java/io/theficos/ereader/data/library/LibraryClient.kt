package io.theficos.ereader.data.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

class LibraryHttpException(val code: Int, val body: String) :
    RuntimeException("library request failed: $code body=${body.take(200)}")
