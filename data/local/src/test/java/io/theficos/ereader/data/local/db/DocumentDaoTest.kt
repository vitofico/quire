package io.theficos.ereader.data.local.db

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
class DocumentDaoTest {
    private lateinit var db: EReaderDatabase
    private lateinit var dao: DocumentDao
    private lateinit var progressDao: ProgressDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), EReaderDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.documentDao()
        progressDao = db.progressDao()
    }

    @After fun tearDown() { db.close() }

    @Test fun `insert and lookup by metadata id`() = runTest {
        val rowId = dao.insert(DocumentEntity(
            metadataId = "42", contentHash = "abc", title = "T", author = "A",
            downloadUrl = "https://x/y.epub", localPath = "/tmp/y.epub",
            coverPath = null, downloadedAt = 1L
        ))
        val found = dao.findByMetadataId("42")
        assertThat(found?.id).isEqualTo(rowId)
        assertThat(found?.title).isEqualTo("T")
    }

    @Test fun `insert and lookup by content hash`() = runTest {
        dao.insert(DocumentEntity(
            metadataId = null, contentHash = "abc", title = "T", author = null,
            downloadUrl = "u", localPath = "p", coverPath = null, downloadedAt = 1L
        ))
        assertThat(dao.findByContentHash("abc")?.contentHash).isEqualTo("abc")
    }

    @Test fun `unique constraint on metadata id`() = runTest {
        dao.insert(DocumentEntity(metadataId = "42", contentHash = "h1", title = "a", author = null, downloadUrl = "u1", localPath = "p1", coverPath = null, downloadedAt = 1))
        try {
            dao.insert(DocumentEntity(metadataId = "42", contentHash = "h2", title = "b", author = null, downloadUrl = "u2", localPath = "p2", coverPath = null, downloadedAt = 2))
            org.junit.Assert.fail("expected SQLiteConstraintException for duplicate metadataId")
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            // ok
        }
    }

    @Test fun `insert with coverPath round-trips`() = runTest {
        val id = dao.insert(DocumentEntity(
            metadataId = "id-cover",
            contentHash = "hash-cover",
            title = "T",
            author = null,
            downloadUrl = "http://x/y.epub",
            localPath = "/tmp/y.epub",
            coverPath = "/tmp/y.jpg",
            downloadedAt = 0L,
        ))
        val row = dao.findById(id)
        assertThat(row).isNotNull()
        assertThat(row!!.coverPath).isEqualTo("/tmp/y.jpg")
    }

    // ---- observeSeriesContinuationCandidates (PR8) ----

    private suspend fun insertBook(
        contentHash: String,
        title: String,
        seriesName: String? = null,
        seriesIndex: Double? = null,
    ): Long = dao.insert(DocumentEntity(
        metadataId = contentHash,
        contentHash = contentHash,
        title = title,
        author = null,
        downloadUrl = "u",
        localPath = "p",
        coverPath = null,
        downloadedAt = 0L,
        seriesName = seriesName,
        seriesIndex = seriesIndex,
    ))

    private suspend fun setProgress(
        documentId: Long,
        percent: Double = 0.0,
        finishedAt: Long? = null,
        updatedAt: Long = 0L,
    ) {
        progressDao.upsert(ProgressEntity(
            documentId = documentId,
            locator = "loc",
            percent = percent,
            updatedAt = updatedAt,
            localUpdatedAt = updatedAt,
            syncedAt = 0L,
            finishedAt = finishedAt,
        ))
    }

    private suspend fun shelf(): List<DocumentEntity> =
        dao.observeSeriesContinuationCandidates(startedThreshold = 0.05, maxItems = 12).first()

    @Test fun `shelf includes unread sibling when another book in the series is finished`() = runTest {
        val finished = insertBook("h1", "Foundation", seriesName = "Foundation", seriesIndex = 1.0)
        val candidate = insertBook("h2", "Foundation and Empire", seriesName = "Foundation", seriesIndex = 2.0)
        setProgress(finished, percent = 1.0, finishedAt = 100L)

        val result = shelf()

        assertThat(result.map { it.id }).containsExactly(candidate)
    }

    @Test fun `shelf is empty when nothing is finished`() = runTest {
        insertBook("h1", "Foundation", seriesName = "Foundation", seriesIndex = 1.0)
        insertBook("h2", "Foundation and Empire", seriesName = "Foundation", seriesIndex = 2.0)

        assertThat(shelf()).isEmpty()
    }

    @Test fun `shelf excludes the candidate when the user already finished it`() = runTest {
        val finished = insertBook("h1", "Foundation", seriesName = "Foundation", seriesIndex = 1.0)
        val also = insertBook("h2", "Foundation and Empire", seriesName = "Foundation", seriesIndex = 2.0)
        setProgress(finished, percent = 1.0, finishedAt = 100L)
        setProgress(also, percent = 1.0, finishedAt = 200L)

        assertThat(shelf()).isEmpty()
    }

    @Test fun `shelf excludes the candidate when the user has started past the threshold`() = runTest {
        val finished = insertBook("h1", "Foundation", seriesName = "Foundation", seriesIndex = 1.0)
        val started = insertBook("h2", "Foundation and Empire", seriesName = "Foundation", seriesIndex = 2.0)
        setProgress(finished, percent = 1.0, finishedAt = 100L)
        setProgress(started, percent = 0.5, updatedAt = 150L)

        assertThat(shelf()).isEmpty()
    }

    @Test fun `shelf includes a candidate barely below the started threshold`() = runTest {
        val finished = insertBook("h1", "Foundation", seriesName = "Foundation", seriesIndex = 1.0)
        val barely = insertBook("h2", "Foundation and Empire", seriesName = "Foundation", seriesIndex = 2.0)
        setProgress(finished, percent = 1.0, finishedAt = 100L)
        // Below the 5% threshold and not finished: still eligible.
        setProgress(barely, percent = 0.02, updatedAt = 150L)

        assertThat(shelf().map { it.id }).containsExactly(barely)
    }

    @Test fun `shelf matches series name case-insensitively`() = runTest {
        val finished = insertBook("h1", "foundation v1", seriesName = "foundation", seriesIndex = 1.0)
        val candidate = insertBook("h2", "Foundation v2", seriesName = "Foundation", seriesIndex = 2.0)
        setProgress(finished, percent = 1.0, finishedAt = 100L)

        assertThat(shelf().map { it.id }).containsExactly(candidate)
    }

    @Test fun `shelf surfaces multiple series at once, ordered by most recently finished sibling`() = runTest {
        val foundationA = insertBook("hf1", "Foundation 1", seriesName = "Foundation", seriesIndex = 1.0)
        val foundationB = insertBook("hf2", "Foundation 2", seriesName = "Foundation", seriesIndex = 2.0)
        val duneA = insertBook("hd1", "Dune 1", seriesName = "Dune", seriesIndex = 1.0)
        val duneB = insertBook("hd2", "Dune 2", seriesName = "Dune", seriesIndex = 2.0)
        setProgress(duneA, percent = 1.0, finishedAt = 100L)         // older finish
        setProgress(foundationA, percent = 1.0, finishedAt = 500L)   // newer finish

        val ids = shelf().map { it.id }
        assertThat(ids).containsExactly(foundationB, duneB).inOrder()
    }

    @Test fun `shelf orders within a series by seriesIndex ASC`() = runTest {
        val finished = insertBook("h0", "Foundation 0", seriesName = "Foundation", seriesIndex = 0.0)
        setProgress(finished, percent = 1.0, finishedAt = 100L)
        val b3 = insertBook("h3", "Foundation 3", seriesName = "Foundation", seriesIndex = 3.0)
        val b1 = insertBook("h1", "Foundation 1", seriesName = "Foundation", seriesIndex = 1.0)
        val b2 = insertBook("h2", "Foundation 2", seriesName = "Foundation", seriesIndex = 2.0)

        val ids = shelf().map { it.id }
        assertThat(ids).containsExactly(b1, b2, b3).inOrder()
    }

    @Test fun `shelf pushes NULL seriesIndex to the end within a series`() = runTest {
        val finished = insertBook("h0", "Foundation 0", seriesName = "Foundation", seriesIndex = 0.0)
        setProgress(finished, percent = 1.0, finishedAt = 100L)
        val noIdx = insertBook("hnone", "Foundation Bonus", seriesName = "Foundation", seriesIndex = null)
        val b1 = insertBook("h1", "Foundation 1", seriesName = "Foundation", seriesIndex = 1.0)

        val ids = shelf().map { it.id }
        assertThat(ids).containsExactly(b1, noIdx).inOrder()
    }

    @Test fun `shelf caps the result list`() = runTest {
        val finished = insertBook("hfin", "Series A 1", seriesName = "Series A", seriesIndex = 1.0)
        setProgress(finished, percent = 1.0, finishedAt = 100L)
        repeat(15) { i ->
            insertBook("h${i + 2}", "Series A ${i + 2}", seriesName = "Series A", seriesIndex = (i + 2).toDouble())
        }

        assertThat(shelf()).hasSize(12)
    }

    @Test fun `books with NULL seriesName never appear on the shelf`() = runTest {
        val finished = insertBook("hf1", "Foundation", seriesName = "Foundation", seriesIndex = 1.0)
        setProgress(finished, percent = 1.0, finishedAt = 100L)
        insertBook("hbare", "Standalone novel", seriesName = null, seriesIndex = null)
        insertBook("hempty", "Series-less", seriesName = "", seriesIndex = null)
        val candidate = insertBook("hf2", "Foundation 2", seriesName = "Foundation", seriesIndex = 2.0)

        assertThat(shelf().map { it.id }).containsExactly(candidate)
    }

    @Test fun `shelf is reactive to a new finish in a sibling book`() = runTest {
        val a = insertBook("h1", "Foundation 1", seriesName = "Foundation", seriesIndex = 1.0)
        val b = insertBook("h2", "Foundation 2", seriesName = "Foundation", seriesIndex = 2.0)
        // Initially nothing finished → empty shelf.
        assertThat(shelf()).isEmpty()
        // Mark book 1 finished → book 2 should now appear.
        setProgress(a, percent = 1.0, finishedAt = 100L)
        assertThat(shelf().map { it.id }).containsExactly(b)
    }
}
