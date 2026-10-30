package io.theficos.ereader.ui.reader

import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.core.model.Document
import io.theficos.ereader.core.model.DocumentIdentity
import io.theficos.ereader.core.model.HighlightColour
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.local.HighlightRepository
import io.theficos.ereader.data.local.ProgressRepository
import io.theficos.ereader.data.local.db.EReaderDatabase
import io.theficos.ereader.data.local.db.HighlightDao
import io.theficos.ereader.data.local.db.HighlightEntity
import io.theficos.ereader.reader.HighlightNavigator
import io.theficos.ereader.reader.ReaderPreferencesStore
import io.theficos.ereader.reader.ReadiumFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class ReaderHighlightNavigationTest {
    private lateinit var db: EReaderDatabase
    private lateinit var store: ViewModelStore
    private lateinit var vm: ReaderViewModel
    private lateinit var repository: HighlightRepository
    private val origin = Locator(Url("origin.xhtml")!!, MediaType.XHTML, locations = Locator.Locations(progression = .4))
    private val drift = origin.copy(locations = Locator.Locations(progression = .1))
    private val book = Document(1, DocumentIdentity(contentHash = "book-hash"), "Book", null, "local:", "unused", null, 0)

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(context, EReaderDatabase::class.java).allowMainThreadQueries().build()
        db.openHelper.writableDatabase
        repository = HighlightRepository(Rows(), newId = { "saved" })
        vm = ReaderViewModel(1, DocumentRepository(db.documentDao()), ProgressRepository(db.progressDao()),
            ReadiumFactory(context), ReaderPreferencesStore(context), repository, readDomAnchor = { null })
        store = ViewModelStore().apply { put("reader", vm) }
        vm.highlighting.attach(book)
        vm.onViewportChanged(1080, 2400)
        vm.publishLocator(origin)
    }

    @After fun cleanup() { store.clear(); db.close(); Dispatchers.resetMain() }

    private suspend fun save() {
        val point = JSONObject().put("path", JSONArray(listOf(0, 0))).put("nodeType", 3).put("offset", 0)
        val anchor = JSONObject().put("version", 3).put("href", "target.xhtml").put("documentHash", "a".repeat(64))
            .put("quote", "quote").put("rangeText", "quote").put("start", point)
            .put("end", JSONObject(point.toString()).put("offset", 5))
        val locator = Locator(Url("target.xhtml")!!, MediaType.XHTML, locations = Locator.Locations(otherLocations = mapOf("quireDomRange" to anchor.toString())))
        repository.create(book, locator.toJSON().toString(), "quote", HighlightColour.YELLOW)
    }

    private class Navigation(override var currentLocator: Locator?) : HighlightNavigator {
        override var isAlive = true
        override var safeToPublish = true
        var calls = 0
        var cancelled = false
        var action: suspend () -> Boolean = { awaitCancellation() }
        override suspend fun jump(locator: Locator, isCurrent: () -> Boolean): Boolean {
            calls++
            return try { action() } finally { cancelled = true }
        }
    }

    @Test fun `pre-configuration cancellation retains the armed original position before any size callback`() = runTest {
        save(); runCurrent()
        val nav = Navigation(drift)
        vm.bindHighlightNavigator(nav)
        vm.openHighlights()
        vm.jumpToHighlight("saved")
        runCurrent()
        vm.beginViewportResize()
        runCurrent()
        assertThat(nav.cancelled).isTrue()
        assertThat(vm.jumpingHighlight.value).isNull()
        // Native post-resize drift must remain suppressed until the existing resize settles.
        vm.publishLocator(drift)
        assertThat(vm.currentLocator.value).isEqualTo(origin)
        vm.completeViewportResize()
        runCurrent()
        assertThat(vm.currentLocator.value).isEqualTo(origin)
    }

    @Test fun `resize cancellation never emits a transient native page as committed progress`() = runTest {
        save(); runCurrent()
        val published = mutableListOf<Locator>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.locatorUpdates.collect { published += it } }
        val nav = Navigation(drift)
        vm.bindHighlightNavigator(nav)
        vm.openHighlights()
        vm.jumpToHighlight("saved")
        runCurrent()
        vm.publishLocator(drift)
        vm.onViewportChanged(2400, 1080)
        runCurrent()
        vm.publishLocator(drift)
        assertThat(vm.currentLocator.value).isEqualTo(origin)
        assertThat(published).doesNotContain(drift)
        vm.completeViewportResize()
        runCurrent()
        assertThat(published).doesNotContain(drift)
        assertThat(vm.currentLocator.value).isEqualTo(origin)
    }

    @Test fun `failed rollback keeps progress quarantined and blocks row retry until reopen`() = runTest {
        save(); runCurrent()
        val nav = Navigation(drift).apply { safeToPublish = false; action = { false } }
        vm.bindHighlightNavigator(nav)
        vm.openHighlights()
        vm.jumpToHighlight("saved")
        runCurrent()
        assertThat(vm.highlightPositionUncertain.value).isTrue()
        vm.publishLocator(drift)
        vm.jumpToHighlight("saved")
        vm.closeHighlights()
        runCurrent()
        assertThat(nav.calls).isEqualTo(1)
        assertThat(vm.highlightsVisible.value).isTrue()
        assertThat(vm.currentLocator.value).isEqualTo(origin)
    }

    private class Rows : HighlightDao {
        private val rows = MutableStateFlow<List<HighlightEntity>>(emptyList())
        override suspend fun insert(highlight: HighlightEntity) { rows.value += highlight }
        override suspend fun findById(id: String) = rows.value.firstOrNull { it.id == id }
        override fun observeActive(metadataId: String?, contentHash: String?): Flow<List<HighlightEntity>> = rows.map { all ->
            all.filter { it.deletedAt == null && it.contentHash == contentHash }
        }
        override suspend fun setColour(id: String, colour: String, now: Long) {}
        override suspend fun softDelete(id: String, now: Long) {}
    }
}
