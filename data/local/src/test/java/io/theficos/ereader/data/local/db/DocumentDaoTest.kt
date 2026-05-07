package io.theficos.ereader.data.local.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
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

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), EReaderDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.documentDao()
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
}
