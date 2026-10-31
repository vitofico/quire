package io.theficos.ereader.reader

import com.google.common.truth.Truth.assertThat
import android.webkit.ValueCallback
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
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
@Config(sdk = [28])
class HighlightJumpTransactionTest {
    private val origin = Locator(Url("origin.xhtml")!!, MediaType.XHTML)
    private val target = Locator(Url("target.xhtml")!!, MediaType.XHTML)

    private inner class Driver : HighlightJumpDriver {
        override var isAlive = true
        var snapshot: Locator? = origin
        val shown = mutableListOf<Locator>()
        var show: suspend (Locator) -> Boolean = { true }
        override suspend fun origin() = snapshot
        override suspend fun show(locator: Locator): Boolean { shown += locator; return show.invoke(locator) }
    }

    @Test fun `cancelled transaction waits for bounded missing rollback callback and remains unsafe`() = runTest {
        val callbacks = mutableListOf<ValueCallback<String>>()
        val driver = Driver().apply { show = { awaitReaderJavascript { callbacks += it } == "true" } }
        val transaction = HighlightJumpTransaction(driver)
        val work = launch { transaction.jump(target) { true } }
        runCurrent()
        assertThat(callbacks).hasSize(1)
        work.cancel(); runCurrent()
        assertThat(callbacks).hasSize(2)
        assertThat(work.isCompleted).isFalse()
        testScheduler.advanceTimeBy(1501); runCurrent()
        work.join()
        assertThat(work.isCompleted).isTrue()
        assertThat(driver.shown).containsExactly(target, origin).inOrder()
        assertThat(transaction.safeToPublish).isFalse()
        callbacks.forEach { it.onReceiveValue("true") }
        assertThat(transaction.safeToPublish).isFalse()
    }

    @Test fun `successful exact navigation does not roll back`() = runTest {
        val driver = Driver()
        val transaction = HighlightJumpTransaction(driver)
        assertThat(transaction.jump(target) { true }).isTrue()
        assertThat(driver.shown).containsExactly(target)
        assertThat(transaction.safeToPublish).isTrue()
    }

    @Test fun `unavailable exact target restores its origin before permitting progress`() = runTest {
        val driver = Driver().apply { show = { it == origin } }
        val transaction = HighlightJumpTransaction(driver)
        assertThat(transaction.jump(target) { true }).isFalse()
        assertThat(driver.shown).containsExactly(target, origin).inOrder()
        assertThat(transaction.safeToPublish).isTrue()
    }

    @Test fun `a deleted or superseded target cannot commit after its awaited work`() = runTest {
        var current = true
        val driver = Driver().apply { show = { if (it == target) current = false; true } }
        val transaction = HighlightJumpTransaction(driver)
        assertThat(transaction.jump(target) { current }).isFalse()
        assertThat(driver.shown).containsExactly(target, origin).inOrder()
    }

    @Test fun `Back cancellation completes rollback before the transaction releases its lock`() = runTest {
        val targetPending = CompletableDeferred<Unit>()
        val rollbackPending = CompletableDeferred<Unit>()
        val driver = Driver().apply {
            show = { if (it == target) targetPending.await() else rollbackPending.await(); true }
        }
        val transaction = HighlightJumpTransaction(driver)
        val first = launch { transaction.jump(target) { true } }
        runCurrent()
        assertThat(transaction.safeToPublish).isFalse()
        first.cancel()
        runCurrent()
        assertThat(driver.shown).containsExactly(target, origin).inOrder()
        val second = async { transaction.jump(target) { true } }
        runCurrent()
        assertThat(driver.shown).hasSize(2)
        rollbackPending.complete(Unit)
        targetPending.complete(Unit)
        first.join()
        assertThat(second.await()).isTrue()
        assertThat(driver.shown).containsExactly(target, origin, target).inOrder()
    }

    @Test fun `timed out staging returns to the captured position`() = runTest {
        val pending = CompletableDeferred<Unit>()
        val driver = Driver().apply { show = { if (it == target) pending.await(); true } }
        val transaction = HighlightJumpTransaction(driver, timeoutMs = 100)
        val result = async { transaction.jump(target) { true } }
        advanceUntilIdle()
        assertThat(result.await()).isFalse()
        assertThat(driver.shown).containsExactly(target, origin).inOrder()
        assertThat(transaction.safeToPublish).isTrue()
    }

    @Test fun `failed rollback never marks a staged page safe to persist`() = runTest {
        val driver = Driver().apply { show = { false } }
        val transaction = HighlightJumpTransaction(driver)
        assertThat(transaction.jump(target) { true }).isFalse()
        assertThat(transaction.safeToPublish).isFalse()
    }

    @Test fun `an unsuccessful retry cannot turn an uncertain staged page into a safe origin`() = runTest {
        val driver = Driver().apply { show = { false } }
        val transaction = HighlightJumpTransaction(driver)
        assertThat(transaction.jump(target) { true }).isFalse()
        driver.snapshot = null
        assertThat(transaction.jump(target) { true }).isFalse()
        assertThat(transaction.safeToPublish).isFalse()
        driver.snapshot = target
        driver.show = { true }
        assertThat(transaction.jump(target) { true }).isFalse()
        assertThat(transaction.safeToPublish).isFalse()
        assertThat(driver.shown).containsExactly(target, origin).inOrder()
    }

    @Test fun `closed navigator receives no rollback or success callback`() = runTest {
        val driver = Driver().apply { show = { isAlive = false; true } }
        val transaction = HighlightJumpTransaction(driver)
        assertThat(transaction.jump(target) { true }).isFalse()
        assertThat(driver.shown).containsExactly(target)
        assertThat(transaction.safeToPublish).isFalse()
    }

    @Test fun `unavailable origin leaves the book untouched`() = runTest {
        val driver = Driver().apply { snapshot = null }
        val transaction = HighlightJumpTransaction(driver)
        assertThat(transaction.jump(target) { true }).isFalse()
        assertThat(driver.shown).isEmpty()
        assertThat(transaction.safeToPublish).isTrue()
    }

    @Test fun `rollback-only position metadata is rejected on destinations before any movement`() = runTest {
        val driver = Driver()
        val transaction = HighlightJumpTransaction(driver)
        val mixed = target.copy(locations = Locator.Locations(otherLocations = mapOf(
            "quireDomRange" to "saved range", VIEWPORT_POSITION_KEY to "origin snapshot")))
        assertThat(transaction.jump(mixed) { true }).isFalse()
        assertThat(driver.shown).isEmpty()
        assertThat(transaction.safeToPublish).isTrue()
    }

    @Test fun `textless origin snapshot is retained for rollback without destination fallback`() = runTest {
        val cover = origin.copy(locations = Locator.Locations(otherLocations = mapOf("quireViewportPosition" to "snapshot")))
        val driver = Driver().apply { snapshot = cover; show = { it == cover } }
        val transaction = HighlightJumpTransaction(driver)
        assertThat(transaction.jump(target) { true }).isFalse()
        assertThat(driver.shown).containsExactly(target, cover).inOrder()
    }

    @Test fun `chapter staging removes ambiguous selectors text and saved page percentages`() {
        val saved = target.copy(text = Locator.Text(highlight = "repeated"), locations = Locator.Locations(
            progression = .8, position = 12, otherLocations = mapOf("cssSelector" to "p", "quireDomRange" to "saved")))
        val stage = resourceStart(saved)
        assertThat(stage.href).isEqualTo(target.href)
        assertThat(stage.text.highlight).isNull()
        assertThat(stage.locations.otherLocations).isEmpty()
        assertThat(stage.locations.position).isNull()
        assertThat(stage.locations.progression).isEqualTo(0.0)
    }
}
