package io.theficos.ereader.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.theficos.ereader.MainActivity
import io.theficos.ereader.auth.SavedCatalog
import io.theficos.ereader.data.opds.OpdsPublication
import io.theficos.quire.R

/** Where a tapped notification takes the reader. */
sealed interface OpenRequest {
    data class Book(val id: Long) : OpenRequest
    data class Catalog(val id: String) : OpenRequest
}

/** The [OpenRequest] a notification's tap carried, or null for any other launch. */
fun Intent.openRequest(): OpenRequest? {
    if (flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
    getStringExtra(EXTRA_CATALOG_ID)?.let { return OpenRequest.Catalog(it) }
    val book = getLongExtra(EXTRA_BOOK_ID, -1L)
    return if (book >= 0) OpenRequest.Book(book) else null
}

private const val EXTRA_CATALOG_ID = "io.theficos.quire.extra.CATALOG_ID"
private const val EXTRA_BOOK_ID = "io.theficos.quire.extra.BOOK_ID"

/**
 * Posts the two kinds of notification. Each channel is created right before its first
 * notification, so a reader who never turns a feature on never sees its channel in the system
 * settings. Text carries titles and the catalog's name, never an address.
 */
internal object Notifier {
    private const val CHANNEL_NEW_BOOKS = "quire-new-books"
    private const val CHANNEL_REMINDER = "quire-reading-reminder"
    private const val ID_NEW_BOOKS = 4301
    private const val ID_REMINDER = 4302

    /** True when Quire may post: always before Android 13, otherwise once the reader allowed it. */
    fun canPost(context: Context): Boolean {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return allowed && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun showNewBooks(context: Context, catalog: SavedCatalog, added: List<OpdsPublication>) {
        if (!canPost(context) || added.isEmpty()) return
        val res = context.resources
        val title = res.getQuantityString(R.plurals.notify_new_books_title, added.size, added.size, catalog.name)
        val first = added.first().title
        val text = if (added.size == 1) first
        else res.getQuantityString(R.plurals.notify_new_books_more, added.size - 1, first, added.size - 1)
        ensureChannel(context, CHANNEL_NEW_BOOKS, R.string.notify_channel_new_books, NotificationManager.IMPORTANCE_DEFAULT)
        val notification = NotificationCompat.Builder(context, CHANNEL_NEW_BOOKS)
            .setSmallIcon(R.drawable.quire_mark)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open(context, catalog.id.hashCode()) { putExtra(EXTRA_CATALOG_ID, catalog.id) })
            .setAutoCancel(true)
            .build()
        post(context, catalog.id, ID_NEW_BOOKS, notification)
    }

    fun showReminder(context: Context, bookId: Long?, bookTitle: String?) {
        if (!canPost(context)) return
        val text = bookTitle?.let { context.getString(R.string.notify_reminder_text_book, it) }
            ?: context.getString(R.string.notify_reminder_text)
        ensureChannel(context, CHANNEL_REMINDER, R.string.notify_channel_reminder, NotificationManager.IMPORTANCE_LOW)
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDER)
            .setSmallIcon(R.drawable.quire_mark)
            .setContentTitle(context.getString(R.string.notify_reminder_title))
            .setContentText(text)
            .setContentIntent(open(context, ID_REMINDER) { if (bookId != null) putExtra(EXTRA_BOOK_ID, bookId) })
            .setAutoCancel(true)
            .build()
        post(context, null, ID_REMINDER, notification)
    }

    private fun post(context: Context, tag: String?, id: Int, notification: android.app.Notification) {
        try {
            NotificationManagerCompat.from(context).notify(tag, id, notification)
        } catch (_: SecurityException) {
            // The permission was withdrawn after canPost: nothing to show.
        }
    }

    private fun ensureChannel(context: Context, id: String, name: Int, importance: Int) {
        // minSdk is 26, so the channel API is always available. Re-creating keeps the reader's choices.
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(id, context.getString(name), importance))
    }

    private fun open(context: Context, requestCode: Int, extras: Intent.() -> Unit): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply(extras)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
