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

    @Test fun `upsert round-trips finishedAt`() = runTest {
        val docId = newDoc()
        dao.upsert(ProgressEntity(
            documentId = docId, locator = "x", percent = 0.99,
            updatedAt = 1, localUpdatedAt = 1, syncedAt = 0,
            finishedAt = 1234L,
        ))
        val found = dao.findByDocument(docId)
        assertThat(found?.finishedAt).isEqualTo(1234L)
    }

    @Test fun `upsert allows null finishedAt`() = runTest {
        val docId = newDoc()
        dao.upsert(ProgressEntity(
            documentId = docId, locator = "x", percent = 0.5,
            updatedAt = 1, localUpdatedAt = 1, syncedAt = 0,
            finishedAt = null,
        ))
        val found = dao.findByDocument(docId)
        assertThat(found?.finishedAt).isNull()
    }

    @Test fun `MIGRATION_3_4 sql adds nullable finishedAt column`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "migration-3to4-test.db"
        ctx.deleteDatabase(dbName)
        val sqlite = ctx.openOrCreateDatabase(dbName, android.content.Context.MODE_PRIVATE, null)
        try {
            sqlite.execSQL(
                "CREATE TABLE progress (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "documentId INTEGER NOT NULL, " +
                    "locator TEXT NOT NULL, " +
                    "percent REAL NOT NULL, " +
                    "updatedAt INTEGER NOT NULL, " +
                    "localUpdatedAt INTEGER NOT NULL DEFAULT 0, " +
                    "syncedAt INTEGER NOT NULL DEFAULT 0)"
            )
            sqlite.execSQL("INSERT INTO progress (documentId, locator, percent, updatedAt, localUpdatedAt, syncedAt) VALUES (1, '', 0.0, 0, 0, 0)")

            // Apply the same SQL the production migration runs.
            sqlite.execSQL("ALTER TABLE progress ADD COLUMN finishedAt INTEGER")

            sqlite.rawQuery("PRAGMA table_info(progress)", null).use { c ->
                val cols = mutableListOf<String>()
                while (c.moveToNext()) cols += c.getString(c.getColumnIndexOrThrow("name"))
                assertThat(cols).contains("finishedAt")
            }
            // Pre-existing rows tolerate NULL for the new column.
            sqlite.rawQuery("SELECT finishedAt FROM progress", null).use { c ->
                assertThat(c.moveToNext()).isTrue()
                assertThat(c.isNull(0)).isTrue()
            }
        } finally {
            sqlite.close()
            ctx.deleteDatabase(dbName)
        }
    }
}
