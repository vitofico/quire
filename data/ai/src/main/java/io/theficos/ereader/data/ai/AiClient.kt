package io.theficos.ereader.data.ai

import io.theficos.ereader.core.metadata.MetadataBundle
import io.theficos.ereader.core.model.DocumentIdentity
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * REST client for the AI endpoints on quire-server.
 *
 * Auth: relies on the OkHttpClient already having AccountAuthInterceptor wired
 * (the same one used by :data:sync). The interceptor emits Basic or Bearer
 * per the configured account scheme. This client does not add headers.
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

    /** Last `generation_timeout_s` the server advertised; null until the first config read. */
    @Volatile
    private var generationTimeoutS: Int? = null

    /** Last `profile_timeout_s` the server advertised; null until the first config read (or on a server that omits the field). */
    @Volatile
    private var profileTimeoutS: Int? = null

    suspend fun getConfig(): AiConfig =
        get<AiConfig>("/ai/v1/config").also {
            generationTimeoutS = it.generationTimeoutS
            profileTimeoutS = it.profileTimeoutS
        }

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

    /** Lookup-or-generate. May block for minutes while a model runs; see [longCallHttp]. */
    suspend fun lookupInsight(
        identity: DocumentIdentity,
        bundle: MetadataBundle,
    ): BookInsightResponse =
        post(
            "/ai/v1/insights/lookup",
            InsightLookupBody(identity, bundle),
            client = longCallHttp(generationTimeoutS),
        )

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

    /**
     * PR-γ: kick off a server-side profile regeneration. May block for
     * minutes while the model runs; sized from `profile_timeout_s` when the
     * server advertises it, or from `generation_timeout_s` otherwise (see
     * [longCallHttp]). Throws [AiQuotaException] on 429 (Retry-After may be
     * embedded in the body), and [AiHttpException] on every other non-2xx
     * (409 ai_not_opted_in is mapped to `AiHttpException(409)` and the
     * ViewModel maps it to `Disabled.OptedOut`).
     */
    suspend fun refreshProfile(): ReaderProfileResponseDto = withContext(Dispatchers.IO) {
        // Server expects an empty JSON body — `{}` is the simplest valid shape.
        val empty = "{}".toRequestBody(mediaType)
        longCallHttp(profileTimeoutS ?: generationTimeoutS).newCall(
            Request.Builder()
                .url("${resolveBaseUrl()}/ai/v1/profile/refresh")
                .post(empty)
                .build(),
        ).await().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw makeError(resp.code, body)
            json.decodeFromString<ReaderProfileResponseDto>(body)
        }
    }

    /**
     * PR-γ (Lock #3 surface — invoked by PR-δ's Settings button). Idempotent:
     * the server returns 204 unconditionally, so a second call with no row
     * present is not an error.
     */
    suspend fun deleteProfile() = withContext(Dispatchers.IO) {
        http.newCall(
            Request.Builder()
                .url("${resolveBaseUrl()}/ai/v1/profile")
                .delete()
                .build(),
        ).await().use { resp ->
            if (!resp.isSuccessful) {
                throw makeError(resp.code, resp.body?.string().orEmpty())
            }
        }
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
            .await()
            .use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw makeError(resp.code, body)
                json.decodeFromString<InsightSyncResponse>(body)
            }
    }

    private suspend inline fun <reified T> get(path: String): T =
        execute(Request.Builder().url("${resolveBaseUrl()}$path").get())

    private suspend inline fun <reified Body, reified Resp> post(
        path: String,
        body: Body,
        client: OkHttpClient = http,
    ): Resp =
        execute(
            Request.Builder()
                .url("${resolveBaseUrl()}$path")
                .post(json.encodeToString(body).toRequestBody(mediaType)),
            client,
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
        ).await().use { resp ->
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

    private suspend inline fun <reified Resp> execute(
        builder: Request.Builder,
        client: OkHttpClient = http,
    ): Resp =
        withContext(Dispatchers.IO) {
            client.newCall(builder.build()).await().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw makeError(resp.code, body)
                }
                json.decodeFromString<Resp>(body)
            }
        }

    private suspend fun executeRaw(builder: Request.Builder) {
        withContext(Dispatchers.IO) {
            http.newCall(builder.build()).await().use { resp ->
                if (!resp.isSuccessful) {
                    throw makeError(resp.code, resp.body?.string().orEmpty())
                }
            }
        }
    }

    /**
     * Issue #102: every request path in this client awaits its [Call]
     * through here instead of calling `execute()` directly, so cancelling
     * the coroutine (e.g. the reader screen that asked for the call is left)
     * cancels the OkHttp call too, rather than leaving it to hold an IO
     * thread and a socket until the call timeout elapses.
     */
    private suspend fun Call.await(): Response =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { cancel() }
            enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isCancelled) return
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    cont.resume(response)
                }
            })
        }

    /**
     * Issue #102: a client for calls that block on a model. The shared
     * client's timeouts are sized for OPDS and sync, not for a generation
     * or profile refresh that the server bounds at [timeoutS] and retries
     * once. Derived per call so a config refresh takes effect immediately;
     * `newBuilder()` shares the connection pool and dispatcher.
     */
    private fun longCallHttp(timeoutS: Int?): OkHttpClient {
        val seconds = computeLongCallTimeoutS(timeoutS)
        return http.newBuilder()
            .readTimeout(seconds, TimeUnit.SECONDS)
            .callTimeout(seconds + 30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Map an HTTP error body to the most specific exception:
     * 429 with a quota body -> [AiQuotaException];
     * a non-429 status with a `detail` object carrying `code` (issue #102) -> [AiProviderException];
     * anything else -> [AiHttpException].
     */
    private fun makeError(code: Int, body: String): RuntimeException {
        val detail = try {
            (json.parseToJsonElement(body) as? JsonObject)?.get("detail")
        } catch (ignored: Exception) {
            null
        }
        if (code == 429 && detail != null) {
            // 429 body shape from server: {detail: {used, limit, resets_at}}
            try {
                return AiQuotaException(json.decodeFromString(QuotaInfo.serializer(), detail.toString()))
            } catch (ignored: Exception) {
                // fall through
            }
        }
        if (code != 429 && detail is JsonObject && detail["code"] != null) {
            try {
                val info = json.decodeFromString(ProviderErrorDetail.serializer(), detail.toString())
                return AiProviderException(
                    code = code,
                    body = body,
                    errorCode = info.code,
                    serverMessage = info.message,
                    hint = info.hint,
                    providerStatus = info.providerStatus,
                )
            } catch (ignored: Exception) {
                // fall through to the generic exception
            }
        }
        return AiHttpException(code, body)
    }

    companion object {
        /** Server default for QUIRE_SERVER_AI_TIMEOUT_S, assumed when the server does not advertise one. */
        const val DEFAULT_GENERATION_TIMEOUT_S = 120

        /**
         * Twice the server's generation timeout (the server retries once on
         * malformed output) plus 30 s for retrieval and queueing, clamped to
         * one to ten minutes so a misconfigured server cannot pin the phone.
         */
        fun computeLongCallTimeoutS(generationTimeoutS: Int?): Long =
            (2L * (generationTimeoutS ?: DEFAULT_GENERATION_TIMEOUT_S) + 30L).coerceIn(60L, 600L)
    }
}

open class AiHttpException(val code: Int, val body: String) :
    RuntimeException("AI request failed: $code body=${body.take(200)}")

/**
 * Issue #102: a provider failure the server described. [serverMessage] is
 * written for the reader, [hint] for whoever runs the server. Subclass of
 * [AiHttpException] so existing `is AiHttpException` branches keep working.
 */
class AiProviderException(
    code: Int,
    body: String,
    val errorCode: String,
    val serverMessage: String,
    val hint: String?,
    val providerStatus: Int?,
) : AiHttpException(code, body)

class InsightNotCachedException : RuntimeException("insight not cached")

class AiQuotaException(val info: QuotaInfo) :
    RuntimeException("AI quota exhausted: ${info.used}/${info.limit}, resets at ${info.resetsAt}")
