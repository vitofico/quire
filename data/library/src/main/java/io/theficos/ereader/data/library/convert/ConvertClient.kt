package io.theficos.ereader.data.library.convert

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * REST client for `/convert/v1` on quire-server: PDF in, EPUB out.
 *
 * Auth comes from the shared Quire client's interceptor, which signs only the account's own
 * server. Uploads and downloads stream from and to files, never through memory, and every call
 * is cancelled with its coroutine.
 */
class ConvertClient(
    private val baseUrlProvider: () -> String?,
    private val http: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /**
     * The server drops an upload that sends nothing for 60 s; wait as long for each write. A
     * broken transfer is never sent again behind the caller's back: a PDF can be 100 MiB.
     */
    private val transferHttp: OkHttpClient by lazy {
        http.newBuilder()
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    suspend fun capabilities(): ConvertCapabilities = call(get(url("capabilities")))

    suspend fun create(idempotencyKey: String, sha256: String, bytes: Long): ConvertJob =
        call(post(url("jobs"), json.encodeToString(CreateJobBody.serializer(), CreateJobBody(idempotencyKey, sha256, bytes, mode = "local"))))

    suspend fun job(id: String): ConvertJob = call(get(url("jobs", id)))

    /** The PDF as the raw body. */
    suspend fun upload(id: String, pdf: File): ConvertJob =
        call(
            Request.Builder().url(url("jobs", id, "source")).put(pdf.asRequestBody(PDF)).build(),
            transferHttp,
        )

    suspend fun cancel(id: String): ConvertJob = call(post(url("jobs", id, "cancel"), null))

    suspend fun acknowledge(id: String): ConvertJob = call(post(url("jobs", id, "acknowledge"), null))

    /**
     * Streams the EPUB into [dest] and answers its size and SHA-256, to check against the job's
     * result. Stops past [maxBytes], the size the job announced.
     */
    suspend fun download(id: String, dest: File, maxBytes: Long): Downloaded =
        transferHttp.newCall(get(url("jobs", id, "epub"))).await().use { resp ->
            if (!resp.isSuccessful) throw error(resp)
            withContext(Dispatchers.IO) {
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                val body = resp.body ?: throw IOException("empty body")
                body.byteStream().use { input ->
                    dest.outputStream().use { out ->
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            digest.update(buffer, 0, n)
                            out.write(buffer, 0, n)
                            total += n
                            if (total > maxBytes) throw IOException("more than the announced size")
                        }
                    }
                }
                Downloaded(total, digest.digest().toHex())
            }
        }

    data class Downloaded(val bytes: Long, val sha256: String)

    private fun url(vararg segments: String): HttpUrl {
        val base = baseUrlProvider()?.trimEnd('/')
        if (base.isNullOrBlank()) throw ConvertHttpException(0, "not_configured", null)
        return base.toHttpUrl().newBuilder().addPathSegments("convert/v1").apply {
            segments.forEach { addPathSegment(it) }
        }.build()
    }

    private fun get(url: HttpUrl) = Request.Builder().url(url).get().build()

    private fun post(url: HttpUrl, body: String?) = Request.Builder().url(url)
        .post((body ?: "").toRequestBody(if (body == null) null else JSON_TYPE))
        .build()

    private suspend inline fun <reified T> call(request: Request, client: OkHttpClient = http): T =
        client.newCall(request).await().use { resp ->
            val text = withContext(Dispatchers.IO) { resp.body?.string().orEmpty() }
            if (!resp.isSuccessful) throw error(resp, text)
            json.decodeFromString<T>(text)
        }

    private fun error(resp: Response, text: String? = null): ConvertHttpException {
        val body = text ?: runCatching { resp.body?.string() }.getOrNull().orEmpty()
        // Rule errors are {"detail": "<code>"}; schema errors carry a list, which needs no code.
        val detail = runCatching {
            ((json.parseToJsonElement(body) as? JsonObject)?.get("detail") as? JsonPrimitive)
                ?.takeIf { it.isString }?.content
        }.getOrNull()
        return ConvertHttpException(resp.code, detail, resp.header("Retry-After")?.trim()?.toLongOrNull())
    }

    private companion object {
        val PDF = "application/pdf".toMediaType()
        val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        const val BUFFER = 64 * 1024
    }
}

/**
 * A non-2xx answer. [detail] is the server's code (`job_not_found`, `upload_in_progress`, ...),
 * or `Not Found` when conversion is off; [retryAfterSeconds] is its `Retry-After`.
 */
class ConvertHttpException(val code: Int, val detail: String?, val retryAfterSeconds: Long?) :
    RuntimeException("convert request failed: $code $detail")

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private suspend fun Call.await(): Response =
    suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { _, undelivered, _ -> undelivered.close() }
            }
        })
    }
