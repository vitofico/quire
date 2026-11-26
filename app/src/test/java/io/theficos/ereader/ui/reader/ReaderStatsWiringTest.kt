package io.theficos.ereader.ui.reader

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.local.HighlightRepository
import io.theficos.ereader.data.local.ProgressRepository
import io.theficos.ereader.data.local.db.DocumentEntity
import io.theficos.ereader.data.local.db.EReaderDatabase
import io.theficos.ereader.reader.ReaderPreferencesStore
import io.theficos.ereader.reader.ReadiumFactory
import io.theficos.ereader.ui.stats.ReadingStatsSink
import io.theficos.ereader.ui.stats.StatsBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
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
import org.readium.r2.shared.publication.services.PositionsService
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

/** How the reader feeds the session recorder: lifecycle, gestures, positions and close. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ReaderStatsWiringTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: EReaderDatabase
    private val store = ViewModelStore()
    private lateinit var vm: ReaderViewModel
    private var clock = 1_000_000L
    private val recorded: MutableList<RecordedSession> = Collections.synchronizedList(mutableListOf())
    private val counted: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val sink = object : ReadingStatsSink {
        override fun record(book: StatsBook, session: RecordedSession) { recorded += session }
        override suspend fun ensureWordCount(bookKey: String, count: suspend () -> Long?) {
            counted += bookKey
        }
    }

    private val here = Locator(Url("one.xhtml")!!, MediaType.XHTML)

    /** A thousand positions: one page turn can move at most a hundredth of the book. */
    private val publication = Publication(
        manifest = Manifest(metadata = Metadata(), readingOrder = listOf(Link(Url("one.xhtml")!!, MediaType.XHTML))),
        container = object : Container<Resource> {
            override val entries = emptySet<Url>()
            override fun get(url: Url): Resource? = null
            override fun close() {}
        },
        servicesBuilder = Publication.ServicesBuilder(positions = {
            object : PositionsService {
                override suspend fun positionsByReadingOrder() = listOf(List(1_000) { here })
            }
        }),
    )

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(context, EReaderDatabase::class.java).allowMainThreadQueries().build()
        runBlocking {
            db.documentDao().insert(DocumentEntity(id = 1, metadataId = null, contentHash = "hash-one",
                title = "Book", author = null, downloadUrl = "", localPath = "", coverPath = null, downloadedAt = 0))
        }
        vm = ReaderViewModel(1,
            DocumentRepository(db.documentDao()), ProgressRepository(db.progressDao()),
            ReadiumFactory(context), ReaderPreferencesStore(context), HighlightRepository(db.highlightDao()),
            nowMs = { 1_700_000_000_000L }, openPublication = { publication },
            stats = sink, elapsedMs = { clock },
        )
        ViewModelProvider(store, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(vm)!!
        })[ReaderViewModel::class.java]
    }

    @After fun cleanup() {
        // Clear the view model first, so none of its collectors outlives the test dispatcher.
        val job = vm.viewModelScope.coroutineContext[Job]
        store.clear()
        runBlocking { job?.join() }
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun open() {
        vm.load()
        vm.state.first { it is ReaderUiState.Open }
        vm.positions.first { it != null }
    }

    private fun at(progression: Double) = vm.publishLocator(
        here.copy(locations = Locator.Locations(totalProgression = progression)),
    )

    /** A page turn: the gesture, then the position it lands on. */
    private fun turn(to: Double) {
        clock += 20_000
        vm.recognizeReadingGesture()
        clock += 300
        at(to)
    }

    @Test fun `a session runs from start to stop, also when the reader starts before the book opens`() = runTest {
        vm.onReaderStarted()
        open()
        at(0.1)
        turn(0.105)
        turn(0.11)
        vm.onReaderStopped()

        assertThat(recorded.single().activeSeconds).isEqualTo(40)
        assertThat(recorded.single().progressionRead).isWithin(1e-9).of(0.01)
        assertThat(counted).containsExactly("hash-one")
    }

    @Test fun `nothing is recorded while the reader is stopped`() = runTest {
        open()
        at(0.1)
        turn(0.105)
        turn(0.11)
        vm.onReaderStopped()

        assertThat(recorded).isEmpty()
    }

    @Test fun `the session ends once across leaving, stopping and clearing`() = runTest {
        open()
        vm.onReaderStarted()
        at(0.1)
        turn(0.105)
        turn(0.11)
        vm.onReaderStopped() // the reader leaves the composition
        vm.onReaderStopped() // the lifecycle stops
        store.clear() // the view model goes

        assertThat(recorded).hasSize(1)
    }

    @Test fun `the step limit follows the book's positions once they load`() = runTest {
        open()
        vm.onReaderStarted()
        at(0.1)
        turn(0.115) // within the default fiftieth, beyond a hundredth: a link, not a page
        turn(0.12)
        vm.onReaderStopped()

        assertThat(recorded.single().progressionRead).isWithin(1e-9).of(0.005)
    }
}
