package io.theficos.ereader.notify

import android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS
import android.app.NotificationManager.INTERRUPTION_FILTER_ALL
import android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class QuietWhileReadingTest {
    private class FakeDnd : DoNotDisturb {
        override var accessGranted = true
        override var filter = INTERRUPTION_FILTER_ALL
        var watchers = 0
        var onChange: (() -> Unit)? = null
        val sets = mutableListOf<Int>()

        fun userSets(value: Int) {
            filter = value
            onChange?.invoke()
        }

        override fun watch(onChange: () -> Unit): AutoCloseable {
            watchers++
            this.onChange = onChange
            return AutoCloseable { watchers--; this.onChange = null }
        }
    }

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var dnd: FakeDnd
    private lateinit var store: NotificationPreferencesStore
    private lateinit var quiet: QuietWhileReading

    @Before fun setUp() {
        context.getSharedPreferences("notification_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("quiet_state", Context.MODE_PRIVATE).edit().clear().commit()
        dnd = FakeDnd()
        store = NotificationPreferencesStore(context)
        quiet = QuietWhileReading(recording(dnd), store)
        store.setQuietWhileReading(true)
    }

    /** Records every filter Quire sets, so a test can say "changed nothing". */
    private fun recording(fake: FakeDnd) = object : DoNotDisturb by fake {
        override var filter: Int
            get() = fake.filter
            set(value) {
                fake.sets += value
                fake.filter = value
            }
    }

    @Test fun `off by default it never touches Do Not Disturb`() {
        store.setQuietWhileReading(false)
        quiet.onReaderShown()
        quiet.onReaderHidden()
        assertThat(dnd.sets).isEmpty()
    }

    @Test fun `opening a book silences, closing it restores`() {
        quiet.onReaderShown()
        assertThat(dnd.filter).isEqualTo(INTERRUPTION_FILTER_PRIORITY)
        assertThat(store.quietApplied()).isEqualTo(INTERRUPTION_FILTER_PRIORITY)

        quiet.onReaderHidden()
        assertThat(dnd.filter).isEqualTo(INTERRUPTION_FILTER_ALL)
        assertThat(store.quietApplied()).isNull()
        assertThat(dnd.watchers).isEqualTo(0)
    }

    @Test fun `already in Do Not Disturb, it changes nothing and restores nothing`() {
        dnd.filter = INTERRUPTION_FILTER_ALARMS
        quiet.onReaderShown()
        quiet.onReaderHidden()
        assertThat(dnd.sets).isEmpty()
        assertThat(dnd.filter).isEqualTo(INTERRUPTION_FILTER_ALARMS)
    }

    @Test fun `a change by hand while reading is left alone on exit`() {
        quiet.onReaderShown()
        dnd.userSets(INTERRUPTION_FILTER_ALARMS)
        quiet.onReaderHidden()
        assertThat(dnd.filter).isEqualTo(INTERRUPTION_FILTER_ALARMS)
        assertThat(store.quietApplied()).isNull()
    }

    @Test fun `switching off then on again by hand still counts as the reader's choice`() {
        quiet.onReaderShown()
        dnd.userSets(INTERRUPTION_FILTER_ALL)
        dnd.userSets(INTERRUPTION_FILTER_PRIORITY)
        quiet.onReaderHidden()
        assertThat(dnd.filter).isEqualTo(INTERRUPTION_FILTER_PRIORITY)
    }

    @Test fun `the change Quire made itself does not count as the reader's`() {
        quiet.onReaderShown()
        dnd.onChange?.invoke()
        quiet.onReaderHidden()
        assertThat(dnd.filter).isEqualTo(INTERRUPTION_FILTER_ALL)
    }

    @Test fun `after process death the marker restores on the next start`() {
        quiet.onReaderShown()
        // A new process: new controller and store, same preferences on disk.
        val restarted = QuietWhileReading(recording(dnd), NotificationPreferencesStore(context))
        restarted.restoreLeftover()
        assertThat(dnd.filter).isEqualTo(INTERRUPTION_FILTER_ALL)
        assertThat(NotificationPreferencesStore(context).quietApplied()).isNull()
    }

    @Test fun `after process death a filter the reader changed since is left alone`() {
        quiet.onReaderShown()
        dnd.filter = INTERRUPTION_FILTER_ALARMS
        QuietWhileReading(recording(dnd), NotificationPreferencesStore(context)).restoreLeftover()
        assertThat(dnd.filter).isEqualTo(INTERRUPTION_FILTER_ALARMS)
    }

    @Test fun `a start with no marker changes nothing`() {
        dnd.filter = INTERRUPTION_FILTER_PRIORITY
        quiet.restoreLeftover()
        assertThat(dnd.sets).isEmpty()
    }

    @Test fun `switching the feature off while reading restores at once`() {
        quiet.onReaderShown()
        quiet.setEnabled(false)
        assertThat(dnd.filter).isEqualTo(INTERRUPTION_FILTER_ALL)
        assertThat(store.settings.value.quietWhileReading).isFalse()
        // Leaving the book afterwards has nothing left to do.
        quiet.onReaderHidden()
        assertThat(dnd.sets).containsExactly(INTERRUPTION_FILTER_PRIORITY, INTERRUPTION_FILTER_ALL).inOrder()
    }

    @Test fun `without access it does nothing, and access withdrawn mid-read only clears the marker`() {
        dnd.accessGranted = false
        quiet.onReaderShown()
        assertThat(dnd.sets).isEmpty()

        dnd.accessGranted = true
        quiet.onReaderShown()
        dnd.accessGranted = false
        quiet.onReaderHidden()
        assertThat(store.quietApplied()).isNull()
    }

    @Test fun `a second show while silenced does not stack`() {
        quiet.onReaderShown()
        quiet.onReaderShown()
        assertThat(dnd.sets).containsExactly(INTERRUPTION_FILTER_PRIORITY)
        assertThat(dnd.watchers).isEqualTo(1)
    }
}
