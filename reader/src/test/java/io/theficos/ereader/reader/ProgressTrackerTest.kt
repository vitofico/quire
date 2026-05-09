package io.theficos.ereader.reader

import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.core.model.Progress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProgressTrackerTest {

    private fun locatorAt(href: String, totalProgression: Double, progression: Double = totalProgression): Locator =
        Locator(
            href = Url(href)!!,
            mediaType = MediaType.XHTML,
            locations = Locator.Locations(
                progression = progression,
                totalProgression = totalProgression,
            ),
        )

    private fun newTracker(
        saved: MutableList<Progress>,
        scope: kotlinx.coroutines.CoroutineScope,
        nowProvider: () -> Long,
    ) = ProgressTracker(
        save = { saved += it },
        scope = scope,
        nowMs = nowProvider,
    )

    @Test fun `debounces saves to one per second`() = runTest(UnconfinedTestDispatcher()) {
        val saved = mutableListOf<Progress>()
        val locators = MutableSharedFlow<Locator>(extraBufferCapacity = 16)
        val tracker = newTracker(saved, backgroundScope) { testScheduler.currentTime }
        tracker.attach(documentId = 1L, locatorUpdates = locators, lastSpineHref = null, initialFinishedAt = null)

        repeat(5) { locators.tryEmit(locatorAt("/ch1", 0.10 + it * 0.01)) }
        runCurrent()
        advanceTimeBy(50)
        assertThat(saved).isEmpty()

        advanceTimeBy(1_000)
        assertThat(saved).hasSize(1)
        assertThat(saved.last().percent).isWithin(0.001).of(0.14)
    }

    @Test fun `flushes immediately on detach`() = runTest(UnconfinedTestDispatcher()) {
        val saved = mutableListOf<Progress>()
        val locators = MutableSharedFlow<Locator>(extraBufferCapacity = 16)
        val tracker = newTracker(saved, backgroundScope) { testScheduler.currentTime }
        tracker.attach(documentId = 1L, locatorUpdates = locators, lastSpineHref = null, initialFinishedAt = null)
        locators.tryEmit(locatorAt("/ch1", 0.5))
        runCurrent()
        tracker.detach()
        assertThat(saved).hasSize(1)
        assertThat(saved.first().locator).contains("/ch1")
        assertThat(saved.first().percent).isEqualTo(0.5)
        assertThat(saved.first().finishedAt).isNull()
    }

    @Test fun `marks finished when totalProgression crosses 0_98`() = runTest(UnconfinedTestDispatcher()) {
        val saved = mutableListOf<Progress>()
        val locators = MutableSharedFlow<Locator>(extraBufferCapacity = 16)
        val tracker = newTracker(saved, backgroundScope) { testScheduler.currentTime }
        tracker.attach(documentId = 1L, locatorUpdates = locators, lastSpineHref = null, initialFinishedAt = null)
        locators.tryEmit(locatorAt("/ch9", 0.985))
        runCurrent()
        advanceTimeBy(1_100)
        assertThat(saved).hasSize(1)
        assertThat(saved.last().finishedAt).isNotNull()
    }

    @Test fun `does not mark finished below threshold and away from last spine`() = runTest(UnconfinedTestDispatcher()) {
        val saved = mutableListOf<Progress>()
        val locators = MutableSharedFlow<Locator>(extraBufferCapacity = 16)
        val tracker = newTracker(saved, backgroundScope) { testScheduler.currentTime }
        tracker.attach(documentId = 1L, locatorUpdates = locators, lastSpineHref = Url("/ch9")!!, initialFinishedAt = null)
        locators.tryEmit(locatorAt("/ch5", 0.40))
        runCurrent()
        advanceTimeBy(1_100)
        assertThat(saved).hasSize(1)
        assertThat(saved.last().finishedAt).isNull()
    }

    @Test fun `marks finished when at last spine and progression at end`() = runTest(UnconfinedTestDispatcher()) {
        val saved = mutableListOf<Progress>()
        val locators = MutableSharedFlow<Locator>(extraBufferCapacity = 16)
        val tracker = newTracker(saved, backgroundScope) { testScheduler.currentTime }
        tracker.attach(documentId = 1L, locatorUpdates = locators, lastSpineHref = Url("/ch9")!!, initialFinishedAt = null)
        // totalProgression below threshold but we're at last spine and end-of-resource
        locators.tryEmit(locatorAt("/ch9", totalProgression = 0.92, progression = 0.995))
        runCurrent()
        advanceTimeBy(1_100)
        assertThat(saved).hasSize(1)
        assertThat(saved.last().finishedAt).isNotNull()
    }

    @Test fun `finishedAt is sticky once set`() = runTest(UnconfinedTestDispatcher()) {
        val saved = mutableListOf<Progress>()
        val locators = MutableSharedFlow<Locator>(extraBufferCapacity = 16)
        val tracker = newTracker(saved, backgroundScope) { testScheduler.currentTime }
        tracker.attach(documentId = 1L, locatorUpdates = locators, lastSpineHref = null, initialFinishedAt = 4242L)
        locators.tryEmit(locatorAt("/ch1", 0.10))
        runCurrent()
        advanceTimeBy(1_100)
        assertThat(saved).hasSize(1)
        assertThat(saved.last().finishedAt).isEqualTo(4242L)
    }
}
