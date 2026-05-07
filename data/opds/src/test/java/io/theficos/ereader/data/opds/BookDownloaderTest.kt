package io.theficos.ereader.data.opds

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var downloader: BookDownloader

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        downloader = BookDownloader(OkHttpClient(), tmp.root)
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun `downloads to file with progress callback`() = runTest {
        val payload = ByteArray(8 * 1024) { (it % 251).toByte() }
        server.enqueue(MockResponse()
            .setHeader("Content-Length", payload.size.toString())
            .setBody(Buffer().write(payload)))

        val updates = mutableListOf<Long>()
        val file = downloader.download(
            url = server.url("/opds/download/42/epub").toString(),
            destFileName = "42.epub",
            onProgress = { sent, total ->
                updates += sent
                assertThat(total).isEqualTo(payload.size.toLong())
            },
        )

        assertThat(file.exists()).isTrue()
        assertThat(file.length()).isEqualTo(payload.size.toLong())
        assertThat(updates).isNotEmpty()
        assertThat(updates.last()).isEqualTo(payload.size.toLong())
    }

    @Test(expected = IllegalStateException::class)
    fun `non-2xx throws`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        downloader.download(server.url("/x").toString(), "x.epub") { _, _ -> }
    }
}
