package io.theficos.ereader.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.data.local.db.DocumentEntity
import io.theficos.ereader.data.local.db.EReaderDatabase
import io.theficos.ereader.data.local.db.ProgressEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DocumentRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: EReaderDatabase
    private lateinit var repo: DocumentRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), EReaderDatabase::class.java
        ).allowMainThreadQueries().build()
        repo = DocumentRepository(db.documentDao())
    }

    @After fun tearDown() = db.close()

    @Test fun `deleteAll wipes documents, cascades progress, and clears books dir`() = runTest {
        val booksDir = tmp.newFolder("books")
        val epub1 = File(booksDir, "a.epub").apply { writeText("a") }
        val epub2 = File(booksDir, "b.epub").apply { writeText("b") }

        val id1 = db.documentDao().insert(DocumentEntity(
            metadataId = "m1", contentHash = "h1", title = "t1", author = null,
            downloadUrl = "u1", localPath = epub1.path, coverPath = null, downloadedAt = 0,
        ))
        val id2 = db.documentDao().insert(DocumentEntity(
            metadataId = "m2", contentHash = "h2", title = "t2", author = null,
            downloadUrl = "u2", localPath = epub2.path, coverPath = null, downloadedAt = 0,
        ))
        db.progressDao().upsert(ProgressEntity(
            documentId = id1, locator = "x", percent = 0.5,
            updatedAt = 1, localUpdatedAt = 1, syncedAt = 1,
        ))
        db.progressDao().upsert(ProgressEntity(
            documentId = id2, locator = "y", percent = 0.5,
            updatedAt = 1, localUpdatedAt = 1, syncedAt = 1,
        ))

        repo.deleteAll(booksDir)

        assertThat(db.documentDao().findById(id1)).isNull()
        assertThat(db.documentDao().findById(id2)).isNull()
        assertThat(db.progressDao().findByDocument(id1)).isNull()
        assertThat(db.progressDao().findByDocument(id2)).isNull()
        assertThat(booksDir.exists()).isTrue()
        assertThat(booksDir.listFiles()).isEmpty()
    }

    @Test fun `deleteAll tolerates a missing books dir`() = runTest {
        val missing = File(tmp.root, "does-not-exist")
        // No throw; DB delete still applies.
        db.documentDao().insert(DocumentEntity(
            metadataId = "m1", contentHash = "h1", title = "t", author = null,
            downloadUrl = "u", localPath = "p", coverPath = null, downloadedAt = 0,
        ))

        repo.deleteAll(missing)

        assertThat(db.documentDao().findByMetadataId("m1")).isNull()
    }
}
