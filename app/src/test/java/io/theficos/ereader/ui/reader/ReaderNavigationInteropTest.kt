package io.theficos.ereader.ui.reader

import android.app.Application
import android.webkit.ValueCallback
import io.theficos.ereader.reader.awaitReaderJavascript
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.core.model.HighlightColour
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.local.HighlightRepository
import io.theficos.ereader.data.local.ProgressRepository
import io.theficos.ereader.data.local.db.DocumentEntity
import io.theficos.ereader.data.local.db.EReaderDatabase
import io.theficos.ereader.data.local.db.MemoryBookmark
import io.theficos.ereader.reader.HighlightNavigator
import io.theficos.ereader.reader.ProgressTracker
import io.theficos.ereader.reader.ReaderPreferencesStore
import io.theficos.ereader.reader.ReadiumFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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
class ReaderNavigationInteropTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: EReaderDatabase
    private lateinit var vm: ReaderViewModel
    private lateinit var highlights: HighlightRepository
    private val store = ViewModelStore()
    private val dispatcher = UnconfinedTestDispatcher()
    private val explicit = mutableListOf<Locator>()
    private val restored = mutableListOf<Locator>()
    private fun point(href: String, fraction: Double) = Locator(Url(href)!!, MediaType.XHTML,
        locations = Locator.Locations(progression = fraction, totalProgression = fraction))
    private val origin = point("origin.xhtml", .2)
    private val target = point("target.xhtml", .6)
    private val memoryPoint = point("memory.xhtml", .8)
    private var dom: Locator? = origin.copy(text = Locator.Text(highlight = "Original paragraph"),
        locations = Locator.Locations(otherLocations = mapOf("cssSelector" to "#original")))
    private val rollbackGates = mutableListOf<CompletableDeferred<Unit>>()
    private var captureGate: CompletableDeferred<Unit>? = null

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(context, EReaderDatabase::class.java).allowMainThreadQueries().build()
        db.openHelper.writableDatabase
        runBlocking { db.documentDao().insert(DocumentEntity(id = 1, metadataId = null, contentHash = "book",
            title = "Book", author = null, downloadUrl = "local:", localPath = "unused", coverPath = null, downloadedAt = 0)) }
        highlights = HighlightRepository(db.highlightDao(), newId = { "saved-highlight" })
        val publication = Publication(Manifest(metadata = Metadata(), readingOrder = listOf(origin, target, memoryPoint)
            .map { Link(it.href, MediaType.XHTML) }), object : Container<Resource> {
            override val entries = emptySet<Url>()
            override fun get(url: Url): Resource? = null
            override fun close() {}
        })
        vm = ReaderViewModel(1, DocumentRepository(db.documentDao()), ProgressRepository(db.progressDao()),
            ReadiumFactory(context), ReaderPreferencesStore(context), highlights,
            readDomAnchor = { captureGate?.await(); dom }, openPublication = { publication },
            bookmarks = db.memoryBookmarkDao(),
            restoreNavigator = { _, locator -> restored += locator; true }, verifyAnchor = { _, _, _ -> true },
            navigateExplicit = { _, locator -> explicit += locator; true })
        ViewModelProvider(store, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(vm)!!
        })[ReaderViewModel::class.java]
    }

    @After fun cleanup() {
        val job = vm.viewModelScope.coroutineContext[Job]
        rollbackGates.forEach { it.complete(Unit) }
        captureGate?.complete(Unit)
        store.clear(); dispatcher.scheduler.advanceUntilIdle()
        runBlocking { job?.join() }
        db.close(); Dispatchers.resetMain()
    }

    private suspend fun ready() {
        vm.load(); vm.state.first { it is ReaderUiState.Open }; vm.memoryReady.first { it }
        vm.publishLocator(origin)
        db.memoryBookmarkDao().save(MemoryBookmark(1, "device-local", ProgressTracker.serialize(memoryPoint), "Question", "Passage", 1))
        vm.memory.first { it != null }
        val book = (vm.state.value as ReaderUiState.Open).document
        val start = JSONObject().put("path", JSONArray(listOf(0, 0))).put("nodeType", 3).put("offset", 0)
        val range = JSONObject().put("version", 3).put("href", target.href.toString()).put("documentHash", "a".repeat(64))
            .put("quote", "quote").put("rangeText", "quote").put("start", start)
            .put("end", JSONObject(start.toString()).put("offset", 5))
        highlights.create(book, target.copy(locations = target.locations.copy(otherLocations = mapOf("quireDomRange" to range.toString())))
            .toJSON().toString(), "quote", HighlightColour.YELLOW)
        vm.highlighting.items.first { it.isNotEmpty() }
    }

    private class Driver(override var currentLocator: Locator?) : HighlightNavigator {
        override val isAlive = true
        override var safeToPublish = true
        var action: suspend () -> Boolean = { awaitCancellation() }
        override suspend fun jump(locator: Locator, isCurrent: () -> Boolean): Boolean = action()
    }

    @Test fun `memory navigation cancellation with missing platform rollback callback reaches bounded recovery`() = runTest {
        ready()
        var rollbackCallback: ValueCallback<String>? = null
        val driver = Driver(target).apply {
            safeToPublish = false
            action = { try { awaitCancellation() } finally { withContext(NonCancellable) {
                safeToPublish = awaitReaderJavascript { rollbackCallback = it } == "true"
            } } }
        }
        vm.bindHighlightNavigator(driver); vm.jumpToHighlight("saved-highlight")
        vm.returnToMemory(); testScheduler.runCurrent()
        assertThat(rollbackCallback).isNotNull()
        assertThat(explicit).isEmpty()
        testScheduler.advanceTimeBy(1501); testScheduler.runCurrent()
        assertThat(vm.jumpingHighlight.value).isNull()
        assertThat(vm.highlightPositionUncertain.value).isTrue()
        assertThat(explicit).isEmpty()
        vm.publishLocator(target)
        assertThat(vm.currentLocator.value).isEqualTo(origin)
        rollbackCallback!!.onReceiveValue("true")
        testScheduler.runCurrent()
        assertThat(vm.highlightPositionUncertain.value).isTrue()
        assertThat(vm.currentLocator.value).isEqualTo(origin)
    }

    @Test fun `memory return waits for highlight rollback and clearing resize cannot release highlight guard`() = runTest {
        ready()
        val rollback = CompletableDeferred<Unit>().also { rollbackGates += it }
        val driver = Driver(origin).apply {
            safeToPublish = false
            action = { try { awaitCancellation() } finally { withContext(NonCancellable) {
                rollback.await(); currentLocator = origin; safeToPublish = true
            } } }
        }
        vm.bindHighlightNavigator(driver); vm.jumpToHighlight("saved-highlight")
        vm.returnToMemory(); testScheduler.runCurrent()
        vm.clearPendingResize(); vm.publishLocator(target)
        assertThat(explicit).isEmpty()
        assertThat(vm.currentLocator.value).isEqualTo(origin)
        rollback.complete(Unit); testScheduler.runCurrent()
        assertThat(explicit).containsExactly(memoryPoint)
        vm.publishLocator(memoryPoint)
        assertThat(vm.currentLocator.value).isEqualTo(memoryPoint)
        assertThat(vm.memory.value?.note).isEqualTo("Question")
        assertThat(vm.highlighting.items.value.single().id).isEqualTo("saved-highlight")
    }

    @Test fun `new highlight jump cancels pending font capture and next reflow keeps highlight target`() = runTest {
        ready()
        captureGate = CompletableDeferred()
        val previous = vm.preferences.value
        vm.updatePreferences(previous.copy(fontScale = .7))
        val driver = Driver(target).apply { action = { true } }
        vm.bindHighlightNavigator(driver); vm.jumpToHighlight("saved-highlight")
        testScheduler.runCurrent()
        captureGate!!.complete(Unit); captureGate = null
        assertThat(vm.preferences.value).isEqualTo(previous)
        assertThat(vm.currentLocator.value).isEqualTo(target)
        dom = target.copy(text = Locator.Text(highlight = "Highlight paragraph"),
            locations = Locator.Locations(otherLocations = mapOf("cssSelector" to "#target")))
        vm.updatePreferences(previous.copy(fontScale = .8)); vm.onPreferencesApplied()
        testScheduler.advanceTimeBy(601); testScheduler.runCurrent()
        assertThat(restored.last().href).isEqualTo(target.href)
        assertThat(restored.last().text.highlight).isEqualTo("Highlight paragraph")
        assertThat(vm.currentLocator.value?.href).isEqualTo(target.href)
    }

    @Test fun `resize completion waits for highlight rollback rather than racing staged page`() = runTest {
        ready()
        val rollback = CompletableDeferred<Unit>().also { rollbackGates += it }
        val driver = Driver(target).apply { safeToPublish = false
            action = { try { awaitCancellation() } finally { withContext(NonCancellable) {
                rollback.await(); currentLocator = origin; safeToPublish = true
            } } }
        }
        vm.bindHighlightNavigator(driver); vm.jumpToHighlight("saved-highlight")
        vm.beginViewportResize(); vm.completeViewportResize(); testScheduler.runCurrent()
        assertThat(restored).isEmpty()
        vm.publishLocator(target)
        assertThat(vm.currentLocator.value).isEqualTo(origin)
        rollback.complete(Unit); testScheduler.runCurrent()
        assertThat(restored.last().href).isEqualTo(origin.href)
        vm.publishLocator(memoryPoint)
        assertThat(vm.currentLocator.value).isEqualTo(memoryPoint)
    }

    @Test fun `unsafe highlight rollback blocks memory jump and progress despite resize clear`() = runTest {
        ready()
        val driver = Driver(target).apply { safeToPublish = false; action = { false } }
        vm.bindHighlightNavigator(driver); vm.jumpToHighlight("saved-highlight"); testScheduler.runCurrent()
        vm.returnToMemory(); vm.clearPendingResize(); vm.publishLocator(target)
        assertThat(explicit).isEmpty()
        assertThat(vm.highlightPositionUncertain.value).isTrue()
        assertThat(vm.currentLocator.value).isEqualTo(origin)
    }
}
