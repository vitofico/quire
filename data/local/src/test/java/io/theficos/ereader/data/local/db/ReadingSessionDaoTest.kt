package io.theficos.ereader.data.local.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReadingSessionDaoTest {
    private lateinit var db: EReaderDatabase
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dao get() = db.readingSessionDao()

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, EReaderDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun cleanup() = db.close()

    private fun session(bookKey: String, startedAt: Long) = ReadingSessionEntity(
        bookKey = bookKey, title = "Book $bookKey", author = null, startedAt = startedAt,
        endedAt = startedAt + 60_000, utcOffsetSeconds = 0, activeSeconds = 60,
        progressionRead = 0.02, wordsRead = 200,
    )

    @Test fun `a removed book keeps its sessions until statistics are cleared`() = runTest {
        val id = db.documentDao().insert(DocumentEntity(
            metadataId = null, contentHash = "hash", title = "Book", author = null,
            downloadUrl = "", localPath = "", coverPath = null, downloadedAt = 0))
        db.progressDao().upsert(ProgressEntity(documentId = id, locator = "l", percent = 0.4,
            updatedAt = 1, localUpdatedAt = 1, syncedAt = 0, finishedAt = 9))
        dao.insert(session("hash", startedAt = 2_000))
        dao.insert(session("hash", startedAt = 1_000))

        assertThat(dao.observeBookProgress().first()).containsExactly(BookProgressRow("hash", 0.4, 9, null))
        db.documentDao().deleteById(id)

        assertThat(dao.observeBookProgress().first()).isEmpty()
        assertThat(dao.observeAll().first().map { it.startedAt }).containsExactly(1_000L, 2_000L).inOrder()
        dao.clear()
        assertThat(dao.observeAll().first()).isEmpty()
    }

    @Test fun `a word count is kept per book and replaced on recount`() = runTest {
        assertThat(dao.wordCount("hash")).isNull()
        dao.saveWordCount(BookWordCountEntity("hash", 1_000))
        dao.saveWordCount(BookWordCountEntity("hash", 1_200))
        assertThat(dao.wordCount("hash")).isEqualTo(1_200L)
        assertThat(dao.observeWordCounts().first()).containsExactly(BookWordCountEntity("hash", 1_200))
    }
}
