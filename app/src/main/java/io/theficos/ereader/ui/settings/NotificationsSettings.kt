package io.theficos.ereader.ui.settings

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.theficos.ereader.notify.NotificationFeatures
import io.theficos.ereader.notify.Notifier
import io.theficos.ereader.ui.components.QuireCard
import io.theficos.ereader.ui.components.SectionLabel
import io.theficos.quire.R
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Date

private enum class Pending { NONE, NEW_BOOKS, REMINDER, QUIET }

/**
 * Settings → Notifications and quiet: new books in catalogs, a daily reading reminder, and Do Not
 * Disturb while reading. Each permission is asked only when the reader switches on the feature
 * that needs it, and a switch stays off until the permission is there.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NotificationsSettings(features: NotificationFeatures, catalogNames: Map<String, String>) {
    val context = LocalContext.current
    val settings by features.settings.collectAsState()
    // Permissions can change in system settings while Quire is in the background.
    var canPost by rememberSaveable { mutableStateOf(Notifier.canPost(context)) }
    var dndGranted by rememberSaveable { mutableStateOf(dndAccessGranted(context)) }
    var pending by rememberSaveable { mutableStateOf(Pending.NONE) }
    var notificationsBlocked by rememberSaveable { mutableStateOf(false) }
    var explainDnd by rememberSaveable { mutableStateOf(false) }
    var pickTime by rememberSaveable { mutableStateOf(false) }

    fun enable(feature: Pending) = when (feature) {
        Pending.NEW_BOOKS -> features.setNewBooks(true)
        Pending.REMINDER -> features.setReminder(true)
        Pending.QUIET -> features.setQuietWhileReading(true)
        Pending.NONE -> Unit
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        canPost = Notifier.canPost(context)
        dndGranted = dndAccessGranted(context)
        if (canPost) notificationsBlocked = false
        // Back from the Do Not Disturb access page: switch on only if it was granted there.
        if (pending == Pending.QUIET) {
            if (dndGranted) enable(Pending.QUIET)
            pending = Pending.NONE
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        canPost = Notifier.canPost(context)
        if (granted && canPost) enable(pending) else notificationsBlocked = true
        pending = Pending.NONE
    }

    fun switchOnNotifying(feature: Pending) {
        when {
            Notifier.canPost(context) -> {
                canPost = true
                enable(feature)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsBlocked -> {
                pending = feature
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            else -> notificationsBlocked = true
        }
    }

    SectionLabel(stringResource(R.string.notify_section_title))
    QuireCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (notificationsBlocked || (!canPost && (settings.newBooks || settings.reminder))) {
                Column {
                    Text(
                        stringResource(R.string.notify_permission_blocked),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { context.openAppNotificationSettings() }) {
                        Text(stringResource(R.string.notify_open_settings))
                    }
                }
            }

            SwitchRow(
                title = stringResource(R.string.notify_new_books_title),
                description = stringResource(R.string.notify_new_books_hint),
                checked = settings.newBooks,
                onCheckedChange = { on -> if (on) switchOnNotifying(Pending.NEW_BOOKS) else features.setNewBooks(false) },
            )
            val skipped = settings.catalogsWithoutLatest.mapNotNull { catalogNames[it] }
            if (settings.newBooks && skipped.isNotEmpty()) {
                Text(
                    stringResource(R.string.notify_new_books_no_feed, skipped.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()
            SwitchRow(
                title = stringResource(R.string.notify_reminder_switch),
                description = stringResource(R.string.notify_reminder_hint),
                checked = settings.reminder,
                onCheckedChange = { on -> if (on) switchOnNotifying(Pending.REMINDER) else features.setReminder(false) },
            )
            if (settings.reminder) {
                TextButton(onClick = { pickTime = true }) {
                    Text(stringResource(R.string.notify_reminder_at, formatTime(context, settings.reminderTime)))
                }
                val locale = LocalConfiguration.current.locales[0]
                val first = WeekFields.of(locale).firstDayOfWeek
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (0L until 7L).map { first.plus(it) }.forEach { day ->
                        val selected = day in settings.reminderDays
                        FilterChip(
                            selected = selected,
                            // The last chosen day stays: a reminder on no day is the switch being off.
                            onClick = {
                                features.setReminderDays(if (selected) settings.reminderDays - day else settings.reminderDays + day)
                            },
                            label = { Text(day.getDisplayName(TextStyle.SHORT, locale)) },
                        )
                    }
                }
            }

            HorizontalDivider()
            SwitchRow(
                title = stringResource(R.string.notify_quiet_title),
                description = stringResource(R.string.notify_quiet_hint),
                checked = settings.quietWhileReading && dndGranted,
                onCheckedChange = { on ->
                    when {
                        !on -> features.setQuietWhileReading(false)
                        dndAccessGranted(context) -> {
                            dndGranted = true
                            features.setQuietWhileReading(true)
                        }
                        else -> explainDnd = true
                    }
                },
            )
            if (settings.quietWhileReading && !dndGranted) {
                Text(
                    stringResource(R.string.notify_quiet_access_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (explainDnd) {
        AlertDialog(
            onDismissRequest = { explainDnd = false },
            title = { Text(stringResource(R.string.notify_quiet_access_title)) },
            text = { Text(stringResource(R.string.notify_quiet_access_explain)) },
            confirmButton = {
                TextButton(onClick = {
                    explainDnd = false
                    pending = Pending.QUIET
                    context.startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text(stringResource(R.string.notify_open_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { explainDnd = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (pickTime) {
        ReminderTimeDialog(
            initial = settings.reminderTime,
            is24Hour = DateFormat.is24HourFormat(context),
            onDismiss = { pickTime = false },
            onPick = { time ->
                pickTime = false
                features.setReminderTime(time)
            },
        )
    }
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderTimeDialog(initial: LocalTime, is24Hour: Boolean, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = is24Hour)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notify_reminder_time_title)) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }) {
                Text(stringResource(R.string.notify_reminder_time_set))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** The time as the phone shows times, 24-hour or not. */
private fun formatTime(context: Context, time: LocalTime): String {
    val instant = time.atDate(java.time.LocalDate.now()).atZone(ZoneId.systemDefault()).toInstant()
    return DateFormat.getTimeFormat(context).format(Date.from(instant))
}

private fun dndAccessGranted(context: Context): Boolean =
    context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

private fun Context.openAppNotificationSettings() {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
}
