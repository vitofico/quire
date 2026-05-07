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

    private fun newDoc(): Long = kotlinx.coroutines.runBlocking {
        docs.insert(DocumentEntity(metadataId = null, contentHash = "h", title = "t", author = null, downloadUrl = "u", localPath = "p", coverPath = null, downloadedAt = 0))
    }

    @Test fun `upsert replaces previous progress for same document`() = runTest {
        val docId = newDoc()
        dao.upsert(ProgressEntity(documentId = docId, locator = "loc1", percent = 0.1, updatedAt = 1, localUpdatedAt = 1, syncedAt = 0))
        dao.upsert(ProgressEntity(documentId = docId, locator = "loc2", percent = 0.5, updatedAt = 2, localUpdatedAt = 2, syncedAt = 0))
        val found = dao.findByDocument(docId)
        assertThat(found?.locator).isEqualTo("loc2")
    }

    @Test fun `dirty returns rows where localUpdatedAt greater than syncedAt`() = runTest {
        val a = newDoc()
        val b = kotlinx.coroutines.runBlocking { docs.insert(DocumentEntity(metadataId = null, contentHash = "h2", title = "t2", author = null, downloadUrl = "u", localPath = "p", coverPath = null, downloadedAt = 0)) }
        dao.upsert(ProgressEntity(documentId = a, locator = "x", percent = 0.1, updatedAt = 1, localUpdatedAt = 5, syncedAt = 5))
        dao.upsert(ProgressEntity(documentId = b, locator = "y", percent = 0.2, updatedAt = 1, localUpdatedAt = 6, syncedAt = 5))
        val dirty = dao.dirty()
        assertThat(dirty.map { it.documentId }).containsExactly(b)
    }

    @Test fun `markSynced sets syncedAt`() = runTest {
        val docId = newDoc()
        dao.upsert(ProgressEntity(documentId = docId, locator = "x", percent = 0.1, updatedAt = 1, localUpdatedAt = 5, syncedAt = 0))
        dao.markSynced(docId, 5)
        val found = dao.findByDocument(docId)
        assertThat(found?.syncedAt).isEqualTo(5)
    }

    @Test fun `flow emits updates`() = runTest {
        val docId = newDoc()
        dao.observeByDocument(docId).test {
            assertThat(awaitItem()).isNull()
            dao.upsert(ProgressEntity(documentId = docId, locator = "x", percent = 0.2, updatedAt = 1, localUpdatedAt = 1, syncedAt = 0))
            assertThat(awaitItem()?.locator).isEqualTo("x")
            cancelAndIgnoreRemainingEvents()
        }
    }
}
