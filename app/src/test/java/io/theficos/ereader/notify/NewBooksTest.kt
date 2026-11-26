package io.theficos.ereader.notify

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.auth.AccountCredentials
import io.theficos.ereader.auth.SavedCatalog
import io.theficos.ereader.data.opds.OpdsFeed
import io.theficos.ereader.data.opds.OpdsHttpException
import io.theficos.ereader.data.opds.OpdsPublication
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownServiceException
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class NewBooksTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var store: NotificationPreferencesStore

    /** Feeds by address; a Throwable value is thrown instead. */
    private val feeds = mutableMapOf<String, Any>()
    private val posted = mutableListOf<Pair<String, List<String>>>()
    private val catalogs = mutableListOf<SavedCatalog>()

    private fun book(id: String, title: String = "Book $id") =
        OpdsPublication(title = title, author = null, epubDownloadHref = "https://c.example/dl/$id", coverUrl = null, entryId = "urn:$id")

    private fun catalog(id: String, url: String) =
        SavedCatalog(id = id, name = "Catalog $id", catalog = AccountCredentials.OpdsOnly(url)).also { catalogs += it }

    private fun root(url: String, latest: String?) { feeds[url] = OpdsFeed("root", emptyList(), emptyList(), latestHref = latest) }
    private fun newest(url: String, vararg books: OpdsPublication) { feeds[url] = OpdsFeed("new", emptyList(), books.toList()) }

    private val check get() = NewBooksCheck(
        catalogs = { catalogs.toList() },
        fetch = { url ->
            when (val value = feeds[url] ?: throw OpdsHttpException(404, "missing")) {
                is Throwable -> throw value
                else -> value as OpdsFeed
            }
        },
        store = store,
        notify = { catalog, added -> posted += catalog.id to added.map { it.title } },
    )

    @Before fun setUp() {
        context.getSharedPreferences("notification_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        store = NotificationPreferencesStore(context)
    }

    @Test fun `the first check records a baseline without notifying`() = runTest {
        catalog("a", "https://a.example/opds")
        root("https://a.example/opds", "https://a.example/opds/new")
        newest("https://a.example/opds/new", book("1"), book("2"))

        assertThat(check.run()).isTrue()

        assertThat(posted).isEmpty()
        assertThat(store.seenIds("a")).containsExactly("urn:1", "urn:2").inOrder()
    }

    @Test fun `books added since the last check are notified once`() = runTest {
        catalog("a", "https://a.example/opds")
        root("https://a.example/opds", "https://a.example/opds/new")
        newest("https://a.example/opds/new", book("1"))
        check.run()

        newest("https://a.example/opds/new", book("3", "Dune"), book("2"), book("1"))
        check.run()
        check.run()

        assertThat(posted).containsExactly("a" to listOf("Dune", "Book 2"))
        assertThat(store.seenIds("a")).containsExactly("urn:3", "urn:2", "urn:1").inOrder()
    }

    @Test fun `a book that falls off the feed and returns is not new`() {
        val first = diffNewBooks(null, listOf(book("1"), book("2")))
        val second = diffNewBooks(first.remembered, listOf(book("3")))
        val third = diffNewBooks(second.remembered, listOf(book("2"), book("3")))
        assertThat(second.added.map { it.stableId }).containsExactly("urn:3")
        assertThat(third.added).isEmpty()
    }

    @Test fun `remembered ids are capped, newest kept`() {
        val previous = (1..MAX_REMEMBERED_IDS).map { "urn:old$it" }
        val diff = diffNewBooks(previous, listOf(book("new1"), book("new2")))
        assertThat(diff.remembered).hasSize(MAX_REMEMBERED_IDS)
        assertThat(diff.remembered.take(3)).containsExactly("urn:new1", "urn:new2", "urn:old1").inOrder()
        assertThat(diff.remembered.last()).isEqualTo("urn:old${MAX_REMEMBERED_IDS - 2}")
    }

    @Test fun `without an entry id the download address stands in`() {
        val pub = book("1").copy(entryId = null)
        assertThat(diffNewBooks(null, listOf(pub)).remembered).containsExactly("https://c.example/dl/1")
    }

    @Test fun `a catalog without a newest feed is skipped and recorded`() = runTest {
        catalog("plain", "https://p.example/opds")
        root("https://p.example/opds", null)

        assertThat(check.run()).isTrue()

        assertThat(posted).isEmpty()
        assertThat(store.seenIds("plain")).isNull()
        assertThat(store.settings.value.catalogsWithoutLatest).containsExactly("plain")
    }

    @Test fun `a refused login or a missing feed is quiet and not retried`() = runTest {
        catalog("a", "https://a.example/opds")
        feeds["https://a.example/opds"] = OpdsHttpException(401, "OPDS fetch 401")
        catalog("b", "https://b.example/opds")

        assertThat(check.run()).isTrue()
        assertThat(posted).isEmpty()
    }

    @Test fun `server errors and timeouts ask for a retry, other catalogs still run`() = runTest {
        catalog("down", "https://d.example/opds")
        feeds["https://d.example/opds"] = OpdsHttpException(503, "OPDS fetch 503")
        catalog("slow", "https://s.example/opds")
        feeds["https://s.example/opds"] = SocketTimeoutException("timeout")
        catalog("ok", "https://o.example/opds")
        root("https://o.example/opds", "https://o.example/new")
        newest("https://o.example/new", book("1"))

        assertThat(check.run()).isFalse()
        assertThat(store.seenIds("ok")).containsExactly("urn:1")
    }

    @Test fun `plain http refused by Android is not retried`() = runTest {
        catalog("http", "http://h.example/opds")
        feeds["http://h.example/opds"] = UnknownServiceException("CLEARTEXT communication to h.example not permitted")
        assertThat(check.run()).isTrue()
    }

    @Test fun `catalogs without an address or needing a login are not fetched`() = runTest {
        catalogs += SavedCatalog(id = "none", name = "No address", catalog = null, listedHost = "https://x.example")
        catalogs += SavedCatalog(id = "login", name = "Login", catalog = AccountCredentials.OpdsOnly("https://l.example"), needsLogin = true)
        feeds["https://l.example"] = IOException("should not be fetched")
        assertThat(check.run()).isTrue()
    }

    @Test fun `ids of removed catalogs are forgotten, and switching off clears the baseline`() = runTest {
        catalog("a", "https://a.example/opds")
        root("https://a.example/opds", "https://a.example/opds/new")
        newest("https://a.example/opds/new", book("1"))
        check.run()
        catalogs.clear()
        check.run()
        assertThat(store.seenIds("a")).isNull()

        catalog("b", "https://b.example/opds")
        root("https://b.example/opds", "https://b.example/opds/new")
        newest("https://b.example/opds/new", book("1"))
        check.run()
        store.setNewBooks(false)
        assertThat(store.seenIds("b")).isNull()
    }
}
