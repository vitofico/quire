package io.theficos.ereader.data.opds

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * REST client for the Quire account's saved catalogs, `/catalogs/v1`. [http] must be the Quire
 * client, which carries the account's login and nothing else.
 *
 * Every failure is a [CatalogHttpException] with the status and the server's `detail` code, or an
 * `IOException` when the server could not be reached or its answer could not be read.
 */
class CatalogSyncClient(
    private val baseUrlProvider: () -> String?,
    private val http: OkHttpClient,
) {
    /** The account's list. Null when it still matches [revision]. */
    suspend fun list(revision: String?): RemoteCatalogList? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url("/catalogs/v1/catalogs")).get()
            .apply { if (revision != null) header("If-None-Match", "\"$revision\"") }
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == HTTP_NOT_MODIFIED) return@use null
            decode(response.successBody()) { json.decodeFromString(RemoteCatalogList.serializer(), it) }
        }
    }

    suspend fun secret(id: String): CatalogSecret = withContext(Dispatchers.IO) {
        call(Request.Builder().url(url("/catalogs/v1/catalogs/$id/secret")).get().build()) {
            json.decodeFromString(CatalogSecret.serializer(), it)
        }
    }

    suspend fun put(id: String, body: CatalogPut): RemoteCatalog = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(CatalogPut.serializer(), body).toRequestBody(JSON)
        call(Request.Builder().url(url("/catalogs/v1/catalogs/$id")).put(payload).build()) {
            json.decodeFromString(RemoteCatalog.serializer(), it)
        }
    }

    suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        call(Request.Builder().url(url("/catalogs/v1/catalogs/$id")).delete().build()) {}
    }

    suspend fun order(ids: List<String>): RemoteCatalogList = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(CatalogOrder.serializer(), CatalogOrder(ids)).toRequestBody(JSON)
        call(Request.Builder().url(url("/catalogs/v1/order")).put(payload).build()) {
            json.decodeFromString(RemoteCatalogList.serializer(), it)
        }
    }

    private fun url(path: String): String {
        val base = baseUrlProvider()?.trimEnd('/')
        if (base.isNullOrBlank()) throw CatalogHttpException(0, null)
        return base + path
    }

    private fun <T> call(request: Request, read: (String) -> T): T =
        http.newCall(request).execute().use { decode(it.successBody(), read) }

    /** A body that isn't the expected JSON (a captive portal's page, a cut-off answer) is a failed exchange. */
    private fun <T> decode(body: String, read: (String) -> T): T = try {
        read(body)
    } catch (e: SerializationException) {
        throw IOException("unreadable catalogs response", e)
    }

    private fun Response.successBody(): String {
        val body = body?.string().orEmpty()
        if (!isSuccessful) throw CatalogHttpException(code, detailCode(body))
        return body
    }

    private companion object {
        const val HTTP_NOT_MODIFIED = 304
        val JSON = "application/json; charset=utf-8".toMediaType()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        /** The server's rule code (`{"detail": "<code>"}`); null for schema errors and other bodies. */
        fun detailCode(body: String): String? = runCatching {
            (json.parseToJsonElement(body).jsonObject["detail"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        }.getOrNull()
    }
}

/** A `/catalogs/v1` call the server answered with an error. [detail] is its rule code, when it sent one. */
class CatalogHttpException(val code: Int, val detail: String?) :
    Exception("catalogs request failed: $code ${detail.orEmpty()}")

@Serializable
data class RemoteCatalogList(val revision: String, val catalogs: List<RemoteCatalog>)

/** A catalog as the account lists it: never its address or login. */
@Serializable
data class RemoteCatalog(
    val id: String,
    val name: String,
    val kind: String,
    val host: String,
    @SerialName("has_login") val hasLogin: Boolean = false,
    @SerialName("needs_login") val needsLogin: Boolean = false,
    val position: Int = 0,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class CatalogSecret(val url: String, val username: String? = null, val password: String? = null) {
    override fun toString() = "CatalogSecret(url=***, username=$username, password=***)"
}

@Serializable
data class CatalogPut(
    val name: String,
    val kind: String,
    val url: String,
    val username: String? = null,
    val password: String? = null,
) {
    override fun toString() = "CatalogPut(name=$name, kind=$kind, url=***, username=$username, password=***)"
}

@Serializable
private data class CatalogOrder(val ids: List<String>)
