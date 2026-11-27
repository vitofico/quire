package io.theficos.ereader.ui.stats

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.auth.AccountCredentials
import io.theficos.ereader.data.library.LibraryClient
import io.theficos.ereader.data.library.ReadingActivity
import io.theficos.ereader.data.library.ReadingActivityPage
import io.theficos.ereader.data.library.ReadingActivityUpload
import io.theficos.ereader.data.local.db.EReaderDatabase
import io.theficos.ereader.data.local.db.ReadingSessionEntity
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ReadingActivitySyncTest {
    @Test fun `opt in uploads legacy sessions and merges browser activity once`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, EReaderDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = MockWebServer()
        val browser = ReadingActivity("browser", "book-2", "Web book", 2_000, -18000, 60, 500)
        val remote = ConcurrentHashMap(mapOf(browser.id to browser))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.method) {
                "GET" -> MockResponse().setBody(Json.encodeToString(
                    ReadingActivityPage.serializer(), ReadingActivityPage(0, remote.values.toList(), null),
                ))
                "PUT" -> {
                    val uploaded = Json.decodeFromString(ReadingActivityUpload.serializer(), request.body.readUtf8())
                    uploaded.sessions.forEach { remote[it.id] = it }
                    MockResponse().setResponseCode(204)
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            val owner = MutableStateFlow<String?>("reader")
            val account = MutableStateFlow<AccountCredentials?>(
                AccountCredentials.Basic(server.url("/").toString(), "reader", "pw"),
            )
            val repository = ReadingStatsRepository(
                db.readingSessionDao(), context.getSharedPreferences("recording-test", Context.MODE_PRIVATE), scope, owner,
            )
            val local = ReadingSessionEntity(
                bookKey = "book-1", title = "Phone book", author = null, startedAt = 1_000,
                endedAt = 121_000, utcOffsetSeconds = 3_600, activeSeconds = 120, progressionRead = .05, wordsRead = 900,
            )
            db.readingSessionDao().insert(local)
            val sync = ReadingActivitySync(
                repository, context.getSharedPreferences("activity-test", Context.MODE_PRIVATE),
                account, { it.subject }, { credentials -> LibraryClient({ credentials.baseUrl }, OkHttpClient()) }, scope,
            )
            withTimeout(10_000) {
                sync.state.first { it.owner == "reader" }
                assertThat(server.requestCount).isEqualTo(0)
                sync.setEnabled(true)
                val synced = sync.state.first { it.remote.any { row -> row.id == browser.id } && !it.failed }
                assertThat(remote.values.map { it.title }).containsExactly("Web book", "Phone book")
                val uploaded = remote.values.single { it.title == local.title }
                assertThat(uploaded.startedAt).isEqualTo(local.startedAt)
                assertThat(uploaded.utcOffsetSeconds).isEqualTo(local.utcOffsetSeconds)
                val rows = repository.sessions.first()
                assertThat(rows.single().owner).isEqualTo("reader")
                assertThat(sync.merged(rows, synced.copy(remote = remote.values.toList())).map { it.title })
                    .containsExactly("Phone book", "Web book")

                account.value = AccountCredentials.Basic(server.url("/").toString(), "other", "pw")
                owner.value = "other"
                sync.state.first { it.owner == "other" }
                assertThat(sync.merged(repository.sessions.first(), sync.state.value)).isEmpty()
            }
        } finally {
            scope.cancel()
            server.shutdown()
            db.close()
        }
    }
}
