package io.theficos.ereader.notify

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class QuietWhileReadingTest {
    /** Quire's own rule, as the system would hold it. */
    private class FakeDnd(private val store: NotificationPreferencesStore) : DoNotDisturb {
        override var supported = true
        override var accessGranted = true
        var ruleOn = false
        val calls = mutableListOf<String>()

        override fun setRuleActive(active: Boolean) {
            calls += if (active) "on" else "off"
            if (active) store.quietRuleId = "rule-1"
            if (store.quietRuleId != null) ruleOn = active
        }

        override fun removeRule() {
            calls += "remove"
            store.quietRuleId = null
            ruleOn = false
        }
    }

    private class FakeSafety : QuietSafetyNet {
        val calls = mutableListOf<String>()
        override fun arm() { calls += "arm" }
        override fun rearm() { calls += "rearm" }
        override fun cancel() { calls += "cancel" }
    }

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var store: NotificationPreferencesStore
    private lateinit var dnd: FakeDnd
    private lateinit var safety: FakeSafety
    private lateinit var quiet: QuietWhileReading
    private val handlerBefore = Thread.getDefaultUncaughtExceptionHandler()

    @Before fun setUp() {
        for (file in listOf("notification_prefs", "new_books_seen", DEVICE_PREFS)) {
            context.getSharedPreferences(file, Context.MODE_PRIVATE).edit().clear().commit()
        }
        store = NotificationPreferencesStore(context)
        dnd = FakeDnd(store)
        safety = FakeSafety()
        quiet = QuietWhileReading(dnd, store, safety)
        store.setQuietWhileReading(true)
    }

    @After fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(handlerBefore)
    }

    @Test fun `off by default it never touches Do Not Disturb`() {
        store.setQuietWhileReading(false)
        quiet.onReaderShown("a")
        quiet.onReaderHidden("a")
        assertThat(dnd.calls).isEmpty()
        assertThat(safety.calls).isEmpty()
    }

    @Test fun `opening a book turns Quire's rule on, closing it turns the rule off`() {
        quiet.onReaderShown("a")
        assertThat(dnd.ruleOn).isTrue()
        assertThat(safety.calls).containsExactly("arm")

        quiet.onReaderHidden("a")
        assertThat(dnd.ruleOn).isFalse()
        assertThat(dnd.calls).containsExactly("on", "off").inOrder()
        assertThat(safety.calls).containsExactly("arm", "cancel").inOrder()
    }

    @Test fun `a second reader opened over the first keeps it quiet until both are gone`() {
        // Tapping the reminder while book A is open: B starts before A stops.
        quiet.onReaderShown("a")
        quiet.onReaderShown("b")
        quiet.onReaderHidden("a")
        assertThat(dnd.ruleOn).isTrue()

        quiet.onReaderHidden("b")
        assertThat(dnd.ruleOn).isFalse()
        assertThat(dnd.calls).containsExactly("on", "off").inOrder()
    }

    @Test fun `a hide repeated by dispose after stop does nothing more`() {
        quiet.onReaderShown("a")
        quiet.onReaderHidden("a")
        quiet.onReaderHidden("a")
        assertThat(dnd.calls).containsExactly("on", "off").inOrder()
    }

    @Test fun `without access or support nothing is touched`() {
        dnd.accessGranted = false
        quiet.onReaderShown("a")
        quiet.onReaderHidden("a")
        dnd.accessGranted = true
        dnd.supported = false
        quiet.onReaderShown("b")
        quiet.onReaderHidden("b")
        assertThat(dnd.calls).isEmpty()
        assertThat(safety.calls).isEmpty()
    }

    @Test fun `switching the feature off mid-read turns the rule off and deletes it`() {
        quiet.onReaderShown("a")
        quiet.setEnabled(false)
        assertThat(dnd.calls).containsExactly("on", "off", "remove").inOrder()
        assertThat(store.quietRuleId).isNull()
        assertThat(store.settings.value.quietWhileReading).isFalse()
    }

    @Test fun `a start after a read cut short turns the rule off`() {
        quiet.onReaderShown("a")
        // The process dies; a new one starts with fresh state over the same files.
        val next = QuietWhileReading(dnd, store, safety)
        next.restoreLeftover()
        assertThat(dnd.ruleOn).isFalse()
        assertThat(safety.calls.last()).isEqualTo("cancel")
    }

    @Test fun `a start with no rule created touches nothing`() {
        quiet.restoreLeftover()
        assertThat(dnd.calls).isEmpty()
    }

    @Test fun `the safety check rebooks while reading and turns the rule off otherwise`() {
        quiet.onReaderShown("a")
        quiet.onSafetyCheck()
        assertThat(safety.calls).containsExactly("arm", "rearm").inOrder()
        assertThat(dnd.ruleOn).isTrue()

        // A new process woken by the check, with no reader on screen.
        val woken = QuietWhileReading(dnd, store, safety)
        woken.onSafetyCheck()
        assertThat(dnd.ruleOn).isFalse()
    }

    @Test fun `a crash while quiet turns the rule off, then hands on to the previous handler`() {
        val seen = mutableListOf<Throwable>()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> seen += e }
        quiet.onReaderShown("a")

        val boom = IllegalStateException("boom")
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), boom)

        assertThat(dnd.ruleOn).isFalse()
        assertThat(seen).containsExactly(boom)
    }

    @Test fun `a rule deleted in system settings does not crash the reader`() {
        val throwing = object : DoNotDisturb by dnd {
            override fun setRuleActive(active: Boolean) = throw IllegalArgumentException("Rule doesn't exist")
        }
        val q = QuietWhileReading(throwing, store, safety)
        q.onReaderShown("a")
        q.onReaderHidden("a")
    }
}
