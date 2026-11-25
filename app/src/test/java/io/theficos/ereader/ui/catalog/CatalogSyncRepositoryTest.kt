package io.theficos.ereader.ui.catalog

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.auth.AccountCredentials
import io.theficos.ereader.auth.CalibreCredentialStore
import io.theficos.ereader.auth.SavedCatalog
import io.theficos.ereader.data.opds.CatalogFailure
import io.theficos.ereader.data.opds.CatalogResult
import io.theficos.ereader.data.opds.CatalogSyncClient
import io.theficos.ereader.data.opds.CatalogSyncRepository
import io.theficos.ereader.data.opds.CatalogSyncStatus
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class CatalogSyncRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var store: CalibreCredentialStore
    private lateinit var repo: CatalogSyncRepository
    private val merged = mutableListOf<Pair<String, String>>()

    private val a = SavedCatalog.newId()
    private val b = SavedCatalog.newId()

    @Before fun setUp() {
        FakeAndroidKeyStore.setup()
        server = MockWebServer().apply { start() }
        store = CalibreCredentialStore(ApplicationProvider.getApplicationContext()).apply { clear() }
        store.saveBearerAccount(server.url("/").toString(), "a@b.c", "tok")
        val client = CatalogSyncClient(
            { (store.getAccount() as? AccountCredentials.Bearer)?.baseUrl },
            // One answer per request, so a dropped connection is not retried into the next one.
            OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
        )
        repo = CatalogSyncRepository(store, client) { from, into -> merged += from to into }
    }

    @After fun tearDown() {
        server.shutdown()
        store.clear()
    }

    private fun listed(id: String, name: String, updated: String, kind: String = "opds", needsLogin: Boolean = false, position: Int = 0) =
        """{"id":"$id","name":"$name","kind":"$kind","host":"https://books.example","has_login":true,""" +
            """"needs_login":$needsLogin,"position":$position,"updated_at":"$updated"}"""

    private fun list(revision: String, vararg catalogs: String) =
        json("""{"revision":"$revision","catalogs":[${catalogs.joinToString(",")}]}""")

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun detail(code: Int, detail: String) = json("""{"detail":"$detail"}""", code)

    private fun secret(url: String, user: String? = null, pass: String? = null) = json(
        JSONObject().put("url", url).apply { user?.let { put("username", it) }; pass?.let { put("password", it) } }.toString(),
    )

    private fun saved(id: String, name: String, updated: String) =
        json("""{"id":"$id","name":"$name","kind":"opds","host":"https://x.example","position":0,"updated_at":"$updated"}""")

    private fun request(): RecordedRequest = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "no request" }

    private fun device(url: String, name: String = "Mine", deviceOnly: Boolean = false) =
        SavedCatalog(SavedCatalog.newId(), name, AccountCredentials.OpdsOnly(url), deviceOnly = deviceOnly)

    /** The account holds A, with its secret fetched, at revision 1. */
    private suspend fun synced() {
        server.enqueue(list("1", listed(a, "Books", "t1", kind = "calibre")))
        server.enqueue(secret("https://books.example/opds", "reader", "pw"))
        assertThat(repo.pull()).isEqualTo(CatalogResult.Done)
        request(); request()
    }

    @Test fun `a pull applies the account's list with its secrets and keeps device catalogs after it`() = runTest {
        val mine = device("https://home.example/opds")
        store.saveCatalog(mine)
        server.enqueue(list("3", listed(b, "Locked", "t2", needsLogin = true, position = 1), listed(a, "Books", "t1", kind = "calibre")))
        server.enqueue(secret("https://books.example/opds", "reader", "pw"))

        assertThat(repo.pull()).isEqualTo(CatalogResult.Done)

        val first = request()
        assertThat(first.path).isEqualTo("/catalogs/v1/catalogs")
        assertThat(first.getHeader("If-None-Match")).isNull()
        assertThat(request().path).isEqualTo("/catalogs/v1/catalogs/$a/secret")
        assertThat(server.requestCount).isEqualTo(2)

        val catalogs = store.catalogs.value
        assertThat(catalogs.map { it.id }).containsExactly(a, b, mine.id).inOrder()
        assertThat(catalogs[0].catalog).isEqualTo(AccountCredentials.OpdsOnly("https://books.example/opds", "reader", "pw", calibre = true))
        assertThat(catalogs[0].onAccount).isTrue()
        assertThat(catalogs[1].catalog).isNull()
        assertThat(catalogs[1].needsLogin).isTrue()
        assertThat(catalogs[1].host).isEqualTo("https://books.example")
        assertThat(catalogs[2].awaitingUpload).isTrue()
        assertThat(store.catalogRevision.value).isEqualTo("3")
        assertThat(repo.status.first()).isEqualTo(CatalogSyncStatus.SYNCED)
        assertThat(repo.pendingUploads.first().map { it.id }).containsExactly(mine.id)
    }

    @Test fun `an unchanged list is a 304, and only edited catalogs fetch their secret again`() = runTest {
        synced()
        server.enqueue(MockResponse().setResponseCode(304))
        assertThat(repo.pull()).isEqualTo(CatalogResult.Done)
        assertThat(request().getHeader("If-None-Match")).isEqualTo("\"1\"")

        // A was edited (its updated_at moved) and B is new: both secrets are read.
        server.enqueue(list("2", listed(a, "Renamed", "t5"), listed(b, "Other", "t6", position = 1)))
        server.enqueue(secret("https://books.example/v2/opds"))
        server.enqueue(secret("https://other.example/opds"))
        assertThat(repo.pull()).isEqualTo(CatalogResult.Done)
        request()
        assertThat(request().path).isEqualTo("/catalogs/v1/catalogs/$a/secret")
        assertThat(request().path).isEqualTo("/catalogs/v1/catalogs/$b/secret")
        assertThat(store.catalogs.value.map { it.name }).containsExactly("Renamed", "Other").inOrder()
        assertThat(store.catalogs.value[0].catalog?.baseUrl).isEqualTo("https://books.example/v2/opds")

        // B deleted elsewhere, A untouched: no secret, B gone.
        server.enqueue(list("3", listed(a, "Renamed", "t5")))
        assertThat(repo.pull()).isEqualTo(CatalogResult.Done)
        request()
        assertThat(store.catalogs.value.map { it.id }).containsExactly(a)
        assertThat(server.requestCount).isEqualTo(7)
    }

    @Test fun `a catalog deleted while the list was read is skipped`() = runTest {
        server.enqueue(list("1", listed(a, "Gone", "t1")))
        server.enqueue(detail(404, "not_found"))
        assertThat(repo.pull()).isEqualTo(CatalogResult.Done)
        assertThat(store.catalogs.value).isEmpty()
        assertThat(repo.status.first()).isEqualTo(CatalogSyncStatus.SYNCED)
    }

    @Test fun `a secret the server can no longer read marks the catalog for a new login`() = runTest {
        server.enqueue(list("1", listed(a, "Books", "t1")))
        server.enqueue(detail(409, "needs_login"))
        assertThat(repo.pull()).isEqualTo(CatalogResult.Done)
        assertThat(store.catalogs.value.single().needsLogin).isTrue()
    }

    @Test fun `a dropped connection while reading secrets applies nothing`() = runTest {
        val mine = device("https://home.example/opds")
        store.saveCatalog(mine)
        server.enqueue(list("1", listed(a, "Books", "t1")))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertThat(repo.pull()).isEqualTo(CatalogResult.Failed(CatalogFailure.OFFLINE))
        assertThat(store.catalogs.value).containsExactly(mine)
        assertThat(store.catalogRevision.value).isNull()
        assertThat(repo.status.first()).isEqualTo(CatalogSyncStatus.CHECKING)
    }

    @Test fun `a server without catalog sync leaves the list on the device`() = runTest {
        server.enqueue(detail(404, "Not Found"))
        assertThat(repo.pull()).isEqualTo(CatalogResult.Failed(CatalogFailure.NOT_SUPPORTED))
        assertThat(repo.status.first()).isEqualTo(CatalogSyncStatus.NOT_SUPPORTED)

        val mine = device("https://home.example/opds")
        assertThat(repo.save(mine)).isEqualTo(CatalogResult.Done)
        assertThat(store.catalogs.value).containsExactly(mine)
        assertThat(repo.pendingUploads.first()).isEmpty()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun `an expired session changes nothing and is not a sign-out`() = runTest {
        synced()
        server.enqueue(json("""{"detail":"Not authenticated"}""", 401))
        assertThat(repo.remove(a)).isEqualTo(CatalogResult.Failed(CatalogFailure.SIGN_IN_REQUIRED))
        assertThat(request().method).isEqualTo("DELETE")
        assertThat(store.getAccount()).isNotNull()
        assertThat(store.catalogs.value.map { it.id }).containsExactly(a)
        assertThat(store.catalogAccount()?.password).isEqualTo("pw")
    }

    @Test fun `a catalog saved on a synced account goes to the server first`() = runTest {
        synced()
        val entry = SavedCatalog(SavedCatalog.newId(), " Kavita ", AccountCredentials.OpdsOnly("https://kavita.example/api/opds/KEY", "alice", "hunter2"))
        server.enqueue(saved(entry.id, "Kavita", "t9"))

        assertThat(repo.save(entry, toAccount = true)).isEqualTo(CatalogResult.Done)

        val put = request()
        assertThat(put.method).isEqualTo("PUT")
        assertThat(put.path).isEqualTo("/catalogs/v1/catalogs/${entry.id}")
        val body = JSONObject(put.body.readUtf8())
        assertThat(body.getString("name")).isEqualTo("Kavita")
        assertThat(body.getString("kind")).isEqualTo("opds")
        assertThat(body.getString("url")).isEqualTo("https://kavita.example/api/opds/KEY")
        assertThat(body.getString("username")).isEqualTo("alice")
        assertThat(body.getString("password")).isEqualTo("hunter2")
        val stored = store.catalogs.value.last()
        assertThat(stored.id).isEqualTo(entry.id)
        assertThat(stored.remoteUpdatedAt).isEqualTo("t9")
    }

    @Test fun `the server's refusals map to reasons and change nothing`() = runTest {
        synced()
        val cases = listOf(
            detail(409, "catalog_limit_reached") to CatalogFailure.LIMIT_REACHED,
            detail(422, "https_required") to CatalogFailure.HTTPS_REQUIRED,
            detail(422, "credentials_in_url") to CatalogFailure.CREDENTIALS_IN_URL,
            detail(422, "invalid_url") to CatalogFailure.INVALID_URL,
            detail(422, "invalid_name") to CatalogFailure.INVALID_NAME,
            detail(422, "login_incomplete") to CatalogFailure.LOGIN_INCOMPLETE,
            detail(409, "needs_login") to CatalogFailure.NEEDS_LOGIN,
            json("""{"detail":[{"loc":["body","url"],"msg":"field required"}]}""", 422) to CatalogFailure.REJECTED,
            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) to CatalogFailure.OFFLINE,
            // A captive portal's sign-in page instead of the API's answer.
            MockResponse().setBody("<html>Sign in to the Wi-Fi</html>") to CatalogFailure.OFFLINE,
        )
        for ((response, reason) in cases) {
            server.enqueue(response)
            assertThat(repo.save(device("http://plain.example/opds"), toAccount = true)).isEqualTo(CatalogResult.Failed(reason))
        }
        assertThat(store.catalogs.value.map { it.id }).containsExactly(a)
        // A login half filled in never reaches the server.
        val half = SavedCatalog(SavedCatalog.newId(), "x", AccountCredentials.OpdsOnly("https://x.example", "alice", null))
        assertThat(repo.save(half, toAccount = true)).isEqualTo(CatalogResult.Failed(CatalogFailure.REJECTED))
        assertThat(server.requestCount).isEqualTo(2 + cases.size)
    }

    @Test fun `a catalog kept on this device only never reaches the server`() = runTest {
        synced()
        val mine = device("https://home.example/opds", deviceOnly = true)
        assertThat(repo.save(mine)).isEqualTo(CatalogResult.Done)
        assertThat(repo.save(mine.copy(name = "Home"))).isEqualTo(CatalogResult.Done)
        assertThat(server.requestCount).isEqualTo(2)

        // Reordering sends the account's catalogs only, after checking the list is current.
        server.enqueue(MockResponse().setResponseCode(304))
        server.enqueue(list("2", listed(a, "Books", "t1", kind = "calibre")))
        assertThat(repo.reorder(listOf(mine.id, a))).isEqualTo(CatalogResult.Done)
        request()
        assertThat(JSONObject(request().body.readUtf8()).getJSONArray("ids").toString()).isEqualTo("""["$a"]""")
        assertThat(store.catalogs.value.map { it.id }).containsExactly(mine.id, a).inOrder()

        assertThat(repo.remove(mine.id)).isEqualTo(CatalogResult.Done)
        assertThat(server.requestCount).isEqualTo(4)
        assertThat(repo.pendingUploads.first()).isEmpty()
        // An account catalog can't be moved off the account.
        assertThat(repo.save(store.catalogs.value.single().copy(deviceOnly = true)))
            .isEqualTo(CatalogResult.Failed(CatalogFailure.REJECTED))
    }

    @Test fun `removing an account catalog deletes it on the server`() = runTest {
        synced()
        server.enqueue(MockResponse().setResponseCode(204))
        assertThat(repo.remove(a)).isEqualTo(CatalogResult.Done)
        val delete = request()
        assertThat(delete.method).isEqualTo("DELETE")
        assertThat(delete.path).isEqualTo("/catalogs/v1/catalogs/$a")
        assertThat(store.catalogs.value).isEmpty()
    }

    @Test fun `reorder sends the account's catalogs and a conflict fetches the list again`() = runTest {
        server.enqueue(list("1", listed(a, "A", "t1"), listed(b, "B", "t1", position = 1)))
        server.enqueue(secret("https://a.example/opds"))
        server.enqueue(secret("https://b.example/opds"))
        repo.pull()
        repeat(3) { request() }
        val mine = device("https://home.example/opds")
        store.saveCatalog(mine)

        // The server bumps the row that moved, so only B's secret is read again.
        server.enqueue(MockResponse().setResponseCode(304))
        server.enqueue(list("2", listed(b, "B", "t2"), listed(a, "A", "t1", position = 1)))
        server.enqueue(secret("https://b.example/opds"))
        assertThat(repo.reorder(listOf(mine.id, b, a))).isEqualTo(CatalogResult.Done)
        assertThat(request().getHeader("If-None-Match")).isEqualTo("\"1\"")
        val order = request()
        assertThat(order.path).isEqualTo("/catalogs/v1/order")
        assertThat(JSONObject(order.body.readUtf8()).getJSONArray("ids").toString()).isEqualTo("""["$b","$a"]""")
        assertThat(request().path).isEqualTo("/catalogs/v1/catalogs/$b/secret")
        assertThat(store.catalogs.value.map { it.id }).containsExactly(mine.id, b, a).inOrder()
        assertThat(store.catalogRevision.value).isEqualTo("2")

        server.enqueue(MockResponse().setResponseCode(304))
        server.enqueue(detail(409, "order_mismatch"))
        server.enqueue(MockResponse().setResponseCode(304))
        assertThat(repo.reorder(listOf(a, b, mine.id))).isEqualTo(CatalogResult.Failed(CatalogFailure.ORDER_CHANGED))
        request()
        assertThat(request().path).isEqualTo("/catalogs/v1/order")
        assertThat(request().getHeader("If-None-Match")).isEqualTo("\"2\"")
        assertThat(store.catalogs.value.map { it.id }).containsExactly(mine.id, b, a).inOrder()
    }

    @Test fun `the sign-in answer uploads the accepted, keeps the declined and merges a twin by address`() = runTest {
        val upload = device("https://kavita.example/opds", "Kavita")
        val keep = device("https://home.example/opds", "Home")
        val twin = device("https://books.example/opds", "Same as A")
        listOf(upload, keep, twin).forEach(store::saveCatalog)
        store.setActiveCatalog(twin.id)
        synced()
        assertThat(repo.pendingUploads.first().map { it.id }).containsExactly(upload.id, keep.id, twin.id).inOrder()

        server.enqueue(MockResponse().setResponseCode(304))
        server.enqueue(saved(upload.id, "Kavita", "t9"))
        assertThat(repo.uploadPending(setOf(upload.id, twin.id))).isEqualTo(CatalogResult.Done)

        request()
        assertThat(request().path).isEqualTo("/catalogs/v1/catalogs/${upload.id}")
        assertThat(server.requestCount).isEqualTo(4)
        assertThat(merged).containsExactly(twin.id to a)
        val byId = store.catalogs.value.associateBy { it.id }
        assertThat(byId.keys).containsExactly(a, upload.id, keep.id)
        assertThat(byId.getValue(upload.id).onAccount).isTrue()
        assertThat(byId.getValue(keep.id).deviceOnly).isTrue()
        assertThat(store.activeCatalog.value?.id).isEqualTo(a)
        assertThat(repo.pendingUploads.first()).isEmpty()
    }

    @Test fun `a catalog over the account's limit stays on this device only`() = runTest {
        val first = device("https://one.example/opds")
        val second = device("https://two.example/opds")
        listOf(first, second).forEach(store::saveCatalog)
        synced()
        server.enqueue(MockResponse().setResponseCode(304))
        server.enqueue(detail(409, "catalog_limit_reached"))
        server.enqueue(detail(422, "https_required"))

        assertThat(repo.uploadPending(setOf(first.id, second.id)))
            .isEqualTo(CatalogResult.Failed(CatalogFailure.LIMIT_REACHED))
        assertThat(store.catalogs.value.filter { it.id != a }.all { it.deviceOnly }).isTrue()
    }

    @Test fun `offline, the declined are kept on the device and the accepted wait`() = runTest {
        val accepted = device("https://one.example/opds")
        val declined = device("https://two.example/opds")
        listOf(accepted, declined).forEach(store::saveCatalog)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertThat(repo.uploadPending(setOf(accepted.id))).isEqualTo(CatalogResult.Failed(CatalogFailure.OFFLINE))
        val byId = store.catalogs.value.associateBy { it.id }
        assertThat(byId.getValue(accepted.id).awaitingUpload).isTrue()
        assertThat(byId.getValue(declined.id).deviceOnly).isTrue()
    }

    @Test fun `an edit of a catalog waiting for the sign-in question stays on this device`() = runTest {
        // For example kept at sign-out by an earlier reader of this device.
        val kept = SavedCatalog(SavedCatalog.newId(), "Kept", AccountCredentials.OpdsOnly("https://home.example/opds", "alice", "secret"))
        store.saveCatalog(kept)
        synced()

        assertThat(repo.save(kept.copy(name = "Renamed"))).isEqualTo(CatalogResult.Done)
        assertThat(server.requestCount).isEqualTo(2)
        assertThat(store.catalogs.value.single { it.id == kept.id }.awaitingUpload).isTrue()
        assertThat(repo.pendingUploads.first().map { it.id }).containsExactly(kept.id)

        // Only the reader's own choice takes it to the account.
        server.enqueue(saved(kept.id, "Renamed", "t9"))
        assertThat(repo.save(kept.copy(name = "Renamed"), toAccount = true)).isEqualTo(CatalogResult.Done)
        assertThat(request().method).isEqualTo("PUT")
        assertThat(store.catalogs.value.single { it.id == kept.id }.onAccount).isTrue()
    }

    @Test fun `an account catalog is edited after checking the list, and one removed elsewhere is not saved back`() = runTest {
        synced()
        val books = store.catalogs.value.single()
        server.enqueue(MockResponse().setResponseCode(304))
        server.enqueue(saved(a, "Renamed", "t2"))
        assertThat(repo.save(books.copy(name = "Renamed"))).isEqualTo(CatalogResult.Done)
        assertThat(request().getHeader("If-None-Match")).isEqualTo("\"1\"")
        assertThat(request().method).isEqualTo("PUT")

        // Another device removed it meanwhile.
        server.enqueue(list("2"))
        assertThat(repo.save(books.copy(name = "Again"))).isEqualTo(CatalogResult.Failed(CatalogFailure.REMOVED_ELSEWHERE))
        assertThat(request().method).isEqualTo("GET")
        assertThat(server.requestCount).isEqualTo(5)
        assertThat(store.catalogs.value).isEmpty()
    }

    @Test fun `reordering after another device removed a catalog moves nothing`() = runTest {
        server.enqueue(list("1", listed(a, "A", "t1"), listed(b, "B", "t1", position = 1)))
        server.enqueue(secret("https://a.example/opds"))
        server.enqueue(secret("https://b.example/opds"))
        repo.pull()
        repeat(3) { request() }

        server.enqueue(list("2", listed(b, "B", "t1")))
        assertThat(repo.reorder(listOf(b, a))).isEqualTo(CatalogResult.Failed(CatalogFailure.REMOVED_ELSEWHERE))
        request()
        assertThat(server.requestCount).isEqualTo(4)
        assertThat(store.catalogs.value.map { it.id }).containsExactly(b)
    }

    @Test fun `adding an address the list already has updates that catalog`() = runTest {
        synced()
        val again = SavedCatalog(SavedCatalog.newId(), "Again", AccountCredentials.OpdsOnly("https://books.example/opds", "reader", "new"))
        server.enqueue(MockResponse().setResponseCode(304))
        server.enqueue(saved(a, "Again", "t2"))
        assertThat(repo.save(again, toAccount = true)).isEqualTo(CatalogResult.Done)
        request()
        assertThat(request().path).isEqualTo("/catalogs/v1/catalogs/$a")
        assertThat(store.catalogs.value.map { it.id }).containsExactly(a)
        assertThat(store.catalogs.value.single().catalog?.password).isEqualTo("new")

        // On this device it stays where it was too.
        val mine = device("https://home.example/opds", deviceOnly = true)
        store.saveCatalog(mine)
        assertThat(repo.save(device("https://home.example/opds", "Home"), toAccount = true)).isEqualTo(CatalogResult.Done)
        assertThat(store.catalogs.value.map { it.id }).containsExactly(a, mine.id).inOrder()
        assertThat(store.catalogs.value.last().name).isEqualTo("Home")
        assertThat(store.catalogs.value.last().deviceOnly).isTrue()
        assertThat(server.requestCount).isEqualTo(4)
    }

    @Test fun `a device catalog with its own login for an account address stays on this device`() = runTest {
        val mine = SavedCatalog(SavedCatalog.newId(), "Mine", AccountCredentials.OpdsOnly("https://books.example/opds", "other", "own-password"))
        store.saveCatalog(mine)
        synced()
        server.enqueue(MockResponse().setResponseCode(304))

        assertThat(repo.uploadPending(setOf(mine.id))).isEqualTo(CatalogResult.Done)
        assertThat(merged).isEmpty()
        val kept = store.catalogs.value.single { it.id == mine.id }
        assertThat(kept.deviceOnly).isTrue()
        assertThat(kept.catalog?.password).isEqualTo("own-password")
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test fun `a legacy Calibre-Web sign-in never talks to the catalog API`() = runTest {
        store.saveBasicAccount(server.url("/").toString(), "reader", "pw")
        assertThat(repo.pull()).isEqualTo(CatalogResult.Done)
        assertThat(repo.save(device("https://home.example/opds"), toAccount = true)).isEqualTo(CatalogResult.Done)
        assertThat(repo.status.first()).isEqualTo(CatalogSyncStatus.DEVICE_ONLY)
        assertThat(server.requestCount).isEqualTo(0)
    }
}
