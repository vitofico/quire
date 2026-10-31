package io.theficos.ereader.data.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class SyncClient(
    private val baseUrlProvider: () -> String?,
    private val okHttp: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    suspend fun pushProgress(body: ProgressPushBody): SyncResult<ProgressPushResponse> = withContext(Dispatchers.IO) {
        val base = baseUrlProvider() ?: return@withContext SyncResult.Unauthorized
        post(base, SyncApi.PATH_PROGRESS_PUSH, body, ProgressPushBody.serializer(), ProgressPushResponse.serializer())
    }

    suspend fun pullProgress(sinceIso8601: String): SyncResult<ProgressPullResponse> = withContext(Dispatchers.IO) {
        val base = baseUrlProvider() ?: return@withContext SyncResult.Unauthorized
        val url = (base.trimEnd('/') + SyncApi.PATH_PROGRESS_PULL).toHttpUrl()
            .newBuilder().addQueryParameter("since", sinceIso8601).build()
        val req = Request.Builder().url(url).get().build()
        execute(req, ProgressPullResponse.serializer())
    }

    // Redirects can replay a private POST body; this endpoint never follows them.
    private val highlightHttp = okHttp.newBuilder().followRedirects(false).followSslRedirects(false).build()

    suspend fun pullHighlights(session: HighlightSyncSession, cursor: Long): SyncResult<HighlightPullResponse> =
        withContext(Dispatchers.IO) {
            val url = (session.baseUrl.trimEnd('/') + SyncApi.PATH_HIGHLIGHTS).toHttpUrl()
                .newBuilder().addQueryParameter("since", cursor.toString()).addQueryParameter("limit", "500").build()
            execute(Request.Builder().url(url).header("Authorization", session.authorization).get().build(),
                HighlightPullResponse.serializer(), highlightHttp)
        }

    suspend fun pushHighlights(session: HighlightSyncSession, items: List<HighlightItemDto>): SyncResult<HighlightPushResponse> =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(HighlightPushBody.serializer(), HighlightPushBody(items))
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(session.baseUrl.trimEnd('/') + SyncApi.PATH_HIGHLIGHTS)
                .header("Authorization", session.authorization).post(body).build()
            execute(request, HighlightPushResponse.serializer(), highlightHttp)
        }

    private fun <Req, Resp> post(
        baseUrl: String,
        path: String,
        body: Req,
        reqSerializer: KSerializer<Req>,
        respSerializer: KSerializer<Resp>,
    ): SyncResult<Resp> {
        val payload = json.encodeToString(reqSerializer, body)
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .post(payload)
            .build()
        return execute(req, respSerializer)
    }

    private fun <T> execute(req: Request, serializer: KSerializer<T>, http: OkHttpClient = okHttp): SyncResult<T> = try {
        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            when {
                resp.code == 401 -> SyncResult.Unauthorized
                resp.isSuccessful -> try {
                    SyncResult.Success(json.decodeFromString(serializer, raw))
                } catch (_: SerializationException) {
                    SyncResult.HttpFailure(resp.code, "Invalid sync response")
                }
                else -> SyncResult.HttpFailure(resp.code, raw)
            }
        }
    } catch (e: IOException) {
        SyncResult.NetworkFailure(e)
    }
}
