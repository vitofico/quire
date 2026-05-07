package io.theficos.ereader.data.local.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
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
class ProgressDaoTest {
    private lateinit var db: EReaderDatabase
    private lateinit var docs: DocumentDao
    private lateinit var dao: ProgressDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), EReaderDatabase::class.java
        ).allowMainThreadQueries().build()
        docs = db.documentDao()
        dao = db.progressDao()
    }

    @After fun tearDown() { db.close() }

    @Test fun `upsert replaces previous progress for same document`() = runTest {
        val docId = docs.insert(DocumentEntity(metadataId = null, contentHash = "h", title = "t", author = null, downloadUrl = "u", localPath = "p", downloadedAt = 0))
        dao.upsert(ProgressEntity(documentId = docId, locator = "loc1", percent = 0.1, updatedAt = 1))
        dao.upsert(ProgressEntity(documentId = docId, locator = "loc2", percent = 0.5, updatedAt = 2))
        val found = dao.findByDocument(docId)
        assertThat(found?.locator).isEqualTo("loc2")
        assertThat(found?.percent).isEqualTo(0.5)
    }

    @Test fun `flow emits updates`() = runTest {
        val docId = docs.insert(DocumentEntity(metadataId = null, contentHash = "h", title = "t", author = null, downloadUrl = "u", localPath = "p", downloadedAt = 0))
        dao.observeByDocument(docId).test {
            assertThat(awaitItem()).isNull()
            dao.upsert(ProgressEntity(documentId = docId, locator = "x", percent = 0.2, updatedAt = 1))
            assertThat(awaitItem()?.locator).isEqualTo("x")
            cancelAndIgnoreRemainingEvents()
        }
    }
}
