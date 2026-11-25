package io.theficos.ereader.ui.settings

import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.auth.AccountCredentials
import io.theficos.ereader.auth.CalibreCredentialStore
import io.theficos.ereader.auth.SavedCatalog
import io.theficos.ereader.data.opds.CatalogSyncClient
import io.theficos.ereader.data.opds.CatalogSyncRepository
import io.theficos.ereader.data.opds.ServerProbe
import io.theficos.ereader.ui.catalog.FakeAndroidKeyStore
import io.theficos.ereader.ui.onboarding.CatalogCheck
import io.theficos.ereader.ui.uiText
import io.theficos.quire.R
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class CatalogsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var server: MockWebServer
    private lateinit var store: CalibreCredentialStore
    private lateinit var repository: CatalogSyncRepository
    private val viewModels = mutableListOf<CatalogsViewModel>()

    /** Answers keyed by "METHOD path"; anything else is a 404. */
    private val routes = mutableMapOf<String, MockResponse>()
    private val requests = LinkedBlockingQueue<RecordedRequest>()

    private val feed =
        """<?xml version="1.0" encoding="utf-8"?>
           <feed xmlns="http://www.w3.org/2005/Atom"><title>Books</title>
             <entry><title>All</title>
               <link rel="subsection" type="application/atom+xml;profile=opds-catalog;kind=navigation" href="/feed/all"/>
             </entry>
           </feed>"""

    @Before fun setUp() {
        FakeAndroidKeyStore.setup()
        Dispatchers.setMain(testDispatcher)
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    return routes["${request.method} ${request.path}"] ?: MockResponse().setResponseCode(404)
                }
            }
            start()
        }
        store = CalibreCredentialStore(ApplicationProvider.getApplicationContext()).apply { clear() }
        val client = CatalogSyncClient(
            { (store.getAccount() as? AccountCredentials.Bearer)?.baseUrl },
            // One answer per request, so a dropped connection is not retried into the next one.
            OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
        )
        repository = CatalogSyncRepository(store, client) { _, _ -> }
    }

    @After fun tearDown() {
        try {
            runTest { viewModels.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        } finally {
            runCatching { server.shutdown() }
            store.clear()
            Dispatchers.resetMain()
        }
    }

    private fun newViewModel(): CatalogsViewModel =
        CatalogsViewModel(store, repository, CatalogCheck(ServerProbe(), OkHttpClient()), testDispatcher)
            .also { viewModels += it }

    private fun atom(body: String = feed) =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/atom+xml").setBody(body)

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun request(): RecordedRequest = checkNotNull(requests.poll(5, TimeUnit.SECONDS)) { "no request" }

    private fun device(url: String, name: String = SavedCatalog.defaultName(url)) =
        SavedCatalog(SavedCatalog.newId(), name, AccountCredentials.OpdsOnly(url))

    /** Signed in to a Quire server that syncs catalogs, holding [listed]. */
    private fun signedIn(vararg listed: String) {
        routes["GET /catalogs/v1/catalogs"] = json("""{"revision":"1","catalogs":[${listed.joinToString(",")}]}""")
        store.saveBearerAccount(server.url("/").toString(), "a@b.c", "tok")
    }

    private fun CatalogsViewModel.fill(address: String, username: String = "", password: String = "", name: String = "") {
        onFormChange(form.value!!.copy(address = address, username = username, password = password, name = name))
    }

    @Test fun `adding a catalog tests it and keeps it on this device without an account`() = runTest {
        routes["GET /feed"] = atom()
        val vm = newViewModel()
        vm.add()
        vm.fill(server.url("/feed").toString(), name = "  ")
        vm.saveForm()
        advanceUntilIdle()

        assertThat(vm.form.value).isNull()
        val saved = store.catalogs.value.single()
        assertThat(saved.catalog).isEqualTo(AccountCredentials.OpdsOnly(server.url("/feed").toString()))
        assertThat(saved.name).isEqualTo(SavedCatalog.defaultName(server.url("/feed").toString()))
        assertThat(store.activeCatalog.value?.id).isEqualTo(saved.id)
    }

    @Test fun `a catalog that fails its test stays in the form with the reason`() = runTest {
        routes["GET /feed"] = MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Basic realm=\"books\"")
        val vm = newViewModel()
        vm.add()
        vm.fill(server.url("/feed").toString())
        vm.saveForm()
        advanceUntilIdle()

        assertThat(vm.form.value?.error).isEqualTo(uiText(R.string.onboarding_this_catalog_asked_for_a_username_and))
        assertThat(vm.form.value?.busy).isFalse()
        assertThat(store.catalogs.value).isEmpty()

        // Typing clears the reason.
        vm.fill(server.url("/feed").toString(), username = "a")
        assertThat(vm.form.value?.error).isNull()
    }

    @Test fun `half a login or an overlong name is refused before any request`() = runTest {
        val vm = newViewModel()
        vm.add()
        vm.fill(server.url("/feed").toString(), username = "alice")
        vm.saveForm()
        assertThat(vm.form.value?.error).isEqualTo(uiText(R.string.catalogs_failed_login_incomplete))

        vm.fill(server.url("/feed").toString(), name = "x".repeat(SavedCatalog.MAX_NAME_LENGTH + 1))
        vm.saveForm()
        advanceUntilIdle()
        assertThat(vm.form.value?.error).isEqualTo(uiText(R.string.catalogs_failed_name))
        assertThat(requests.poll(250, TimeUnit.MILLISECONDS)).isNull()
    }

    @Test fun `a new login for the same Calibre-Web address is tested there and keeps the kind`() = runTest {
        val url = server.url("/opds").toString()
        val books = SavedCatalog(SavedCatalog.newId(), "Books", AccountCredentials.OpdsOnly(url, "reader", "old", calibre = true))
        store.saveCatalog(books)
        routes["GET /opds"] = atom()
        val vm = newViewModel()

        vm.edit(books.id)
        assertThat(vm.form.value?.address).isEqualTo(url)
        vm.onFormChange(vm.form.value!!.copy(password = "new", name = "My books"))
        vm.saveForm()
        advanceUntilIdle()

        assertThat(vm.form.value).isNull()
        val tested = request()
        assertThat(tested.path).isEqualTo("/opds")
        assertThat(tested.getHeader("Authorization")).isEqualTo(Credentials.basic("reader", "new"))
        // One request: the address was not probed again as if it were new.
        assertThat(requests.poll(250, TimeUnit.MILLISECONDS)).isNull()
        val saved = store.catalogs.value.single()
        assertThat(saved.catalog).isEqualTo(AccountCredentials.OpdsOnly(url, "reader", "new", calibre = true))
        assertThat(saved.name).isEqualTo("My books")
    }

    @Test fun `renaming alone saves without testing the connection`() = runTest {
        val books = device(server.url("/feed").toString())
        store.saveCatalog(books)
        val vm = newViewModel()
        vm.edit(books.id)
        vm.onFormChange(vm.form.value!!.copy(name = "Renamed"))
        vm.saveForm()
        advanceUntilIdle()

        assertThat(vm.form.value).isNull()
        assertThat(store.catalogs.value.single().name).isEqualTo("Renamed")
        assertThat(requests.poll(250, TimeUnit.MILLISECONDS)).isNull()
    }

    @Test fun `renaming a catalog nobody was asked about yet never uploads its login`() = runTest {
        // For example kept by an earlier reader at sign-out, on a device another reader now signs in on.
        val kept = SavedCatalog(SavedCatalog.newId(), "Kept", AccountCredentials.OpdsOnly(server.url("/feed").toString(), "alice", "secret"))
        store.saveCatalog(kept)
        signedIn()
        val vm = newViewModel()
        repository.pull()
        request()
        vm.edit(kept.id)
        vm.onFormChange(vm.form.value!!.copy(name = "Renamed"))
        vm.saveForm()
        advanceUntilIdle()
        // Without a guard the save would go to the account on the IO dispatcher: wait for it either way.
        vm.form.first { it?.busy != true }

        assertThat(vm.form.value).isNull()
        assertThat(requests.poll(250, TimeUnit.MILLISECONDS)).isNull()
        val renamed = store.catalogs.value.single()
        assertThat(renamed.name).isEqualTo("Renamed")
        assertThat(renamed.awaitingUpload).isTrue()

        // Only "Save to account" takes it there.
        routes["PUT /catalogs/v1/catalogs/${kept.id}"] =
            json("""{"id":"${kept.id}","name":"Renamed","kind":"opds","host":"x","position":0,"updated_at":"t1"}""")
        vm.edit(kept.id)
        vm.saveForm(deviceOnly = false)
        vm.form.first { it?.busy != true }
        assertThat(vm.form.value).isNull()
        assertThat(request().method).isEqualTo("PUT")
        assertThat(store.catalogs.value.single().onAccount).isTrue()
    }

    @Test fun `the form's string form never shows the address or password`() {
        val form = CatalogForm(SavedCatalog.newId(), isNew = true, address = "https://books.example/opds/KEY", username = "alice", password = "hunter2")
        assertThat(form.toString()).doesNotContain("KEY")
        assertThat(form.toString()).doesNotContain("hunter2")
    }

    @Test fun `removing and reordering catalogs on this device`() = runTest {
        val a = device("https://a.example/opds")
        val b = device("https://b.example/opds")
        val c = device("https://c.example/opds")
        listOf(a, b, c).forEach(store::saveCatalog)
        val vm = newViewModel()

        vm.move(c.id, -1)
        advanceUntilIdle()
        assertThat(store.catalogs.value.map { it.id }).containsExactly(a.id, c.id, b.id).inOrder()
        vm.move(a.id, -1)
        advanceUntilIdle()
        assertThat(store.catalogs.value.map { it.id }).containsExactly(a.id, c.id, b.id).inOrder()

        vm.edit(c.id)
        vm.removeEditing()
        advanceUntilIdle()
        assertThat(vm.form.value).isNull()
        assertThat(store.catalogs.value.map { it.id }).containsExactly(a.id, b.id).inOrder()
    }

    @Test fun `the legacy sign-in's pinned row has no catalog form`() = runTest {
        store.saveBasicAccount("https://books.example", "reader", "pw")
        val vm = newViewModel()
        vm.edit(store.catalogs.value.single().id)
        assertThat(vm.form.value).isNull()
    }

    @Test fun `the sign-in question lists undecided catalogs and a no keeps them on this device`() = runTest {
        val mine = device("https://home.example/opds")
        store.saveCatalog(mine)
        signedIn()
        val vm = newViewModel()
        repository.pull()
        advanceUntilIdle()
        assertThat(vm.uploadQuestion.value.map { it.id }).containsExactly(mine.id)

        routes["GET /catalogs/v1/catalogs"] = MockResponse().setResponseCode(304)
        vm.answerUpload(emptySet())
        advanceUntilIdle()

        assertThat(vm.uploadQuestion.value).isEmpty()
        assertThat(vm.message.value).isNull()
        assertThat(store.catalogs.value.single().deviceOnly).isTrue()
        assertThat(requests.none { it.method == "PUT" }).isTrue()
    }

    @Test fun `putting the question off decides nothing`() = runTest {
        val mine = device("https://home.example/opds")
        store.saveCatalog(mine)
        signedIn()
        val vm = newViewModel()
        repository.pull()
        advanceUntilIdle()
        assertThat(vm.uploadQuestion.value).hasSize(1)

        vm.postponeUpload()
        advanceUntilIdle()
        assertThat(vm.uploadQuestion.value).isEmpty()
        assertThat(store.catalogs.value.single().awaitingUpload).isTrue()
    }

    @Test fun `a catalog whose login the server cannot read asks for it again`() = runTest {
        val id = SavedCatalog.newId()
        signedIn(
            """{"id":"$id","name":"Locked","kind":"opds","host":"https://books.example","has_login":true,""" +
                """"needs_login":true,"position":0,"updated_at":"t1"}""",
        )
        val vm = newViewModel()
        repository.pull()
        advanceUntilIdle()

        vm.edit(id)
        val form = vm.form.value!!
        assertThat(form.error).isEqualTo(uiText(R.string.catalogs_needs_login))
        assertThat(form.address).isEmpty()
        assertThat(form.name).isEqualTo("Locked")
    }

    @Test fun `at the account's limit a new catalog can stay on this device only`() = runTest {
        signedIn()
        val vm = newViewModel()
        repository.pull()
        routes["GET /feed"] = atom()
        vm.add()
        val id = vm.form.value!!.id
        routes["PUT /catalogs/v1/catalogs/$id"] = json("""{"detail":"catalog_limit_reached"}""", 409)
        vm.fill(server.url("/feed").toString())
        vm.saveForm()
        // The account call runs on the IO dispatcher, outside the test scheduler.
        vm.form.first { it?.busy == false }

        assertThat(vm.form.value?.error).isEqualTo(uiText(R.string.catalogs_failed_limit))
        assertThat(vm.form.value?.offerDeviceOnly).isTrue()
        assertThat(store.catalogs.value).isEmpty()

        vm.saveForm(deviceOnly = true)
        advanceUntilIdle()
        assertThat(vm.form.value).isNull()
        assertThat(store.catalogs.value.single().deviceOnly).isTrue()
    }
}
