package io.theficos.ereader.di

import io.theficos.ereader.testing.awaitRequest
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
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
                server.enqueue(MockResponse().setBody("""
                    {"items":[{"content_hash":"mirror-hash","opds_href":"$origin/restore.epub",
                    "title":"Remote Copy","created_at":"$time","updated_at":"$time"}],"server_time":"$time"}
                """.trimIndent()))
                server.enqueue(MockResponse().setBody("""
                    {"items":[{"document":{"content_hash":"mirror-hash"},"locator":"loc",
                    "percent":0.5,"client_updated_at":"$time"}],"server_time":"$time"}
                """.trimIndent()))
                server.enqueue(MockResponse().setBody(Buffer().write(original.readBytes())))

                assertThat(container.runRestoreInProgress { _, _ -> })
                    .isEqualTo(RestoreSummary(1, 1, 0, 0, 0))
                assertThat(container.documentRepository.observeLibrary().first().map { it.id })
                    .containsExactly(id)
                assertThat(container.booksDir.list()?.toList()).containsExactly("original.epub")
                assertThat(server.requestCount).isEqualTo(3)
                assertThat(server.awaitRequest().requestUrl?.encodedPath)
                    .isEqualTo("/library/v1/items")
                assertThat(server.awaitRequest().requestUrl?.encodedPath)
                    .isEqualTo("/sync/v1/progress")
                assertThat(server.awaitRequest().requestUrl?.encodedPath)
                    .isEqualTo("/restore.epub")
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
}
