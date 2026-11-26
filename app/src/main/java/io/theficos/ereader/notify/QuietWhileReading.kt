package io.theficos.ereader.notify

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat

/** What quiet mode needs from Android's Do Not Disturb, so the rules can be tested without it. */
interface DoNotDisturb {
    /** True while the reader has granted Quire Do Not Disturb access. */
    val accessGranted: Boolean

    /** The current interruption filter, one of `NotificationManager.INTERRUPTION_FILTER_*`. */
    var filter: Int

    /** Call [onChange] whenever the filter changes, until the returned handle is closed. */
    fun watch(onChange: () -> Unit): AutoCloseable
}

/**
 * "Quiet while reading": Do Not Disturb, priority only, while a book is open on screen.
 *
 * Priority only rather than alarms only: it is the reader's own Do Not Disturb, so the calls,
 * contacts and alarms they already let through still get through. Alarms only would also hold
 * back a call from someone they chose to always hear from.
 *
 * The rule is never to leave the phone silenced, and never to undo a choice the reader made:
 * - It changes nothing when Do Not Disturb was already on; then there is nothing to put back.
 * - Before changing the filter it records, with a synchronous write, which filter it set. Only that
 *   record allows a later restore, so a restore after process death ([restoreLeftover], called on
 *   every process start) works, and nothing is restored that Quire did not change.
 * - When the filter changes while it is on (the reader switched Do Not Disturb by hand), it drops
 *   the record and leaves the phone as the reader set it.
 * - On leaving it restores "all" only while the filter is still the one it set.
 *
 * Nothing here runs per page: only when the reader comes on or goes off screen.
 */
class QuietWhileReading(
    private val dnd: DoNotDisturb,
    private val store: NotificationPreferencesStore,
) {
    private var watch: AutoCloseable? = null

    /** The reader came on screen. */
    fun onReaderShown() {
        if (!store.settings.value.quietWhileReading || store.quietApplied() != null) return
        try {
            if (!dnd.accessGranted || dnd.filter != NotificationManager.INTERRUPTION_FILTER_ALL) return
            store.setQuietApplied(QUIET_FILTER)
            dnd.filter = QUIET_FILTER
            watch = dnd.watch(::onFilterChanged)
        } catch (e: SecurityException) {
            // Access was withdrawn between the check and the change: nothing was silenced.
            Log.w(TAG, "Couldn't turn on Do Not Disturb", e)
            store.setQuietApplied(null)
        }
    }

    /** The reader went off screen: closed, covered, or the app went to the background. */
    fun onReaderHidden() = restore()

    /** On process start: the process died while a book had the phone silenced. */
    fun restoreLeftover() = restore()

    /** The reader switched the feature off. */
    fun setEnabled(enabled: Boolean) {
        if (!enabled) restore()
        store.setQuietWhileReading(enabled)
    }

    internal fun onFilterChanged() {
        val applied = store.quietApplied() ?: return
        val current = runCatching { dnd.filter }.getOrNull()
        if (current != applied) {
            // The reader changed Do Not Disturb by hand: from now on it is theirs.
            stopWatching()
            store.setQuietApplied(null)
        }
    }

    private fun restore() {
        stopWatching()
        val applied = store.quietApplied() ?: return
        try {
            if (dnd.accessGranted && dnd.filter == applied) dnd.filter = NotificationManager.INTERRUPTION_FILTER_ALL
        } catch (e: SecurityException) {
            Log.w(TAG, "Couldn't restore Do Not Disturb", e)
        }
        store.setQuietApplied(null)
    }

    private fun stopWatching() {
        watch?.close()
        watch = null
    }

    private companion object {
        const val TAG = "QuietWhileReading"
        const val QUIET_FILTER = NotificationManager.INTERRUPTION_FILTER_PRIORITY
    }
}

/** [DoNotDisturb] on the platform's NotificationManager. */
class AndroidDoNotDisturb(context: Context) : DoNotDisturb {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)

    override val accessGranted: Boolean get() = manager.isNotificationPolicyAccessGranted

    override var filter: Int
        get() = manager.currentInterruptionFilter
        set(value) = manager.setInterruptionFilter(value)

    override fun watch(onChange: () -> Unit): AutoCloseable {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onChange()
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        return AutoCloseable { runCatching { appContext.unregisterReceiver(receiver) } }
    }
}
