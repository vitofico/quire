package io.theficos.ereader.data.sync

import kotlinx.serialization.KSerializer
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

    suspend fun pushProgress(body: ProgressPushBody): SyncResult<ProgressPushResponse> {
        val base = baseUrlProvider() ?: return SyncResult.Unauthorized
        return post(base, SyncApi.PATH_PROGRESS_PUSH, body, ProgressPushBody.serializer(), ProgressPushResponse.serializer())
    }

    suspend fun pullProgress(sinceIso8601: String): SyncResult<ProgressPullResponse> {
        val base = baseUrlProvider() ?: return SyncResult.Unauthorized
        val url = (base.trimEnd('/') + SyncApi.PATH_PROGRESS_PULL).toHttpUrl()
            .newBuilder().addQueryParameter("since", sinceIso8601).build()
        val req = Request.Builder().url(url).get().build()
        return execute(req, ProgressPullResponse.serializer())
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

    private fun <T> execute(req: Request, serializer: KSerializer<T>): SyncResult<T> = try {
        okHttp.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            when {
                resp.code == 401 -> SyncResult.Unauthorized
                resp.isSuccessful -> SyncResult.Success(json.decodeFromString(serializer, raw))
                else -> SyncResult.HttpFailure(resp.code, raw)
            }
        }
    } catch (e: IOException) {
        SyncResult.NetworkFailure(e)
    }
}
