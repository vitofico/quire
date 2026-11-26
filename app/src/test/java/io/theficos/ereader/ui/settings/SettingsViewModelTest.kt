package io.theficos.ereader.ui.settings

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.auth.AccountCredentials
import io.theficos.ereader.core.model.DocumentIdentity
import io.theficos.ereader.auth.CalibreCredentialStore
import io.theficos.ereader.auth.SavedCatalog
import io.theficos.ereader.data.ai.AiClient
import io.theficos.ereader.data.ai.AiRepository
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.local.db.EReaderDatabase
import io.theficos.ereader.domain.restore.RestoreSummary
import io.theficos.ereader.reader.ReaderPreferencesStore
import io.theficos.ereader.ui.catalog.FakeAndroidKeyStore
import io.theficos.ereader.ui.theme.AppTheme
import io.theficos.ereader.ui.theme.AppThemePreferencesStore
import io.theficos.ereader.ui.theme.Palette
import io.theficos.ereader.ui.theme.ThemeMode
import io.theficos.ereader.ui.library.LibraryPreferencesStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
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
    private val viewModels = mutableListOf<SettingsViewModel>()

    @Before fun setUp() {
        FakeAndroidKeyStore.setup()
        Dispatchers.setMain(StandardTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, EReaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // Open now, on the test thread. Left to a Room worker, the open can deadlock
        // with tearDown's close() when the test ends first.
        db.openHelper.writableDatabase
        docs = DocumentRepository(db.documentDao())
        booksDir = File.createTempFile("books", "").apply { delete(); mkdirs() }
        store = CalibreCredentialStore(context)
        store.clear()
        // AiClient with a null baseUrl never makes a network call; the VM's
        // init refresh()/fetchHealth() both swallow the resulting error.
        aiRepository = AiRepository(
            client = AiClient(baseUrlProvider = { null }, http = OkHttpClient(), language = { "en" }),
            insightDao = db.insightDao(),
            appLanguage = { "en" },
        )
    }

    @After fun tearDown() {
        try {
            runTest {
                viewModels.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            }
        } finally {
            try {
                if (::db.isInitialized) db.close()
            } finally {
                try {
                    if (::booksDir.isInitialized) booksDir.deleteRecursively()
                } finally {
                    Dispatchers.resetMain()
                }
            }
        }
    }

    private fun buildVm(restoreInProgress: (suspend ((Int, Int) -> Unit) -> RestoreSummary)?): SettingsViewModel =
        SettingsViewModel(
            store = store,
            readerStore = ReaderPreferencesStore(context),
            appThemeStore = AppThemePreferencesStore(context),
            syncStateDao = db.syncStateDao(),
            documentRepo = docs,
            booksDir = booksDir,
            aiRepository = aiRepository,
            insightSyncRepository = null,
            insightDao = db.insightDao(),
            restoreInProgress = restoreInProgress,
            libraryPreferencesStore = LibraryPreferencesStore(context),
        ).also { viewModels += it }

    /** A view model with no restore hook, for tests that don't exercise restore. */
    private fun newViewModel(): SettingsViewModel = buildVm(restoreInProgress = null)

    @Test fun `remove all books clears the library and its files without touching siblings`() = runTest {
        val bystander = File.createTempFile("outside-library", ".txt", booksDir.parentFile)
        try {
            bystander.writeText("keep")
            val ids = (1..2).map { number ->
                val epub = File(booksDir, "$number.epub").apply { writeText("book") }
                docs.insert(
                    DocumentIdentity(contentHash = "hash-$number"), "Book $number", null,
                    "url-$number", epub.path, null, 0L,
                )
            }
            val cache = File(booksDir, "covers").apply { mkdir() }
            File(cache, "cover.png").writeText("cover")

            val vm = newViewModel()
            val scope = checkNotNull(vm.viewModelScope.coroutineContext[Job])
            val existing = scope.children.toSet()
            vm.removeAllBooks()
            scope.children.filterNot { it in existing }.toList().forEach { it.join() }

            ids.forEach { assertThat(docs.findById(it)).isNull() }
            assertThat(booksDir.listFiles()).isEmpty()
            assertThat(bystander.readText()).isEqualTo("keep")
        } finally {
            bystander.delete()
        }
    }

    @Test fun `app theme changes persist without changing reader preferences`() = runTest {
        val readerStore = ReaderPreferencesStore(context)
        val before = readerStore.flow.value
        val vm = newViewModel()

        val midnight = AppTheme(mode = ThemeMode.DARK, dark = Palette.MIDNIGHT)
        vm.setAppTheme(midnight)

        assertThat(vm.appTheme.value).isEqualTo(midnight)
        assertThat(AppThemePreferencesStore(context).flow.value).isEqualTo(midnight)
        assertThat(ReaderPreferencesStore(context).flow.value).isEqualTo(before)
    }

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
        backgroundScope.launch { vm.events.collect { events += it } }

        vm.restoreInProgressBooks()
        advanceUntilIdle()

        assertThat(events).contains(SettingsEvent.RestoreFinished(summary))
        assertThat(vm.restoreRunning.value).isFalse()
        assertThat(vm.isConnected.value).isTrue()
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
        backgroundScope.launch { vm.events.collect { events += it } }

        vm.restoreInProgressBooks()
        advanceUntilIdle()

        assertThat(events).contains(SettingsEvent.RestoreFinished(summary))
        // finally clears progress after the run completes.
        assertThat(vm.restoreProgress.value).isNull()
        assertThat(vm.restoreRunning.value).isFalse()
    }

    @Test fun `restoreInProgressBooks emits RestoreFailed when the use case throws`() = runTest {
        store.saveBasicAccount(baseUrl = "https://books.example.com", username = "alice", password = "pw")
        val vm = buildVm(restoreInProgress = { _ -> throw IllegalStateException("boom") })

        val events = mutableListOf<SettingsEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }

        vm.restoreInProgressBooks()
        advanceUntilIdle()

        assertThat(events).contains(SettingsEvent.RestoreFailed("boom"))
        assertThat(vm.restoreRunning.value).isFalse()
    }

    @Test fun `isConnected is false when no account configured`() = runTest {
        val vm = buildVm(restoreInProgress = { _ -> error("should not run") })
        advanceUntilIdle()
        assertThat(vm.isConnected.value).isFalse()
    }

    // ---------- catalogs ----------

    @Test fun `a device with only catalogs reports no sync credentials and cannot restore`() = runTest {
        store.saveCatalog(catalogEntry("https://kavita.example/api/opds/KEY"))
        val vm = newViewModel()
        advanceUntilIdle()
        assertThat(vm.sync.value.hasCredentials).isFalse()
        // Restore reads the quire-server library mirror, which a catalog alone cannot reach.
        assertThat(vm.isConnected.value).isFalse()
    }

    @Test fun `isConnected is true for a server-backed account`() = runTest {
        store.saveBasicAccount("https://books.example.com", "alice", "pw")
        val vm = newViewModel()
        advanceUntilIdle()
        assertThat(vm.isConnected.value).isTrue()
    }

    @Test fun `the legacy sign-in form edits the legacy sign-in only`() = runTest {
        store.saveBasicAccount("https://calibre.example", "alice", "pw")
        val vm = newViewModel()
        assertThat(vm.calibre.value.baseUrl).isEqualTo("https://calibre.example")
        vm.onPasswordChange("new-password")
        vm.onQuireServerUrlChange("not a url")
        vm.saveCalibre()
        assertThat(vm.calibre.value.error).isNotNull()
        assertThat((store.getAccount() as AccountCredentials.Basic).password).isEqualTo("pw")

        vm.onQuireServerUrlChange("")
        vm.saveCalibre()
        advanceUntilIdle()
        assertThat((store.getAccount() as AccountCredentials.Basic).password).isEqualTo("new-password")
        assertThat(store.catalogAccount()?.password).isEqualTo("new-password")
    }

    @Test fun `the legacy form cannot turn a Quire account into a Calibre-Web sign-in`() = runTest {
        store.saveBearerAccount("https://quire.example", "alice@example.com", "quire-token")
        val vm = newViewModel()
        assertThat(vm.calibre.value.saved).isFalse()
        vm.onBaseUrlChange("https://books.example/calibre/")
        vm.onUsernameChange("reader")
        vm.onPasswordChange("catalog-password")
        vm.saveCalibre()
        advanceUntilIdle()

        assertThat(store.getAccount()).isInstanceOf(AccountCredentials.Bearer::class.java)
        assertThat(store.catalogs.value).isEmpty()
    }

    @Test fun `signing out keeps the catalogs or removes them`() = runTest {
        store.saveBearerAccount("https://quire.example", "alice@example.com", "quire-token")
        val kept = catalogEntry("https://feed.example/opds")
        store.saveCatalog(kept)
        val vm = newViewModel()
        advanceUntilIdle()
        assertThat(vm.isConnected.value).isTrue()

        vm.signOut(keepCatalogs = true)
        advanceUntilIdle()
        assertThat(store.getAccount()).isNull()
        assertThat(store.catalogs.value.map { it.id }).containsExactly(kept.id)
        assertThat(vm.isConnected.value).isFalse()

        store.saveBearerAccount("https://quire.example", "alice@example.com", "quire-token")
        vm.signOut(keepCatalogs = false)
        advanceUntilIdle()
        assertThat(store.getAccount()).isNull()
        assertThat(store.catalogs.value).isEmpty()
    }

    private fun catalogEntry(url: String) =
        SavedCatalog(SavedCatalog.newId(), SavedCatalog.defaultName(url), AccountCredentials.OpdsOnly(url))
}
