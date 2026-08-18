package io.theficos.ereader.ui.reader

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.local.ProgressRepository
import io.theficos.ereader.data.local.db.EReaderDatabase
import io.theficos.ereader.reader.ReaderPreferencesStore
import io.theficos.ereader.reader.ReadiumFactory
import kotlinx.coroutines.CompletableDeferred
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
import org.readium.r2.navigator.epub.EpubNavigatorFragment
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

    /**
     * A [ReaderViewModel] whose DOM anchors come from [anchors] in order, the last one repeating.
     * Standing in for a JavaScript round trip into Readium's WebView, which a unit test has no
     * WebView for.
     */
    /** Test clock, in milliseconds; advance it to step past the post-resize anchor hold. */
    private var clock = 0L

    private fun vmServing(
        vararg anchors: Locator,
        /** Held open from the second read onwards, to park one in flight across a rotation. */
        gate: CompletableDeferred<Unit>? = null,
    ): ReaderViewModel {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        var call = 0
        return ReaderViewModel(
            documentId = 1L,
            docs = DocumentRepository(db.documentDao()),
            progress = ProgressRepository(db.progressDao()),
            readium = ReadiumFactory(context),
            preferencesStore = ReaderPreferencesStore(context),
            readDomAnchor = { _: EpubNavigatorFragment? ->
                if (call > 0) gate?.await()
                anchors[minOf(call++, anchors.size - 1)]
            },
            nowMs = { clock },
        )
    }

    private fun domAnchor(nth: Int) = Locator(
        href = Url("ch1.xhtml")!!,
        mediaType = MediaType.XHTML,
        locations = Locator.Locations(
            otherLocations = mapOf("cssSelector" to ":root > :nth-child(2) > :nth-child($nth)"),
        ),
        text = Locator.Text(highlight = "Paragraph $nth."),
    )

    private val Locator?.selector: String?
        get() = this?.locations?.otherLocations?.get("cssSelector") as? String

    @Test fun `the anchor carries a DOM location, not just a progression`() = runTest {
        val vm = vmServing(domAnchor(23))
        vm.publishLocator(locatorAt(0.5))
        vm.onViewportChanged(1080, 2424)
        vm.onViewportChanged(2424, 1080)
        vm.completeViewportResize()

        // Readium restores a locator precisely only when it carries text to match; without it,
        // it maps the progression onto the new page grid and lands wherever that arithmetic
        // happens to point. This is the whole fix: rotation must hand it a DOM location.
        assertThat(vm.currentLocator.value?.text?.highlight).isEqualTo("Paragraph 23.")
        assertThat(vm.currentLocator.value.selector)
            .isEqualTo(":root > :nth-child(2) > :nth-child(23)")
        // ...while still carrying the position it left, for Readium's own fallback and for the
        // percentage the HUD and the progress row read.
        assertThat(vm.currentLocator.value?.locations?.progression).isEqualTo(0.5)
    }

    @Test fun `a rotation does not move the anchor, so returning is exact`() = runTest {
        // Which element "starts the page" depends on the shape of the page: a tall portrait
        // column starts several paragraphs where a short landscape one starts a single
        // paragraph. Re-reading the anchor while the reader is rotated therefore replaces it
        // with an earlier one, and honouring that on the way back walks the reader backwards a
        // page at a time. A rotation is not a change of reading position: the anchor must be
        // whatever the reader was last actually on.
        val vm = vmServing(domAnchor(23), domAnchor(22), domAnchor(18))
        vm.publishLocator(locatorAt(0.5))
        vm.onViewportChanged(1080, 2424)

        repeat(3) {
            vm.onViewportChanged(2424, 1080)
            vm.completeViewportResize()
            vm.onViewportChanged(1080, 2424)
            vm.completeViewportResize()
            assertThat(vm.currentLocator.value.selector)
                .isEqualTo(":root > :nth-child(2) > :nth-child(23)")
        }
    }

    @Test fun `Readium's own report of the restored page does not move the anchor`() = runTest {
        // Readium reports where it landed of its own accord, about half a second after the
        // jump. That arrives as an ordinary publish, so without a hold it re-reads the anchor
        // from the page the reader is only rotating through — which is how the drift crept back
        // in intermittently even once the anchor was no longer refreshed explicitly.
        val vm = vmServing(domAnchor(23), domAnchor(31))
        vm.publishLocator(locatorAt(0.5))
        vm.onViewportChanged(1080, 2424)

        vm.onViewportChanged(2424, 1080)
        vm.completeViewportResize()
        clock += 500
        vm.publishLocator(locatorAt(0.39))

        vm.onViewportChanged(1080, 2424)
        vm.completeViewportResize()
        assertThat(vm.currentLocator.value.selector)
            .isEqualTo(":root > :nth-child(2) > :nth-child(23)")
    }

    @Test fun `moving the reader does move the anchor`() = runTest {
        // The other half of the same rule: the anchor has to follow the reader when the reader
        // is the one moving, page turns and swipes alike, both of which arrive as a publish.
        val vm = vmServing(domAnchor(23), domAnchor(31))
        vm.publishLocator(locatorAt(0.5))
        vm.onViewportChanged(1080, 2424)
        // A rotation, and then — once its dust has settled — a page turn.
        vm.onViewportChanged(2424, 1080)
        vm.completeViewportResize()
        clock += 5_000
        vm.publishLocator(locatorAt(0.6))

        vm.onViewportChanged(1080, 2424)
        vm.completeViewportResize()
        assertThat(vm.currentLocator.value.selector)
            .isEqualTo(":root > :nth-child(2) > :nth-child(31)")
    }

    @Test fun `an anchor read while the reader is resizing is discarded`() = runTest {
        // The read is a round trip into the WebView's JavaScript. If a rotation starts while one
        // is in flight, the answer that comes back describes the re-paginated page — exactly the
        // page the anchor exists to avoid. Keeping the older, pre-rotation one is right even
        // though it is a page turn behind.
        val gate = CompletableDeferred<Unit>()
        val vm = vmServing(domAnchor(23), domAnchor(31), gate = gate)
        vm.publishLocator(locatorAt(0.5))
        vm.onViewportChanged(1080, 2424)

        // A page turn, whose read parks on the gate, and then a rotation on top of it.
        vm.publishLocator(locatorAt(0.6))
        vm.onViewportChanged(2424, 1080)
        gate.complete(Unit)
        vm.completeViewportResize()

        // The next rotation is where a swallowed answer would show up.
        vm.onViewportChanged(1080, 2424)
        vm.completeViewportResize()
        assertThat(vm.currentLocator.value.selector)
            .isEqualTo(":root > :nth-child(2) > :nth-child(23)")
    }

    @Test fun `re-anchoring mid-resize does not end the resize`() = runTest {
        // Readium re-paginates in steps and the reader is put back on the anchor at each one,
        // because an early step measures against a page grid that is still the old one. Those
        // steps must not be mistaken for the end of the resize.
        val vm = vmServing(domAnchor(23))
        vm.publishLocator(locatorAt(0.5))
        vm.onViewportChanged(1080, 2424)
        vm.onViewportChanged(2424, 1080)

        vm.reanchorViewport()
        vm.publishLocator(locatorAt(0.31))
        assertThat(vm.currentLocator.value?.locations?.progression).isEqualTo(0.5)

        vm.completeViewportResize()
        vm.publishLocator(locatorAt(0.55))
        assertThat(vm.currentLocator.value?.locations?.progression).isEqualTo(0.55)
    }

    @Test fun `a jump forgets the anchor rather than dragging the reader back`() = runTest {
        // The cached anchor describes the page being left. A rotation landing between the jump
        // and Readium's report of where it arrived would otherwise fold that element into the
        // new locator, and Readium would honour the element over the progression.
        val vm = vmServing(domAnchor(23))
        vm.publishLocator(locatorAt(0.5))
        vm.onViewportChanged(1080, 2424)

        vm.goTo(locatorAt(0.9))
        vm.onViewportChanged(2424, 1080)
        vm.completeViewportResize()

        assertThat(vm.currentLocator.value.selector).isNull()
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
