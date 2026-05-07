package io.theficos.ereader.data.opds

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

class BookDownloader(
    private val okHttp: OkHttpClient,
    private val booksDir: File,
) {
    init { booksDir.mkdirs() }

    suspend fun download(
        url: String,
        destFileName: String,
        onProgress: (bytesSent: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val response = okHttp.newCall(Request.Builder().url(url).get().build()).execute()
        response.use {
            check(it.isSuccessful) { "Download failed ${it.code} for $url" }
            val total = it.body!!.contentLength()
            val out = File(booksDir, destFileName)
            val tmp = File(booksDir, "$destFileName.part")
            try {
                it.body!!.byteStream().use { input ->
                    tmp.outputStream().use { sink ->
                        val buffer = ByteArray(64 * 1024)
                        var sent = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n <= 0) break
                            sink.write(buffer, 0, n)
                            sent += n
                            onProgress(sent, total)
                        }
                    }
                }
                if (out.exists()) out.delete()
                check(tmp.renameTo(out)) { "Failed to rename ${tmp.path} -> ${out.path}" }
                out
            } catch (t: Throwable) {
                tmp.delete()
                throw t
            }
        }
    }
}
