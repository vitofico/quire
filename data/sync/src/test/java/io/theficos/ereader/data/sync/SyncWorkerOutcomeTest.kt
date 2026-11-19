package io.theficos.ereader.data.sync

import io.theficos.ereader.testing.awaitRequest
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertWithMessage
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.local.db.EReaderDatabase
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SyncWorkerOutcomeTest {
    @Test fun `worker maps progress and highlight outcomes to work results`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val previous = SyncDependencies.holder
        val db = Room.inMemoryDatabaseBuilder(context, EReaderDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            db.openHelper.writableDatabase
            val progress = """{"items":[],"server_time":"2026-05-05T12:00:00Z"}"""
            val highlights = """{"items":[],"next_cursor":"0","has_more":false}"""
            val cases = listOf(
                Triple("both succeed", listOf(MockResponse().setBody(progress), MockResponse().setBody(highlights)), Result.success()),
                Triple("progress unauthorized", listOf(MockResponse().setResponseCode(401)), Result.failure()),
                Triple("highlights fail", listOf(MockResponse().setBody(progress), MockResponse().setResponseCode(500)), Result.retry()),
                Triple("network fails", listOf(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)), Result.retry()),
            )

            for ((name, responses, expected) in cases) {
                MockWebServer().use { server ->
                    server.start()
                    val url = server.url("/").toString()
                    val client = SyncClient(
                        { url },
                        OkHttpClient.Builder()
                            .callTimeout(5, TimeUnit.SECONDS)
                            .retryOnConnectionFailure(false)
                            .build(),
                    )
                    val syncer = HighlightSyncer(client, db.highlightSyncDao(), db.syncStateDao()) {
                        HighlightSyncSession("fixture-reader", url, "Bearer fixture-token")
                    }
                    SyncDependencies.holder = SyncDependencies.Holder(SyncOrchestrator(
                        client, db.progressDao(), DocumentRepository(db.documentDao()), db.syncStateDao(), syncer,
                    ))
                    responses.forEach { server.enqueue(it) }
                    val worker = TestListenableWorkerBuilder<SyncWorker>(context).build()

                    assertWithMessage(name).that(worker.doWork()).isEqualTo(expected)
                    if (name != "network fails") {
                        assertWithMessage(name).that(server.requestCount).isEqualTo(responses.size)
                        val paths = responses.map {
                            server.awaitRequest().requestUrl?.encodedPath
                        }
                        assertWithMessage(name).that(paths).containsExactlyElementsIn(
                            listOf("/sync/v1/progress", "/sync/v1/highlights").take(responses.size),
                        ).inOrder()
                    }
                }
            }
        } finally {
            SyncDependencies.holder = previous
            db.close()
        }
    }
}
