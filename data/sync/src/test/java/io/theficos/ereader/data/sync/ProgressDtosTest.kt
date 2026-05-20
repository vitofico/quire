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

    @Test fun `push body round-trips finishedAt`() {
        val body = ProgressPushBody(
            items = listOf(
                ProgressItemDto(
                    document = DocumentIdDto(metadataId = "m1", contentHash = "h1"),
                    locator = "loc",
                    percent = 0.99,
                    clientUpdatedAt = "2026-05-09T12:00:00+00:00",
                    finishedAt = "2026-05-09T12:00:00+00:00",
                )
            )
        )
        val encoded = json.encodeToString(ProgressPushBody.serializer(), body)
        assertThat(encoded).contains("\"finished_at\":\"2026-05-09T12:00:00+00:00\"")
        val decoded = json.decodeFromString(ProgressPushBody.serializer(), encoded)
        assertThat(decoded).isEqualTo(body)
    }

    @Test fun `null finishedAt is omitted on the wire`() {
        val body = ProgressPushBody(
            items = listOf(
                ProgressItemDto(
                    document = DocumentIdDto(metadataId = "m1", contentHash = "h1"),
                    locator = "loc",
                    percent = 0.5,
                    clientUpdatedAt = "2026-05-09T12:00:00+00:00",
                    finishedAt = null,
                )
            )
        )
        val encoded = json.encodeToString(ProgressPushBody.serializer(), body)
        assertThat(encoded).doesNotContain("finished_at")
    }

    @Test fun `pull response decodes with optional finishedAt`() {
        val raw = """{"items":[{"document":{"metadata_id":null,"content_hash":"h"},"locator":"l","percent":0.99,"client_updated_at":"2026-05-09T12:00:00+00:00","finished_at":"2026-05-09T12:00:00+00:00"}],"server_time":"2026-05-09T12:00:01+00:00"}"""
        val r = json.decodeFromString(ProgressPullResponse.serializer(), raw)
        assertThat(r.items.first().finishedAt).isEqualTo("2026-05-09T12:00:00+00:00")
    }

    @Test fun `push body round-trips abandonedAt`() {
        val body = ProgressPushBody(
            items = listOf(
                ProgressItemDto(
                    document = DocumentIdDto(metadataId = "m1", contentHash = "h1"),
                    locator = "loc",
                    percent = 0.6,
                    clientUpdatedAt = "2026-05-20T12:00:00+00:00",
                    abandonedAt = "2026-05-20T12:00:00+00:00",
                )
            )
        )
        val encoded = json.encodeToString(ProgressPushBody.serializer(), body)
        assertThat(encoded).contains("\"abandoned_at\":\"2026-05-20T12:00:00+00:00\"")
        val decoded = json.decodeFromString(ProgressPushBody.serializer(), encoded)
        assertThat(decoded).isEqualTo(body)
    }

    @Test fun `null abandonedAt is omitted on the wire`() {
        val body = ProgressPushBody(
            items = listOf(
                ProgressItemDto(
                    document = DocumentIdDto(metadataId = "m1", contentHash = "h1"),
                    locator = "loc",
                    percent = 0.5,
                    clientUpdatedAt = "2026-05-20T12:00:00+00:00",
                    abandonedAt = null,
                )
            )
        )
        val encoded = json.encodeToString(ProgressPushBody.serializer(), body)
        assertThat(encoded).doesNotContain("abandoned_at")
    }

    @Test fun `pull response decodes with optional abandonedAt`() {
        val raw = """{"items":[{"document":{"metadata_id":null,"content_hash":"h"},"locator":"l","percent":0.6,"client_updated_at":"2026-05-20T12:00:00+00:00","abandoned_at":"2026-05-20T12:00:00+00:00"}],"server_time":"2026-05-20T12:00:01+00:00"}"""
        val r = json.decodeFromString(ProgressPullResponse.serializer(), raw)
        assertThat(r.items.first().abandonedAt).isEqualTo("2026-05-20T12:00:00+00:00")
        assertThat(r.items.first().finishedAt).isNull()
    }
}
