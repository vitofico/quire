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
class MemoryBookmarkTest {
    private lateinit var db: EReaderDatabase
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun open(): EReaderDatabase = Room.databaseBuilder(context, EReaderDatabase::class.java, "memory-test.db")
        .allowMainThreadQueries().build().also { it.openHelper.writableDatabase }
    @Before fun setup() { context.deleteDatabase("memory-test.db"); db = open() }
    @After fun cleanup() { db.close(); context.deleteDatabase("memory-test.db") }
    private suspend fun document(hash: String) = db.documentDao().insert(DocumentEntity(
        metadataId = null, contentHash = hash, title = "Book", author = null,
        downloadUrl = "", localPath = "", coverPath = null, downloadedAt = 0))

    @Test fun `restart preserves note passage and locator without changing progress sync state`() = runTest {
        val id = document("one")
        val progress = ProgressEntity(documentId = id, locator = "progress", percent = 0.4,
            updatedAt = 4, localUpdatedAt = 4, syncedAt = 4)
        db.progressDao().upsert(progress)
        val before = db.progressDao().findByDocument(id)
        val highlight = HighlightEntity("highlight", null, "one", locator = "exact-highlight", text = "Selected quote",
            colour = "YELLOW", bookTitle = "Book", bookAuthor = null, createdAt = 30, updatedAt = 40,
            ownerScope = "owner", syncedAt = 40)
        db.highlightDao().insert(highlight)
        val bookmark = MemoryBookmark(id, "owner", "{locator}", "Why does she avoid this?", "Nearby text", 50)
        db.memoryBookmarkDao().save(bookmark)
        db.close(); db = open()
        assertThat(db.memoryBookmarkDao().observe(id, "owner").first()).isEqualTo(bookmark)
        assertThat(db.progressDao().findByDocument(id)).isEqualTo(before)
        assertThat(db.progressDao().dirty()).isEmpty()
        assertThat(db.highlightDao().findById("highlight", "owner")).isEqualTo(highlight)
        assertThat(db.highlightSyncDao().dirty("owner")).isEmpty()
        db.highlightDao().setColour("highlight", "GREEN", 50, "owner")
        assertThat(db.memoryBookmarkDao().observe(id, "owner").first()).isEqualTo(bookmark)
        assertThat(db.highlightSyncDao().dirty("owner").single().colour).isEqualTo("GREEN")
    }

    @Test fun `books accounts edits and removals are isolated and corrupt locators remain recoverable`() = runTest {
        val one = document("one"); val two = document("two")
        val a = MemoryBookmark(one, "a", "not valid JSON", "Keep me", "passage", 10)
        val b = a.copy(accountKey = "b", note = "Other account")
        val c = a.copy(documentId = two, note = "Other book")
        listOf(a, b, c).forEach { db.memoryBookmarkDao().save(it) }
        assertThat(db.memoryBookmarkDao().observe(one, "a").first()).isEqualTo(a)
        db.memoryBookmarkDao().save(a.copy(note = "Edited", updatedAt = 11))
        assertThat(db.memoryBookmarkDao().observe(one, "a").first()?.locator).isEqualTo("not valid JSON")
        db.memoryBookmarkDao().remove(one, "a")
        assertThat(db.memoryBookmarkDao().observe(one, "a").first()).isNull()
        assertThat(db.memoryBookmarkDao().observe(one, "b").first()).isEqualTo(b)
        assertThat(db.memoryBookmarkDao().observe(two, "a").first()).isEqualTo(c)
    }

    @Test fun `removing book cascades bookmarks for every account`() = runTest {
        val id = document("one")
        listOf("a", "b").forEach { db.memoryBookmarkDao().save(MemoryBookmark(id, it, "x", "note", "text", 1)) }
        db.openHelper.writableDatabase.execSQL("DELETE FROM documents WHERE id = ?", arrayOf(id))
        assertThat(db.memoryBookmarkDao().observe(id, "a").first()).isNull()
        assertThat(db.memoryBookmarkDao().observe(id, "b").first()).isNull()
    }
}
