package io.theficos.ereader.reader

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadyDecorationSessionTest {
    @Test fun `a restored list waits for a ready resource and reads its latest href`() = runTest {
        val session = ReadyDecorationSession("navigator", 1, this)
        val rendered = mutableListOf<String>()
        val submission = async { session.submit({ true }) { nav, href -> rendered += "$nav:$href" } }
        runCurrent()
        assertThat(rendered).isEmpty()
        assertThat(session.pendingSubmissions).isEqualTo(1)

        session.pageReady("chapter-one")
        session.pageReady("chapter-two")
        runCurrent()

        assertThat(submission.await()).isTrue()
        assertThat(rendered).containsExactly("navigator:chapter-two")
        assertThat(session.pendingSubmissions).isEqualTo(0)
        session.close()
    }

    @Test fun `closing a navigator releases pending work without applying it to its replacement`() = runTest {
        val old = ReadyDecorationSession("old", 1, this)
        val rendered = mutableListOf<String>()
        val pending = async { old.submit({ true }) { nav, _ -> rendered += nav } }
        runCurrent()
        old.close()
        old.pageReady("chapter-one")
        assertThat(pending.await()).isFalse()

        val replacement = ReadyDecorationSession("new", 2, this)
        replacement.pageReady("chapter-one")
        assertThat(replacement.submit({ true }) { nav, _ -> rendered += nav }).isTrue()
        assertThat(rendered).containsExactly("new")
        replacement.close()
    }

    @Test fun `submissions are serialized and cancelled old work cannot finish after close`() = runTest {
        val session = ReadyDecorationSession("navigator", 1, this)
        session.pageReady("chapter-one")
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val first = async {
            session.submit({ true }) { _, _ ->
                events += "first-start"
                release.await()
                events += "first-end"
            }
        }
        runCurrent()
        val second = async { session.submit({ true }) { _, _ -> events += "second" } }
        runCurrent()
        assertThat(events).containsExactly("first-start")
        assertThat(session.pendingSubmissions).isEqualTo(2)

        session.close()
        release.complete(Unit)
        assertThat(first.await()).isFalse()
        assertThat(second.await()).isFalse()
        assertThat(events).containsExactly("first-start")
        assertThat(session.pendingSubmissions).isEqualTo(0)
    }

    @Test fun `a ready callback cannot submit for a session the Activity has replaced`() = runTest {
        val session = ReadyDecorationSession("old", 1, this)
        var current = true
        val rendered = mutableListOf<String>()
        val pending = async { session.submit({ current }) { nav, _ -> rendered += nav } }
        runCurrent()
        current = false
        session.pageReady("chapter-one")
        assertThat(pending.await()).isFalse()
        assertThat(rendered).isEmpty()
        session.close()
    }

    @Test fun `cancelling a caller cancels its queued work without closing the navigator`() = runTest {
        val session = ReadyDecorationSession("navigator", 1, this)
        val rendered = mutableListOf<String>()
        val caller = launch { session.submit({ true }) { _, _ -> rendered += "cancelled" } }
        runCurrent()
        caller.cancel()
        runCurrent()
        session.pageReady("chapter-one")
        assertThat(session.submit({ true }) { _, _ -> rendered += "current" }).isTrue()
        assertThat(rendered).containsExactly("current")
        session.close()
    }
}
