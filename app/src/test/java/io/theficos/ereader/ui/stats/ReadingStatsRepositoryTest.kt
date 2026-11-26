package io.theficos.ereader.ui.stats

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.data.local.db.EReaderDatabase
import io.theficos.ereader.ui.reader.RecordedSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ReadingStatsRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, EReaderDatabase::class.java).allowMainThreadQueries().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository = ReadingStatsRepository(
        db.readingSessionDao(),
        context.getSharedPreferences("stats-test", Context.MODE_PRIVATE),
        scope,
    )
    private val book = StatsBook("hash", "Book", null)
    private val session = RecordedSession(startedAt = 0, endedAt = 60_000, utcOffsetSeconds = 0, progressionRead = 0.01)

    /** The background writes [ReadingStatsRepository.record] started, finished. */
    private fun writes(): List<Job> = scope.coroutineContext[Job]!!.children.toList().also { jobs ->
        runBlocking { jobs.forEach { it.join() } }
    }

    @After fun cleanup() {
        if (db.isOpen) db.close()
    }

    @Test fun `with recording off a session is dropped and no word count is made`() = runBlocking {
        repository.setRecording(false)
        var counts = 0
        repository.record(book, session)
        repository.ensureWordCount(book.key) { counts++; 1_000L }
        writes()

        assertThat(repository.sessions.first()).isEmpty()
        assertThat(repository.wordCounts.first()).isEmpty()
        assertThat(counts).isEqualTo(0)
    }

    @Test fun `with recording on the session is stored with its words`() = runBlocking {
        repository.setRecording(true)
        repository.ensureWordCount(book.key) { 100_000L }
        repository.record(book, session)
        writes()

        assertThat(repository.sessions.first().single().wordsRead).isEqualTo(1_000L)
    }

    @Test fun `a failed write is logged, not thrown`() = runBlocking {
        repository.setRecording(true)
        db.close()
        repository.record(book, session)
        assertThat(writes().none { it.isCancelled }).isTrue()
        repository.clear()
    }
}
