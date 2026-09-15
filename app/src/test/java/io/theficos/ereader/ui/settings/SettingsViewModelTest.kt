package io.theficos.ereader.ui.settings

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.auth.AccountCredentials
import io.theficos.ereader.auth.CalibreCredentialStore
import io.theficos.ereader.data.ai.AiClient
import io.theficos.ereader.data.ai.AiRepository
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.local.db.EReaderDatabase
import io.theficos.ereader.domain.restore.RestoreSummary
import io.theficos.ereader.reader.ReaderPreferencesStore
import io.theficos.ereader.ui.catalog.FakeAndroidKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class SettingsViewModelTest {
    private lateinit var context: Context
    private lateinit var db: EReaderDatabase
    private lateinit var docs: DocumentRepository
    private lateinit var booksDir: File
    private lateinit var store: CalibreCredentialStore
    private lateinit var aiRepository: AiRepository

    @Before fun setUp() {
        FakeAndroidKeyStore.setup()
        Dispatchers.setMain(StandardTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, EReaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        docs = DocumentRepository(db.documentDao())
        booksDir = File.createTempFile("books", "").apply { delete(); mkdirs() }
        store = CalibreCredentialStore(context)
        store.clear()
        // AiClient with a null baseUrl never makes a network call; the VM's
        // init refresh()/fetchHealth() both swallow the resulting error.
        aiRepository = AiRepository(
            client = AiClient(baseUrlProvider = { null }, http = OkHttpClient()),
            insightDao = db.insightDao(),
        )
    }

    @After fun tearDown() {
        runCatching { db.close() }
        runCatching { booksDir.deleteRecursively() }
        Dispatchers.resetMain()
    }

    private fun buildVm(restoreInProgress: (suspend ((Int, Int) -> Unit) -> RestoreSummary)?): SettingsViewModel =
        SettingsViewModel(
            store = store,
            readerStore = ReaderPreferencesStore(context),
            syncStateDao = db.syncStateDao(),
            documentRepo = docs,
            booksDir = booksDir,
            aiRepository = aiRepository,
            insightSyncRepository = null,
            insightDao = db.insightDao(),
            restoreInProgress = restoreInProgress,
        )

    /** A view model with no restore hook, for tests that don't exercise restore. */
    private fun newViewModel(): SettingsViewModel = buildVm(restoreInProgress = null)

    @Test fun `restoreInProgressBooks emits RestoreFinished with summary`() = runTest {
        store.saveBasicAccount(baseUrl = "https://books.example.com", username = "alice", password = "pw")
        val summary = RestoreSummary(
            requested = 2,
            downloaded = 2,
            skippedExisting = 0,
            skippedUnfetchable = 0,
            failed = 0,
        )
        val vm = buildVm(restoreInProgress = { _ -> summary })

        val events = mutableListOf<SettingsEvent>()
        val job = launch { vm.events.collect { events += it } }

        vm.restoreInProgressBooks()
        advanceUntilIdle()

        assertThat(events).contains(SettingsEvent.RestoreFinished(summary))
        assertThat(vm.restoreRunning.value).isFalse()
        assertThat(vm.isConnected.value).isTrue()
        job.cancel()
    }

    @Test fun `restoreInProgressBooks surfaces per-book progress and clears it on completion`() = runTest {
        store.saveBasicAccount(baseUrl = "https://books.example.com", username = "alice", password = "pw")
        val summary = RestoreSummary(
            requested = 2,
            downloaded = 2,
            skippedExisting = 0,
            skippedUnfetchable = 0,
            failed = 0,
        )
        val vm = buildVm(
            restoreInProgress = { onProgress ->
                onProgress(1, 2)
                onProgress(2, 2)
                summary
            },
        )

        val events = mutableListOf<SettingsEvent>()
        val job = launch { vm.events.collect { events += it } }

        vm.restoreInProgressBooks()
        advanceUntilIdle()

        assertThat(events).contains(SettingsEvent.RestoreFinished(summary))
        // finally clears progress after the run completes.
        assertThat(vm.restoreProgress.value).isNull()
        assertThat(vm.restoreRunning.value).isFalse()
        job.cancel()
    }

    @Test fun `restoreInProgressBooks emits RestoreFailed when the use case throws`() = runTest {
        store.saveBasicAccount(baseUrl = "https://books.example.com", username = "alice", password = "pw")
        val vm = buildVm(restoreInProgress = { _ -> throw IllegalStateException("boom") })

        val events = mutableListOf<SettingsEvent>()
        val job = launch { vm.events.collect { events += it } }

        vm.restoreInProgressBooks()
        advanceUntilIdle()

        assertThat(events).contains(SettingsEvent.RestoreFailed("boom"))
        assertThat(vm.restoreRunning.value).isFalse()
        job.cancel()
    }

    @Test fun `isConnected is false when no account configured`() = runTest {
        val vm = buildVm(restoreInProgress = { _ -> error("should not run") })
        advanceUntilIdle()
        assertThat(vm.isConnected.value).isFalse()
    }

    // ---------- issue #101 ----------

    @Test fun `an opds account is surfaced as the active account`() {
        store.saveOpdsAccount("https://kavita.example/api/opds/KEY")
        val vm = newViewModel()
        assertThat(vm.opds.value.isActive).isTrue()
        assertThat(vm.opds.value.catalogUrl).isEqualTo("https://kavita.example/api/opds/KEY")
    }

    @Test fun `an opds account reports no sync credentials`() {
        store.saveOpdsAccount("https://kavita.example/api/opds/KEY")
        val vm = newViewModel()
        assertThat(vm.sync.value.hasCredentials).isFalse()
    }

    @Test fun `a calibre account is not surfaced as an opds account`() {
        store.saveBasicAccount("https://calibre.example", "u", "p")
        val vm = newViewModel()
        assertThat(vm.opds.value.isActive).isFalse()
        assertThat(vm.calibre.value.baseUrl).isEqualTo("https://calibre.example")
    }

    @Test fun `saving an opds account replaces the stored record`() = runTest {
        val vm = newViewModel()
        vm.onOpdsCatalogUrlChange("https://feed.example/opds")
        vm.saveOpds()
        advanceUntilIdle()

        val saved = store.getAccount() as AccountCredentials.OpdsOnly
        assertThat(saved.baseUrl).isEqualTo("https://feed.example/opds")
    }

    @Test fun `saving a scheme-less catalog url leaves the stored account untouched`() = runTest {
        store.saveBasicAccount("https://calibre.example", "alice", "pw")
        val vm = newViewModel()
        vm.onOpdsCatalogUrlChange("feed.example/opds")
        vm.saveOpds()
        advanceUntilIdle()

        // The calibre password survives: saveOpds bailed before persistAccount,
        // which would otherwise have dropped it in the same transaction.
        val saved = store.getAccount() as AccountCredentials.Basic
        assertThat(saved.username).isEqualTo("alice")
        assertThat(vm.opds.value.error).isNotNull()
        assertThat(vm.opds.value.saved).isFalse()
    }

    @Test fun `editing the catalog url clears a previous validation error`() = runTest {
        val vm = newViewModel()
        vm.onOpdsCatalogUrlChange("not a url")
        vm.saveOpds()
        advanceUntilIdle()
        assertThat(vm.opds.value.error).isNotNull()

        vm.onOpdsCatalogUrlChange("https://feed.example/opds")
        assertThat(vm.opds.value.error).isNull()
    }

    @Test fun `saving an opds account canonicalises the url before persisting it`() = runTest {
        val vm = newViewModel()
        vm.onOpdsCatalogUrlChange("  https://feed.example/opds  ")
        vm.saveOpds()
        advanceUntilIdle()

        val saved = store.getAccount() as AccountCredentials.OpdsOnly
        assertThat(saved.baseUrl).isEqualTo("https://feed.example/opds")
        assertThat(vm.opds.value.catalogUrl).isEqualTo("https://feed.example/opds")
    }

    @Test fun `isConnected is false for an opds-only account`() = runTest {
        store.saveOpdsAccount("https://feed.example/opds")
        val vm = newViewModel()
        advanceUntilIdle()
        // Restore reads the quire-server library mirror, which an OPDS-only
        // account has no way to reach.
        assertThat(vm.isConnected.value).isFalse()
    }

    @Test fun `isConnected is true for a server-backed account`() = runTest {
        store.saveBasicAccount("https://books.example.com", "alice", "pw")
        val vm = newViewModel()
        advanceUntilIdle()
        assertThat(vm.isConnected.value).isTrue()
    }
}
