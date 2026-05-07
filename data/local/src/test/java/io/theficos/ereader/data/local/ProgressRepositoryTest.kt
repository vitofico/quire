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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProgressRepositoryTest {
    private lateinit var db: EReaderDatabase
    private lateinit var repo: ProgressRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), EReaderDatabase::class.java
        ).allowMainThreadQueries().build()
        repo = ProgressRepository(db.progressDao())
    }

    @After fun tearDown() = db.close()

    private suspend fun seedDoc(): Long = db.documentDao().insert(DocumentEntity(
        metadataId = "m1", contentHash = "h1", title = "t", author = null,
        downloadUrl = "u", localPath = "p", coverPath = null, downloadedAt = 0,
    ))

    @Test fun `resetForDocument writes a dirty zero-progress row`() = runTest {
        val docId = seedDoc()
        db.progressDao().upsert(ProgressEntity(
            documentId = docId, locator = "old", percent = 0.42,
            updatedAt = 100L, localUpdatedAt = 100L, syncedAt = 100L,
        ))

        repo.resetForDocument(docId, now = 999L)

        val row = db.progressDao().findByDocument(docId)!!
        assertThat(row.locator).isEmpty()
        assertThat(row.percent).isEqualTo(0.0)
        assertThat(row.updatedAt).isEqualTo(999L)
        assertThat(row.localUpdatedAt).isEqualTo(999L)
        assertThat(row.syncedAt).isEqualTo(0L)
    }

    @Test fun `resetForDocument seeds a row when none exists`() = runTest {
        val docId = seedDoc()

        repo.resetForDocument(docId, now = 42L)

        val row = db.progressDao().findByDocument(docId)!!
        assertThat(row.locator).isEmpty()
        assertThat(row.percent).isEqualTo(0.0)
        assertThat(row.updatedAt).isEqualTo(42L)
    }
}
