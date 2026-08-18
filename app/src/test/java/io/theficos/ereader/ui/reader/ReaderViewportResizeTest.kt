package io.theficos.ereader.ui.reader

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.local.ProgressRepository
import io.theficos.ereader.data.local.db.EReaderDatabase
import io.theficos.ereader.reader.ReaderPreferencesStore
import io.theficos.ereader.reader.ReadiumFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The reader keeps its place across a viewport resize by snapshotting the current locator,
 * ignoring everything Readium emits while the WebView re-paginates, and then going back to the
 * snapshot. These tests cover which resizes arm that.
 *
 * Issue #95 was a resize nobody had armed, and what it cost is the shape of every test here: a
 * post-re-pagination locator reached the HUD and the progress row, so the reader came back to
 * the wrong page and its saved place was gone. That particular resize is now prevented at
 * source (ReaderScreen turns off Readium's own inset padding); arming on the measured viewport
 * is the backstop for the next one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
// The stock Application: EReaderApp builds the whole DI container on create, keystore and
// all, which this test has no use for.
@Config(sdk = [33], application = android.app.Application::class)
class ReaderViewportResizeTest {

    private lateinit var db: EReaderDatabase
    private lateinit var vm: ReaderViewModel

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(context, EReaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        vm = ReaderViewModel(
            documentId = 1L,
            docs = DocumentRepository(db.documentDao()),
            progress = ProgressRepository(db.progressDao()),
            readium = ReadiumFactory(context),
            preferencesStore = ReaderPreferencesStore(context),
        )
    }

    @After fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun locatorAt(progression: Double): Locator = Locator(
        href = Url("ch1.xhtml")!!,
        mediaType = MediaType.XHTML,
        locations = Locator.Locations(progression = progression, totalProgression = progression),
    )

    @Test fun `first viewport report only establishes the baseline`() = runTest {
        vm.publishLocator(locatorAt(0.5))
        vm.onViewportChanged(1080, 2424)

        // Nothing to re-anchor: the WebView was created at this size, so Readium is still free
        // to report where it actually is.
        vm.publishLocator(locatorAt(0.6))
        assertThat(vm.currentLocator.value?.locations?.progression).isEqualTo(0.6)
    }

    @Test fun `an unchanged viewport does not arm a re-anchor`() = runTest {
        vm.publishLocator(locatorAt(0.5))
        vm.onViewportChanged(1080, 2424)
        vm.onViewportChanged(1080, 2424)

        vm.publishLocator(locatorAt(0.6))
        assertThat(vm.currentLocator.value?.locations?.progression).isEqualTo(0.6)
    }

    @Test fun `a shorter viewport suppresses the drifted locator and restores the anchor`() = runTest {
        vm.publishLocator(locatorAt(0.5))
        vm.onViewportChanged(1080, 2424)

        // Something outside the reader's control shortens the viewport.
        vm.onViewportChanged(1080, 2219)

        // Readium re-paginates and reports wherever the text landed. That must not reach the
        // HUD or, through the locator flow, the saved progress row.
        vm.publishLocator(locatorAt(0.42))
        assertThat(vm.currentLocator.value?.locations?.progression).isEqualTo(0.5)

        vm.completeViewportResize()
        assertThat(vm.currentLocator.value?.locations?.progression).isEqualTo(0.5)

        // Publishing is live again once the anchor has been honoured.
        vm.publishLocator(locatorAt(0.55))
        assertThat(vm.currentLocator.value?.locations?.progression).isEqualTo(0.55)
    }

    @Test fun `a resize arriving in steps keeps the original anchor`() = runTest {
        vm.publishLocator(locatorAt(0.5))
        vm.onViewportChanged(1080, 2424)

        // An inset animation resizes the viewport over several frames, with Readium emitting
        // drifted locators in between. The anchor must stay the pre-resize one throughout.
        vm.onViewportChanged(1080, 2380)
        vm.publishLocator(locatorAt(0.41))
        vm.onViewportChanged(1080, 2300)
        vm.publishLocator(locatorAt(0.40))
        vm.onViewportChanged(1080, 2219)

        vm.completeViewportResize()
        assertThat(vm.currentLocator.value?.locations?.progression).isEqualTo(0.5)
    }

    @Test fun `a zero-sized viewport is not a resize`() = runTest {
        vm.publishLocator(locatorAt(0.5))
        vm.onViewportChanged(1080, 2424)
        // A detached or not-yet-measured view reports zero; treating that as a resize would arm
        // the re-anchor against a size the reader never actually had.
        vm.onViewportChanged(0, 0)
        vm.onViewportChanged(1080, 2424)

        vm.publishLocator(locatorAt(0.6))
        assertThat(vm.currentLocator.value?.locations?.progression).isEqualTo(0.6)
    }

    @Test fun `seeking supersedes an armed re-anchor`() = runTest {
        vm.publishLocator(locatorAt(0.5))
        vm.onViewportChanged(1080, 2424)
        vm.onViewportChanged(1080, 2219)

        // A seek can land while a re-anchor is armed. The reader must stay where the seek put
        // it rather than being yanked back to the anchor, which predates the seek.
        vm.goTo(locatorAt(0.9))
        vm.publishLocator(locatorAt(0.9))
        vm.completeViewportResize()

        assertThat(vm.currentLocator.value?.locations?.progression).isEqualTo(0.9)
    }
}
