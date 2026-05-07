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

    private fun locatorAt(href: String, totalProgression: Double): Locator =
        Locator(
            href = Url(href)!!,
            mediaType = MediaType.XHTML,
            locations = Locator.Locations(
                progression = totalProgression,
                totalProgression = totalProgression,
            ),
        )

    @Test fun `debounces saves to one per second`() = runTest(UnconfinedTestDispatcher()) {
        val saved = mutableListOf<Progress>()
        val locators = MutableSharedFlow<Locator>(extraBufferCapacity = 16)
        val tracker = ProgressTracker(
            save = { saved += it },
            scope = backgroundScope,
            nowMs = { testScheduler.currentTime },
        )
        tracker.attach(documentId = 1L, locatorUpdates = locators)

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
        val tracker = ProgressTracker(
            save = { saved += it },
            scope = backgroundScope,
            nowMs = { testScheduler.currentTime },
        )
        tracker.attach(documentId = 1L, locatorUpdates = locators)
        locators.tryEmit(locatorAt("/ch1", 0.5))
        runCurrent()
        tracker.detach()
        assertThat(saved).hasSize(1)
        assertThat(saved.first().locator).contains("/ch1")
        assertThat(saved.first().percent).isEqualTo(0.5)
    }
}
