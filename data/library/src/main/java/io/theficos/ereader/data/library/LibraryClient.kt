package io.theficos.ereader.data.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * REST client for the `/library/v1` endpoints on opds-sync.
 *
 * Auth: relies on the shared OkHttpClient already carrying Basic auth (the
 * same one used by `:data:sync` and `:data:ai`). This client does not add
 * Authorization headers.
 *
 * v0 (PR9) exposes only `getStats`; future additions (`putItem`, `listItems`,
 * `deleteItem`) will move here when Android-side library upload lands.
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
}

class LibraryHttpException(val code: Int, val body: String) :
    RuntimeException("library request failed: $code body=${body.take(200)}")
