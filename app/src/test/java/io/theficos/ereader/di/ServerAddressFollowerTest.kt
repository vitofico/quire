package io.theficos.ereader.di

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.auth.AccountCredentials
import io.theficos.ereader.auth.CalibreCredentialStore
import io.theficos.ereader.data.local.db.DocumentEntity
import io.theficos.ereader.data.local.db.EReaderDatabase
import io.theficos.ereader.data.local.db.MemoryBookmark
import io.theficos.ereader.ui.catalog.FakeAndroidKeyStore
import io.theficos.ereader.ui.onboarding.ConnectServerViewModel
import io.theficos.ereader.ui.reader.memoryAccountKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class ServerAddressFollowerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val old = MockWebServer()
    private val canonical = MockWebServer()
    private lateinit var store: CalibreCredentialStore
    private lateinit var db: EReaderDatabase
    private lateinit var account: AccountCredentials.Bearer

    private val oldUrl get() = old.url("/").toString().trimEnd('/')
    private val canonicalUrl get() = canonical.url("/").toString().trimEnd('/')

    @Before fun setUp() {
        FakeAndroidKeyStore.setup()
        old.start()
        canonical.start()
        store = CalibreCredentialStore(context)
        store.clear()
        store.saveBearerAccount(oldUrl, "alice@example.com", "tok", accountId = "native:7")
        account = store.getAccount() as AccountCredentials.Bearer
        db = Room.inMemoryDatabaseBuilder(context, EReaderDatabase::class.java).allowMainThreadQueries().build()
        runBlocking {
            db.documentDao().insert(DocumentEntity(id = 1, metadataId = null, contentHash = "one", title = "Book",
                author = null, downloadUrl = "", localPath = "", coverPath = null, downloadedAt = 0))
            db.memoryBookmarkDao().save(MemoryBookmark(1, memoryAccountKey(oldUrl, account.subject), "{}", "Why?", "", 1))
        }
    }

    @After fun tearDown() {
        db.close()
        runCatching { old.shutdown() }
        runCatching { canonical.shutdown() }
    }

    private fun me(id: String, serverUrl: String? = null) = MockResponse().setBody(
        """{"user":{"id":"$id","backend":"native"},"version":1""" +
            (serverUrl?.let { ""","server_url":"$it"""" } ?: "") + "}",
    )

    private fun follow() = runBlocking {
        ServerAddressFollower(store, ConnectServerViewModel.defaultOnboardingClient(), db.memoryBookmarkDao()::moveAccount).follow()
    }

    private suspend fun noteAt(serverUrl: String) =
        db.memoryBookmarkDao().observe(1, memoryAccountKey(serverUrl, account.subject)).first()?.note

    @Test fun `moves to the canonical address with the session, highlights and notes`() = runBlocking {
        old.enqueue(me("native:7", canonicalUrl))
        canonical.enqueue(me("native:7"))

        follow()

        val moved = store.getAccount() as AccountCredentials.Bearer
        assertThat(moved.baseUrl).isEqualTo(canonicalUrl)
        assertThat(moved.token).isEqualTo("tok")
        assertThat(moved.previousHighlightScopes).containsExactly(account.highlightScope())
        assertThat(noteAt(canonicalUrl)).isEqualTo("Why?")
        assertThat(canonical.takeRequest(10, TimeUnit.SECONDS)!!.getHeader("Authorization")).isEqualTo("Bearer tok")
    }

    @Test fun `stays when the canonical address knows a different account`() = runBlocking {
        old.enqueue(me("native:7", canonicalUrl))
        canonical.enqueue(me("native:8"))

        follow()

        assertThat(store.getAccount()).isEqualTo(account)
        assertThat(noteAt(oldUrl)).isEqualTo("Why?")
    }

    @Test fun `stays when the server names no canonical address`() = runBlocking {
        old.enqueue(me("native:7"))

        follow()

        assertThat(store.getAccount()).isEqualTo(account)
        assertThat(canonical.requestCount).isEqualTo(0)
    }
}
