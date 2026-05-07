package io.theficos.ereader.data.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class ProgressDtosTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `push body round-trips`() {
        val body = ProgressPushBody(
            items = listOf(
                ProgressItemDto(
                    document = DocumentIdDto(metadataId = "m1", contentHash = "h1"),
                    locator = "epubcfi(/6)",
                    percent = 0.42,
                    clientUpdatedAt = "2026-05-05T12:00:00+00:00",
                )
            )
        )
        val encoded = json.encodeToString(ProgressPushBody.serializer(), body)
        assertThat(encoded).contains("\"metadata_id\":\"m1\"")
        assertThat(encoded).contains("\"content_hash\":\"h1\"")
        assertThat(encoded).contains("\"client_updated_at\":\"2026-05-05T12:00:00+00:00\"")
        val decoded = json.decodeFromString(ProgressPushBody.serializer(), encoded)
        assertThat(decoded).isEqualTo(body)
    }

    @Test fun `pull response decodes`() {
        val raw = """{"items":[{"document":{"metadata_id":null,"content_hash":"h"},"locator":"l","percent":0.1,"client_updated_at":"2026-05-05T12:00:00+00:00"}],"server_time":"2026-05-05T12:00:01+00:00"}"""
        val r = json.decodeFromString(ProgressPullResponse.serializer(), raw)
        assertThat(r.items).hasSize(1)
        assertThat(r.serverTime).isEqualTo("2026-05-05T12:00:01+00:00")
    }
}
