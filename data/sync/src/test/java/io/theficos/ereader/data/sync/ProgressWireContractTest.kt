package io.theficos.ereader.data.sync

import io.theficos.ereader.testing.awaitRequest
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test

class ProgressWireContractTest {
    @Test fun `client posts terminal states and reads the shared server progress fixture`() = runTest {
        val fixture = Json.parseToJsonElement(
            checkNotNull(javaClass.getResource("/progress-sync.json")).readText(),
        ).jsonObject
        val finished = ProgressItemDto(
            DocumentIdDto("progress-contract-finished", "wire-finished", 2),
            "epubcfi(/6/4!/4)", 0.99, "2026-05-05T12:00:00Z", finishedAt = "2026-05-05T12:00:00Z",
        )
        val abandoned = ProgressItemDto(
            DocumentIdDto(null, "wire-abandoned", 3),
            "epubcfi(/6/8!/4)", 0.6, "2026-05-05T13:00:00Z", abandonedAt = "2026-05-05T13:00:00Z",
        )

        MockWebServer().use { server ->
            server.start()
            val client = SyncClient(
                { server.url("/").toString() },
                OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build(),
            )
            server.enqueue(MockResponse().setBody(fixture.getValue("push_response").toString()))
            server.enqueue(MockResponse().setBody(fixture.getValue("pull").toString()))

            val push = client.pushProgress(ProgressPushBody(listOf(finished, abandoned)))
            assertThat(push).isInstanceOf(SyncResult.Success::class.java)
            val result = (push as SyncResult.Success<ProgressPushResponse>).value.results
            assertThat(result.map { it.document }).containsExactly(finished.document, abandoned.document)
            assertThat(result.map { it.status }).containsExactly("accepted", "accepted")
            val request = server.awaitRequest()
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.requestUrl?.encodedPath).isEqualTo("/sync/v1/progress")
            assertThat(Json.parseToJsonElement(request.body.readUtf8())).isEqualTo(fixture.getValue("push"))

            val pull = client.pullProgress(Instant.EPOCH.toString())
            assertThat(pull).isInstanceOf(SyncResult.Success::class.java)
            val response = (pull as SyncResult.Success<ProgressPullResponse>).value
            assertThat(response.items.map { item ->
                item.copy(
                    clientUpdatedAt = Instant.parse(item.clientUpdatedAt).toString(),
                    finishedAt = item.finishedAt?.let { Instant.parse(it).toString() },
                    abandonedAt = item.abandonedAt?.let { Instant.parse(it).toString() },
                )
            }).containsExactly(finished, abandoned)
            assertThat(Instant.parse(response.serverTime)).isEqualTo(Instant.parse("2026-05-05T13:00:01Z"))
            val pullRequest = server.awaitRequest()
            assertThat(pullRequest.method).isEqualTo("GET")
            assertThat(pullRequest.requestUrl?.encodedPath).isEqualTo("/sync/v1/progress")
        }
    }
}
