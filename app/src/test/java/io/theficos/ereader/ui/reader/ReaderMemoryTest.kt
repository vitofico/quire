package io.theficos.ereader.ui.reader

import android.app.Application
import androidx.room.Room
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.local.ProgressRepository
import io.theficos.ereader.data.local.db.DocumentEntity
import io.theficos.ereader.data.local.db.EReaderDatabase
import io.theficos.ereader.data.local.db.MemoryBookmark
import io.theficos.ereader.reader.ProgressTracker
import io.theficos.ereader.reader.ReaderPreferencesStore
import io.theficos.ereader.reader.ReadiumFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.publication.Metadata
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ReaderMemoryTest {
    private lateinit var db: EReaderDatabase
    private lateinit var dispatcher: TestDispatcher
    private val stores = mutableListOf<ViewModelStore>()
    private val models = mutableListOf<ReaderViewModel>()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val owner = MutableStateFlow("account-a")
    private val live = Locator(Url("one.xhtml")!!, MediaType.XHTML,
        locations = Locator.Locations(position = 3, progression = 0.4, totalProgression = 0.2))
    private val dom = live.copy(text = Locator.Text(highlight = "The letter remained unopened."),
        locations = Locator.Locations(otherLocations = mapOf("cssSelector" to "#p3")))
    private var opens = 0
    private lateinit var vm: ReaderViewModel
    private val publication = Publication(
        manifest = Manifest(metadata = Metadata(), readingOrder = listOf(Link(Url("one.xhtml")!!, MediaType.XHTML))),
        container = object : Container<Resource> {
            override val entries = emptySet<Url>()
            override fun get(url: Url): Resource? = null
            override fun close() {}
        },
    )

    @Before fun setup() {
        dispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(dispatcher)
        owner.value = "account-a"
        db = Room.inMemoryDatabaseBuilder(context, EReaderDatabase::class.java).allowMainThreadQueries().build()
        db.openHelper.writableDatabase
        kotlinx.coroutines.runBlocking { db.documentDao().insert(DocumentEntity(id = 1, metadataId = null,
            contentHash = "one", title = "Book", author = null, downloadUrl = "", localPath = "", coverPath = null, downloadedAt = 0)) }
        vm = model()
    }
    private fun model(
        read: suspend () -> Locator? = { dom },
        restore: suspend (Locator) -> Boolean = { true },
    ) = ReaderViewModel(1,
        DocumentRepository(db.documentDao()), ProgressRepository(db.progressDao()),
        ReadiumFactory(context), ReaderPreferencesStore(context),
        io.theficos.ereader.data.local.HighlightRepository(db.highlightDao()), readDomAnchor = { read() },
        nowMs = { 1234 }, bookmarks = db.memoryBookmarkDao(), accountKey = { owner.value },
        restoreNavigator = { _, locator -> restore(locator) },
            verifyAnchor = { _, _, _ -> true },
        accountChanges = owner, openPublication = { opens++; publication }).also { model ->
            models += model
            val store = ViewModelStore()
            stores += store
            ViewModelProvider(store, object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(model)!!
            })[ReaderViewModel::class.java]
        }
    @After fun cleanup() {
        // Clear every model, including the initial model replaced by the suspended-capture test.
        // onCleared flushes pending progress while the database is still open.
        val jobs = models.mapNotNull { it.viewModelScope.coroutineContext[Job] }
        stores.forEach { it.clear() }
        dispatcher.scheduler.advanceUntilIdle()
        runBlocking { jobs.forEach { it.join() } }
        db.close()
        Dispatchers.resetMain()
    }
    private suspend fun load() {
        vm.load()
        vm.state.first { it is ReaderUiState.Open }
        vm.memoryReady.first { it }
        vm.publishLocator(live)
    }
    private suspend fun stored() = db.memoryBookmarkDao().observe(1, "account-a").first()

    @Test fun `retained reader reload does not reopen book or reset current DOM location`() = runTest {
        load()
        val before = vm.currentLocator.value
        vm.load()
        assertThat(opens).isEqualTo(1)
        assertThat(vm.currentLocator.value).isEqualTo(before)
        assertThat(vm.recreationLocator()?.text?.highlight).isEqualTo(dom.text.highlight)
    }

    @Test fun `save captures nearby passage and original anchor while note edits keep that point`() = runTest {
        load()
        vm.saveMemory("Why avoid the letter?")
        val first = db.memoryBookmarkDao().observe(1, "account-a").first { it != null }!!
        assertThat(first.passage).contains("letter remained unopened")
        assertThat(first.updatedAt).isEqualTo(1234)
        assertThat(ProgressTracker.parseOrNull(first.locator)?.text?.highlight).isEqualTo(dom.text.highlight)
        vm.memory.first { it != null }
        vm.publishLocator(live.copy(locations = live.locations.copy(position = 4, progression = 0.6)))
        vm.saveMemory("Edited question")
        val edited = db.memoryBookmarkDao().observe(1, "account-a").first { it?.note == "Edited question" }!!
        assertThat(edited.locator).isEqualTo(first.locator)
    }

    @Test fun `malformed saved location stays recoverable and normal save does not overwrite`() = runTest {
        val bad = MemoryBookmark(1, "account-a", "broken", "Keep my question", "passage", 10)
        db.memoryBookmarkDao().save(bad)
        load(); vm.memory.first { it != null }
        assertThat(vm.memoryLocator()).isNull()
        vm.saveMemory("attempt")
        vm.memoryError.first { it }
        assertThat(stored()).isEqualTo(bad)
        vm.saveMemory("recovered", atCurrentPoint = true)
        assertThat(db.memoryBookmarkDao().observe(1, "account-a").first { it?.note == "recovered" }?.locator).isNotEqualTo("broken")
    }

    @Test fun `account switch clears visible note and forbids writing into old namespace`() = runTest {
        db.memoryBookmarkDao().save(MemoryBookmark(1, "account-a", ProgressTracker.serialize(live), "Private", "", 1))
        load(); vm.memory.first { it != null }
        owner.value = "account-b"
        assertThat(vm.memory.value).isNull()
        vm.saveMemory("Wrong account")
        vm.removeMemory()
        assertThat(stored()?.note).isEqualTo("Private")
        assertThat(db.memoryBookmarkDao().observe(1, "account-b").first()).isNull()
    }

    @Test fun `account change cancels pending viewport restoration retries`() = runTest {
        var attempts = 0
        vm = model(restore = { attempts++; false })
        load()
        vm.beginViewportResize()
        vm.completeViewportResize()
        owner.value = "account-b"
        testScheduler.advanceTimeBy(5000)
        testScheduler.runCurrent()
        assertThat(attempts).isEqualTo(1)
        assertThat(vm.anchorRecovery.value).isFalse()
    }

    @Test fun `rotation during capture fails without storing a drifting location`() = runTest {
        val pending = CompletableDeferred<Locator?>()
        vm = model(read = { pending.await() })
        load()
        vm.saveMemory("Question")
        vm.beginViewportResize()
        pending.complete(dom)
        vm.memoryError.first { it }
        assertThat(stored()).isNull()
    }

    @Test fun `missing live locator and deleted document never create bookmark`() = runTest {
        vm.load(); vm.state.first { it is ReaderUiState.Open }; vm.memoryReady.first { it }
        vm.saveMemory("Question")
        vm.memoryError.first { it }
        assertThat(stored()).isNull()
        vm.publishLocator(live)
        // Progress is independently debounced. Complete that write before removing its FK parent.
        advanceUntilIdle()
        db.progressDao().observeByDocument(1).first { it != null }
        db.documentDao().deleteById(1)
        vm.saveMemory("Question")
        assertThat(stored()).isNull()
    }
}
