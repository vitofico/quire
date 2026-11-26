package io.theficos.ereader.di

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.auth.CalibreCredentialStore
import io.theficos.ereader.core.identity.extractIdentity
import io.theficos.ereader.data.library.sync.LibraryMirrorPushDependencies
import io.theficos.ereader.data.local.db.EReaderDatabase
import io.theficos.ereader.data.sync.SyncDependencies
import io.theficos.ereader.domain.restore.RestoreSummary
import io.theficos.ereader.sideload.writeTestEpub
import io.theficos.ereader.ui.catalog.FakeAndroidKeyStore
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AppContainerRestoreTest {
    @Test fun `restore discards a download already present under its extracted identity`() = runBlocking {
        FakeAndroidKeyStore.setup()
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, EReaderDatabase::class.java)
            .allowMainThreadQueries().build()
        db.openHelper.writableDatabase
        val previousSync = SyncDependencies.holder
        val previousLibrary = LibraryMirrorPushDependencies.holder
        val container = AppContainer(context, db)
        try {
            MockWebServer().use { server ->
                // Answered by path: the AI features also ask the server for their config at start.
                val answers = java.util.concurrent.ConcurrentHashMap<String, () -> MockResponse>()
                val asked = java.util.concurrent.CopyOnWriteArrayList<String>()
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val path = request.requestUrl?.encodedPath.orEmpty()
                        if (!path.startsWith("/ai/")) asked += path
                        return answers[path]?.invoke() ?: MockResponse().setResponseCode(404)
                    }
                }
                server.start()
                val origin = server.url("/").toString().trimEnd('/')
                container.credentialStore.saveBasicAccount(origin, "reader", "fixture", origin)
                val original = writeTestEpub(
                    File(container.booksDir.apply { mkdirs() }, "original.epub"),
                    "urn:uuid:restore-duplicate",
                    "Existing Book",
                )
                val id = container.documentRepository.insert(
                    extractIdentity(original), "Existing Book", null, "$origin/old.epub",
                    original.path, null, 0L,
                )
                // The mirror advertises a different identity. Only extracting the
                // fetched EPUB can tell the restore path that it is already local.
                val time = "2026-05-05T12:00:00Z"
                val library = """
                    {"items":[{"content_hash":"mirror-hash","opds_href":"$origin/restore.epub",
                    "title":"Remote Copy","created_at":"$time","updated_at":"$time"}],"server_time":"$time"}
                """.trimIndent()
                val progress = """
                    {"items":[{"document":{"content_hash":"mirror-hash"},"locator":"loc",
                    "percent":0.5,"client_updated_at":"$time"}],"server_time":"$time"}
                """.trimIndent()
                answers["/library/v1/items"] = { MockResponse().setBody(library) }
                answers["/sync/v1/progress"] = { MockResponse().setBody(progress) }
                answers["/restore.epub"] = { MockResponse().setBody(Buffer().write(original.readBytes())) }

                assertThat(container.runRestoreInProgress { _, _ -> })
                    .isEqualTo(RestoreSummary(1, 1, 0, 0, 0))
                assertThat(container.documentRepository.observeLibrary().first().map { it.id })
                    .containsExactly(id)
                assertThat(container.booksDir.list()?.toList()).containsExactly("original.epub")
                assertThat(asked).containsExactly("/library/v1/items", "/sync/v1/progress", "/restore.epub").inOrder()
            }
        } finally {
            container.libraryUploaderScope.coroutineContext[Job]?.cancelAndJoin()
            container.credentialStore.clear()
            SyncDependencies.holder = previousSync
            LibraryMirrorPushDependencies.holder = previousLibrary
            db.close()
        }
    }

    @Test fun `a failure moving the old catalog's highlights still starts catalog sync`() = runBlocking {
        FakeAndroidKeyStore.setup()
        val context = ApplicationProvider.getApplicationContext<Application>()
        // An old catalog owner whose address can't be parsed makes moving its highlights throw.
        // The store's own file, written the way an older version could have left it.
        val stored = CalibreCredentialStore(context).apply { clear() }
        val prefs = CalibreCredentialStore::class.java.getDeclaredField("prefs")
            .apply { isAccessible = true }.get(stored) as android.content.SharedPreferences
        prefs.edit().putString("legacy_catalog_owner_url", "not an address").commit()
        val db = Room.inMemoryDatabaseBuilder(context, EReaderDatabase::class.java)
            .allowMainThreadQueries().build()
        db.openHelper.writableDatabase
        val previousSync = SyncDependencies.holder
        val previousLibrary = LibraryMirrorPushDependencies.holder
        val container = AppContainer(context, db)
        try {
            MockWebServer().use { server ->
                server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = MockResponse().setResponseCode(404)
                }
                server.start()
                container.credentialStore.saveBearerAccount(server.url("/").toString(), "a@b.c", "tok")
                // Other start-up work may reach the server too; the catalog list must be among it.
                val paths = generateSequence { server.takeRequest(10, java.util.concurrent.TimeUnit.SECONDS) }
                    .map { it.requestUrl?.encodedPath }
                assertThat(paths.take(10).any { it == "/catalogs/v1/catalogs" }).isTrue()
            }
        } finally {
            container.libraryUploaderScope.coroutineContext[Job]?.cancelAndJoin()
            container.credentialStore.clear()
            SyncDependencies.holder = previousSync
            LibraryMirrorPushDependencies.holder = previousLibrary
            db.close()
        }
    }

    @Test fun `the AI config is fetched at start and for each account, never the last account's`() = runBlocking {
        FakeAndroidKeyStore.setup()
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, EReaderDatabase::class.java)
            .allowMainThreadQueries().build()
        db.openHelper.writableDatabase
        val previousSync = SyncDependencies.holder
        val previousLibrary = LibraryMirrorPushDependencies.holder
        fun server(config: MockResponse) = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                    "/ai/v1/config" -> config
                    "/ai/v1/preferences" -> MockResponse().setBody("""{"ai_enabled":true}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            start()
        }
        val first = server(MockResponse().setBody("""{"configured":true,"details_supported":true}"""))
        val second = server(MockResponse().setResponseCode(503))
        // Signed in before the app starts.
        CalibreCredentialStore(context).apply { clear() }.saveBearerAccount(first.url("/").toString().trimEnd('/'), "a@b.c", "tok")
        val container = AppContainer(context, db)
        try {
            withTimeout(10_000) { container.aiRepository.config.first { it?.detailsSupported == true } }

            // Another account whose server can't answer: the first account's config no longer applies.
            container.credentialStore.saveBearerAccount(second.url("/").toString().trimEnd('/'), "d@e.f", "tok2")
            withTimeout(10_000) { container.aiRepository.config.first { it == null } }
            val asked = generateSequence { second.takeRequest(10, java.util.concurrent.TimeUnit.SECONDS) }
                .map { it.requestUrl?.encodedPath }
            assertThat(asked.take(10).any { it == "/ai/v1/config" }).isTrue()
            assertThat(container.aiRepository.preferences.value).isNull()
        } finally {
            container.libraryUploaderScope.coroutineContext[Job]?.cancelAndJoin()
            container.credentialStore.clear()
            SyncDependencies.holder = previousSync
            LibraryMirrorPushDependencies.holder = previousLibrary
            db.close()
            runCatching { first.shutdown() }
            runCatching { second.shutdown() }
        }
    }
}
