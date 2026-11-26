package io.theficos.ereader.notify

import android.app.ActivityManager
import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.notification.Condition
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.theficos.ereader.EReaderApp
import io.theficos.ereader.MainActivity
import io.theficos.quire.R
import java.util.concurrent.TimeUnit

/** What quiet mode needs from Android: one Do Not Disturb rule that Quire owns. */
interface DoNotDisturb {
    /** False where the feature is hidden: before Android 10, on low-RAM phones, or with no access page. */
    val supported: Boolean

    /** True while the reader has granted Quire Do Not Disturb access. */
    val accessGranted: Boolean

    /** Turn Quire's rule on or off, creating it first when turning on. Off with no rule does nothing. */
    fun setRuleActive(active: Boolean)

    /** Delete Quire's rule from the system, when the reader switches the feature off. */
    fun removeRule()
}

/** A run that puts Do Not Disturb back if Quire's process dies with the rule on. */
interface QuietSafetyNet {
    /** Book a check in about [QuietSafetyWorker.AFTER_MINUTES], replacing any booked one. */
    fun arm()

    /** From inside a check while still reading: book the next one. */
    fun rearm()

    fun cancel()
}

/**
 * "Quiet while reading": Do Not Disturb, priority only, while a book is open on screen.
 *
 * It works through an automatic Do Not Disturb rule that Quire owns (it appears by name in the
 * system's Do Not Disturb settings), never through the phone's own Do Not Disturb switch. Turning
 * that rule off cannot switch off anything else: Do Not Disturb the reader turned on by hand, or
 * another rule such as Bedtime, stays exactly as it is, before, during and after reading.
 *
 * Priority only rather than alarms only: the rule uses the reader's own priority settings, so the
 * calls, contacts and alarms they already let through still get through.
 *
 * The rule is on while at least one reader screen is visible, counted per back-stack entry, so
 * opening book B from a notification while book A is on screen does not switch it off when A goes.
 * It is switched off when the last reader screen goes, when the feature is switched off, on every
 * process start (a crash or a kill mid-read), from the crash handler, and by a [QuietSafetyNet]
 * check that restarts a dead process. Nothing here runs per page.
 */
class QuietWhileReading(
    private val dnd: DoNotDisturb,
    private val store: NotificationPreferencesStore,
    private val safety: QuietSafetyNet,
) {
    private val shown = mutableSetOf<String>()

    @Volatile private var active = false
    private var crashGuardInstalled = false

    /** A reader screen, identified by its back-stack entry [key], came on screen. */
    fun onReaderShown(key: String) {
        if (shown.add(key) && shown.size == 1) silence()
    }

    /** That reader screen went off screen: closed, covered, or the app went to the background. */
    fun onReaderHidden(key: String) {
        if (shown.remove(key) && shown.isEmpty() && active) turnOff()
    }

    /** On process start: a read may have been cut short with the rule on. */
    fun restoreLeftover() {
        if (store.quietRuleId != null) turnOff()
    }

    /** The reader switched the feature on or off. Off turns the rule off and deletes it. */
    fun setEnabled(enabled: Boolean) {
        store.setQuietWhileReading(enabled)
        if (enabled) return
        if (active) turnOff()
        guarded("remove the rule") { dnd.removeRule() }
    }

    /** A [QuietSafetyWorker] run: keep watching while a book is on screen, otherwise make sure the rule is off. */
    fun onSafetyCheck() {
        if (active && shown.isNotEmpty()) safety.rearm() else turnOff(cancelSafety = false)
    }

    private fun silence() {
        if (!store.settings.value.quietWhileReading || !dnd.supported) return
        guarded("turn on the rule") {
            if (!dnd.accessGranted) return@guarded
            installCrashGuard()
            active = true
            dnd.setRuleActive(true)
            safety.arm()
        }
    }

    private fun turnOff(cancelSafety: Boolean = true) {
        active = false
        guarded("turn off the rule") { dnd.setRuleActive(false) }
        if (cancelSafety) safety.cancel()
    }

    /** A crash while silenced turns the rule off before the process goes. */
    private fun installCrashGuard() {
        if (crashGuardInstalled) return
        crashGuardInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (active) runCatching { dnd.setRuleActive(false) }
            previous?.uncaughtException(thread, error)
        }
    }

    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: SecurityException) {
            // Access was withdrawn; Android then removes the app's rules itself.
            Log.w(TAG, "Couldn't $what", e)
        } catch (e: IllegalArgumentException) {
            // The rule was deleted in system settings.
            Log.w(TAG, "Couldn't $what", e)
        }
    }

    private companion object {
        const val TAG = "QuietWhileReading"
    }
}

/** [DoNotDisturb] on an [AutomaticZenRule] owned by Quire. */
class AndroidDoNotDisturb(context: Context, private val store: NotificationPreferencesStore) : DoNotDisturb {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)
    private val condition = Uri.Builder().scheme("condition").authority(appContext.packageName).appendPath("reading").build()

    override val supported: Boolean get() = isSupported(appContext)

    override val accessGranted: Boolean get() = manager.isNotificationPolicyAccessGranted

    override fun setRuleActive(active: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val id = if (active) ruleId() else existingRuleId() ?: return
        val state = if (active) Condition.STATE_TRUE else Condition.STATE_FALSE
        manager.setAutomaticZenRuleState(id, Condition(condition, ruleName(), state))
    }

    override fun removeRule() {
        val id = store.quietRuleId ?: return
        store.quietRuleId = null
        manager.removeAutomaticZenRule(id)
    }

    private fun existingRuleId(): String? = store.quietRuleId?.takeIf { manager.getAutomaticZenRule(it) != null }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun ruleId(): String = existingRuleId() ?: manager.addAutomaticZenRule(
        @Suppress("DEPRECATION") // The Builder is API 35; this constructor is the API 29 one.
        AutomaticZenRule(
            ruleName(),
            null,
            ComponentName(appContext, MainActivity::class.java),
            condition,
            null,
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            true,
        ),
    ).also { store.quietRuleId = it }

    private fun ruleName() = appContext.getString(R.string.notify_quiet_rule_name)

    companion object {
        /** Android 10 or later, not a low-RAM phone, and a system page to grant access on. */
        fun isSupported(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                context.getSystemService(ActivityManager::class.java)?.isLowRamDevice != true &&
                Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).resolveActivity(context.packageManager) != null
    }
}

/** [QuietSafetyNet] on WorkManager: unlike the process, a booked run survives a crash. */
internal class WorkManagerQuietSafetyNet(context: Context) : QuietSafetyNet {
    private val appContext = context.applicationContext

    override fun arm() = enqueue(ExistingWorkPolicy.REPLACE)

    override fun rearm() = enqueue(ExistingWorkPolicy.APPEND_OR_REPLACE)

    override fun cancel() {
        WorkManager.getInstance(appContext).cancelUniqueWork(QuietSafetyWorker.UNIQUE)
    }

    private fun enqueue(policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<QuietSafetyWorker>()
            .setInitialDelay(QuietSafetyWorker.AFTER_MINUTES, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(QuietSafetyWorker.UNIQUE, policy, request)
    }
}

/**
 * Runs about every half hour while a book keeps the phone quiet. If Quire's process died, this
 * run starts a new one, whose start turns the rule off; if the process lives and a book is still on
 * screen, it books the next check.
 */
class QuietSafetyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        (applicationContext as? EReaderApp)?.container?.notificationFeatures?.quietSafetyCheck()
        return Result.success()
    }

    internal companion object {
        const val UNIQUE = "quiet-while-reading-safety"
        const val AFTER_MINUTES = 30L
    }
}
