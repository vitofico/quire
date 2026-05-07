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

    @Test fun `downloadCover writes bytes to the books dir`() = runTest {
        val coverBytes = ByteArray(1024) { (it % 251).toByte() }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "image/jpeg")
                .setBody(Buffer().write(coverBytes))
        )
        val out = downloader.downloadCover(
            server.url("/cover.jpg").toString(),
            "abc.jpg",
        )
        assertThat(out).isNotNull()
        assertThat(out!!.exists()).isTrue()
        assertThat(out.readBytes()).isEqualTo(coverBytes)
    }

    @Test fun `downloadCover returns null on http error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        val out = downloader.downloadCover(
            server.url("/cover.jpg").toString(),
            "missing.jpg",
        )
        assertThat(out).isNull()
    }
}
