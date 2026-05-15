package io.theficos.ereader.data.ai

import io.theficos.ereader.core.metadata.MetadataBundle
import io.theficos.ereader.core.model.DocumentIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * REST client for the AI endpoints on opds-sync.
 *
 * Auth: relies on the OkHttpClient already having BasicAuthInterceptor wired
 * (the same one used by :data:sync). This client does not add headers.
 */
class AiClient(
    private val baseUrlProvider: () -> String?,
    private val http: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    private fun resolveBaseUrl(): String {
        val raw = baseUrlProvider()
        if (raw.isNullOrBlank()) {
            throw AiHttpException(0, "baseUrl not configured")
        }
        return raw.trimEnd('/')
    }

    suspend fun getConfig(): AiConfig =
        get("/ai/v1/config")

    suspend fun getPreferences(): AiPreferences =
        get("/ai/v1/preferences")

    /** PUT preferences. Either or both fields may be sent; pass nulls for unchanged. */
    suspend fun setPreferences(
        enabled: Boolean? = null,
        style: AiStyle? = null,
    ): AiPreferences =
        put("/ai/v1/preferences", AiPreferencesBody(aiEnabled = enabled, style = style))

    /** Lookup-or-generate. May block for tens of seconds while a model runs. */
    suspend fun lookupInsight(
        identity: DocumentIdentity,
        bundle: MetadataBundle,
    ): BookInsightResponse =
        post("/ai/v1/insights/lookup", InsightLookupBody(identity, bundle))

    /** Force a fresh generation. Counts against regen daily limit. */
    suspend fun regenerateInsight(
        identity: DocumentIdentity,
        bundle: MetadataBundle,
        reason: String,
    ): BookInsightResponse =
        post("/ai/v1/insights/regenerate", InsightRegenerateBody(identity, bundle, reason))

    /** Cache-only read. Throws [InsightNotCachedException] on 404. */
    suspend fun getInsight(identity: DocumentIdentity): BookInsightResponse =
        try {
            post("/ai/v1/insights/get", InsightGetBody(identity))
        } catch (e: AiHttpException) {
            if (e.code == 404) throw InsightNotCachedException() else throw e
        }

    suspend fun invalidateInsight(identity: DocumentIdentity) {
        postUnit("/ai/v1/insights/invalidate", InsightGetBody(identity))
    }

    private suspend inline fun <reified T> get(path: String): T =
        execute(Request.Builder().url("${resolveBaseUrl()}$path").get())

    private suspend inline fun <reified Body, reified Resp> post(path: String, body: Body): Resp =
        execute(
            Request.Builder()
                .url("${resolveBaseUrl()}$path")
                .post(json.encodeToString(body).toRequestBody(mediaType))
        )

    private suspend inline fun <reified Body> postUnit(path: String, body: Body) {
        executeRaw(
            Request.Builder()
                .url("${resolveBaseUrl()}$path")
                .post(json.encodeToString(body).toRequestBody(mediaType))
        )
    }

    private suspend inline fun <reified Body, reified Resp> put(path: String, body: Body): Resp =
        execute(
            Request.Builder()
                .url("${resolveBaseUrl()}$path")
                .put(json.encodeToString(body).toRequestBody(mediaType))
        )

    private suspend inline fun <reified Resp> execute(builder: Request.Builder): Resp =
        withContext(Dispatchers.IO) {
            http.newCall(builder.build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw makeError(resp.code, body)
                }
                json.decodeFromString<Resp>(body)
            }
        }

    private suspend fun executeRaw(builder: Request.Builder) {
        withContext(Dispatchers.IO) {
            http.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw makeError(resp.code, resp.body?.string().orEmpty())
                }
            }
        }
    }

    /** Map an HTTP error to either AiQuotaException (429 with quota body) or AiHttpException. */
    private fun makeError(code: Int, body: String): RuntimeException {
        if (code == 429) {
            // 429 body shape from server: {detail: {used, limit, resets_at}}
            try {
                val parsed = json.parseToJsonElement(body) as? JsonObject
                val detail = parsed?.get("detail")
                if (detail != null) {
                    val info = json.decodeFromString(QuotaInfo.serializer(), detail.toString())
                    return AiQuotaException(info)
                }
            } catch (ignored: Exception) {
                // fall through to generic
            }
        }
        return AiHttpException(code, body)
    }
}

class AiHttpException(val code: Int, val body: String) :
    RuntimeException("AI request failed: $code body=${body.take(200)}")

class InsightNotCachedException : RuntimeException("insight not cached")

class AiQuotaException(val info: QuotaInfo) :
    RuntimeException("AI quota exhausted: ${info.used}/${info.limit}, resets at ${info.resetsAt}")
