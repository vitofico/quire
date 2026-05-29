package io.theficos.ereader.data.opds

import io.theficos.ereader.core.metadata.MetadataBundle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * On-device ISBN -> MetadataBundle lookup via the OpenLibrary Books API.
 * Plain OkHttp with NO account auth (talks to a third-party host). Returns
 * null on not-found / network / parse failure.
 */
class OpenLibraryClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
    private val baseUrl: String = "https://openlibrary.org",
) {
    fun lookupByIsbn(isbn13: String): MetadataBundle? {
        val url = "$baseUrl/api/books?bibkeys=ISBN:$isbn13&format=json&jscmd=data"
        val req = Request.Builder().url(url)
            .header("User-Agent", "QuireAndroid (book-scan)")
            .header("Accept", "application/json")
            .get().build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val root = Json.parseToJsonElement(resp.body?.string().orEmpty()) as? JsonObject
                    ?: return null
                val rec = root["ISBN:$isbn13"] as? JsonObject ?: return null
                val title = (rec["title"] as? JsonPrimitive)?.content
                    ?: return null
                val author = (rec["authors"] as? JsonArray)?.firstOrNull()
                    ?.jsonObject?.get("name")?.jsonPrimitive?.content
                val subjects = (rec["subjects"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.content }
                    ?: emptyList()
                MetadataBundle(
                    title = title,
                    author = author,
                    isbn = isbn13,
                    subjects = subjects,
                )
            }
        }.getOrNull()
    }
}
