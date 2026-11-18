package io.theficos.ereader.data.ai

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.core.model.DocumentIdentity
import io.theficos.ereader.data.local.db.EReaderDatabase
import io.theficos.ereader.data.local.db.InsightEntity
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AiRepositoryCacheKeyTest {
    @Test fun `local cache requires each model prompt and style key to match`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), EReaderDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            db.openHelper.writableDatabase
            MockWebServer().use { server ->
                server.start()
                val dao = db.insightDao()
                val repo = AiRepository(
                    AiClient(
                        { server.url("/").toString() },
                        OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build(),
                    ),
                    dao,
                )
                server.enqueue(MockResponse().setBody(
                    """{"configured":true,"model_id":"current-model","prompt_version":"4"}""",
                ))
                server.enqueue(MockResponse().setBody(
                    """{"ai_enabled":true,"style":{"tone":"neutral","language":"auto"}}""",
                ))
                repo.refresh()
                assertThat(server.requestCount).isEqualTo(2)

                val exact = InsightEntity(
                    identityKey = "exact", metadataId = "exact", contentHash = "exact-hash",
                    modelId = "current-model", promptVersion = "4", tone = "neutral", language = "auto",
                    payloadJson = """{"schema_version":4}""", sourcesJson = "[]",
                    schemaVersion = 4, serverId = 1, generatedAt = 1_000, syncedAt = 1_100,
                )
                dao.upsert(exact)
                assertThat(repo.getCachedInsight(DocumentIdentity(metadataId = "exact"))).isNotNull()
                assertThat(server.requestCount).isEqualTo(2)

                val mismatches = mapOf(
                    "model" to exact.copy(modelId = "prior-model"),
                    "prompt" to exact.copy(promptVersion = "3"),
                    "tone" to exact.copy(tone = "scholarly"),
                    "language" to exact.copy(language = "it"),
                )
                for ((dimension, stale) in mismatches) {
                    val hash = "$dimension-hash"
                    dao.upsert(stale.copy(identityKey = dimension, metadataId = dimension, contentHash = hash))
                    server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"not_cached"}"""))
                    val requests = server.requestCount

                    assertThat(repo.getCachedInsight(DocumentIdentity(dimension, hash))).isNull()
                    assertThat(server.requestCount).isEqualTo(requests + 1)
                }
            }
        } finally {
            db.close()
        }
    }
}
