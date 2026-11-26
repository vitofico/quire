package io.theficos.ereader.ui.stats

import android.content.SharedPreferences
import android.util.Log
import io.theficos.ereader.core.model.Document
import io.theficos.ereader.core.model.Progress
import io.theficos.ereader.data.local.db.BookProgressRow
import io.theficos.ereader.data.local.db.BookWordCountEntity
import io.theficos.ereader.data.local.db.ReadingSessionDao
import io.theficos.ereader.data.local.db.ReadingSessionEntity
import io.theficos.ereader.ui.reader.RecordedSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

/** The book a session belongs to: its content hash, and its title and author as they were. */
data class StatsBook(val key: String, val title: String, val author: String?)

/** The key a book's sessions are stored under: its content, so a re-added copy still matches. */
val Document.statsKey: String get() = identity.contentHash ?: "document:$id"

/** What the reader hands its finished sessions to. */
interface ReadingStatsSink {
    /** Stores [session] in the background, unless recording is off. Returns at once. */
    fun record(book: StatsBook, session: RecordedSession)

    /** Counts the book's words with [count] the first time it is opened. */
    suspend fun ensureWordCount(bookKey: String, count: suspend () -> Long?)
}

/**
 * Reading statistics on this device: the sessions the reader recorded, the word counts their
 * speeds come from, and the "Record reading statistics" setting (on unless turned off).
 * Writes run on [scope], an application scope, so a session ended by closing the book is
 * still stored after the reader's view model has gone.
 */
class ReadingStatsRepository(
    private val dao: ReadingSessionDao,
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
) : ReadingStatsSink {
    private val _recording = MutableStateFlow(prefs.getBoolean(KEY_RECORDING, true))
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    val sessions: Flow<List<ReadingSessionEntity>> = dao.observeAll()
    val wordCounts: Flow<List<BookWordCountEntity>> = dao.observeWordCounts()
    val bookProgress: Flow<List<BookProgressRow>> = dao.observeBookProgress()

    fun setRecording(on: Boolean) {
        prefs.edit().putBoolean(KEY_RECORDING, on).apply()
        _recording.value = on
    }

    suspend fun clear() {
        runCatching { dao.clear() }.onFailure { failed("clear", it) }
    }

    /** About how long the rest of the book with [bookKey] takes at the reader's pace. */
    fun secondsLeft(bookKey: String, progress: Flow<Progress?>): Flow<Long?> =
        combine(sessions, wordCounts, progress) { all, counts, current ->
            current ?: return@combine null
            secondsLeft(
                progress = BookProgressRow(bookKey, current.percent, current.finishedAt, current.abandonedAt),
                words = counts.firstOrNull { it.bookKey == bookKey }?.words,
                bookSessions = all.filter { it.bookKey == bookKey },
                overallWpm = wordsPerMinute(all),
            )
        }.distinctUntilChanged()

    override fun record(book: StatsBook, session: RecordedSession) {
        if (!_recording.value) return
        scope.launch {
            runCatching {
                val words = dao.wordCount(book.key)?.let { (session.progressionRead * it).roundToLong() }
                dao.insert(ReadingSessionEntity(
                    bookKey = book.key,
                    title = book.title,
                    author = book.author,
                    startedAt = session.startedAt,
                    endedAt = session.endedAt,
                    utcOffsetSeconds = session.utcOffsetSeconds,
                    activeSeconds = session.activeSeconds,
                    progressionRead = session.progressionRead,
                    wordsRead = words,
                ))
            }.onFailure { failed("store a session", it) }
        }
    }

    override suspend fun ensureWordCount(bookKey: String, count: suspend () -> Long?) {
        if (!_recording.value || dao.wordCount(bookKey) != null) return
        val words = count() ?: return
        dao.saveWordCount(BookWordCountEntity(bookKey, words))
    }

    /** Statistics are a nicety: a full disk or a broken database must not take the app down. */
    private fun failed(what: String, error: Throwable) {
        if (error is CancellationException) throw error
        Log.w(TAG, "Could not $what", error)
    }

    private companion object {
        const val KEY_RECORDING = "record_reading_statistics"
        const val TAG = "ReadingStats"
    }
}
