package io.theficos.ereader.data.ai

import io.theficos.ereader.core.metadata.MetadataBundle
import io.theficos.ereader.core.model.DocumentIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * REST client for the AI endpoints on quire-server.
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

    /** Operational health snapshot for the AI provider + retrieval sources. */
    suspend fun getHealth(): AiHealthResponse =
        get("/ai/v1/health")

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

    /** Cache-only read. Throws [InsightNotCachedException] on 404. */
    suspend fun getInsight(identity: DocumentIdentity): BookInsightResponse =
        try {
            post("/ai/v1/insights/get", InsightGetBody(identity))
        } catch (e: AiHttpException) {
            if (e.code == 404) throw InsightNotCachedException() else throw e
        }

    /**
     * pr-α: cache-only read of the user's most recent reader profile.
     * Returns null on 404 (no row written yet — pr-β's
     * `POST /ai/v1/profile/refresh` writes the first one). Any other
     * non-2xx response propagates as [AiHttpException].
     *
     * No opt-in gate on the server side: opted-out users can still read
     * their last generation.
     */
    suspend fun fetchProfile(): ReaderProfileResponseDto? =
        try {
            get("/ai/v1/profile")
        } catch (e: AiHttpException) {
            if (e.code == 404) null else throw e
        }

    suspend fun invalidateInsight(identity: DocumentIdentity) {
        postUnit("/ai/v1/insights/invalidate", InsightGetBody(identity))
    }

    /**
     * PR-ζ: promote a cached catalog-side insight onto the post-download
     * canonical identity. Returns null when the server returns 204 ("nothing
     * to promote" — no source row at `from` for this variant); throws on any
     * other non-2xx response. Idempotent: a second identical call returns
     * [InsightPromoteResponse.alreadyPromoted] = true.
     */
    suspend fun promoteInsight(
        from: DocumentIdentity,
        to: DocumentIdentity,
        tone: String = "neutral",
        language: String = "auto",
    ): InsightPromoteResponse? =
        postOrNull(
            "/ai/v1/insights/promote",
            InsightPromoteBody(from, to, tone, language),
        )

    /**
     * PR-η: read-only, paginated bulk export of the caller's owned-book
     * insights at their current `(model_id, prompt_version, tone, language)`
     * variant. Weight=0 — never charges against the daily budget.
     *
     * Uses OkHttp's [HttpUrl.Builder] so the cursor's ISO 8601 timestamp's
     * `+` is percent-encoded correctly.
     */
    suspend fun syncInsights(
        cursor: InsightSyncCursor? = null,
        limit: Int = 50,
    ): InsightSyncResponse = withContext(Dispatchers.IO) {
        val builder = "${resolveBaseUrl()}/ai/v1/insights/sync".toHttpUrl().newBuilder()
        builder.addQueryParameter("limit", limit.toString())
        if (cursor != null) {
            builder.addQueryParameter("since_ts", cursor.generatedAt)
            builder.addQueryParameter("since_id", cursor.id.toString())
        }
        http.newCall(Request.Builder().url(builder.build()).get().build())
            .execute()
            .use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw makeError(resp.code, body)
                json.decodeFromString<InsightSyncResponse>(body)
            }
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

    /** POST that decodes 200, returns null on 204, and raises otherwise. */
    private suspend inline fun <reified Body, reified Resp> postOrNull(
        path: String,
        body: Body,
    ): Resp? = withContext(Dispatchers.IO) {
        http.newCall(
            Request.Builder()
                .url("${resolveBaseUrl()}$path")
                .post(json.encodeToString(body).toRequestBody(mediaType))
                .build(),
        ).execute().use { resp ->
            if (resp.code == 204) return@use null
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw makeError(resp.code, text)
            json.decodeFromString<Resp>(text)
        }
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
