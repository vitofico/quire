package io.theficos.ereader.data.local.db

import android.database.sqlite.SQLiteConstraintException
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
class HighlightDaoTest {
    private lateinit var db: EReaderDatabase
    private lateinit var dao: HighlightDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EReaderDatabase::class.java)
            .allowMainThreadQueries().build()
        db.openHelper.writableDatabase
        dao = db.highlightDao()
    }

    @After fun tearDown() = db.close()

    private fun row(id: String, metadataId: String? = "metadata", hash: String = "hash") = HighlightEntity(
        id = id, metadataId = metadataId, contentHash = hash, locator = "locator", text = "quote",
        colour = "YELLOW", bookTitle = "Title", bookAuthor = null, createdAt = 10, updatedAt = 10,
    )

    @Test fun `OR identity lookup deduplicates matching hints and excludes tombstones`() = runTest {
        dao.insert(row("both"))
        dao.insert(row("metadata-only", hash = "old-hash"))
        dao.insert(row("hash-only", metadataId = null))
        dao.insert(row("unrelated", metadataId = "other", hash = "other"))
        dao.insert(row("deleted").copy(deletedAt = 12, updatedAt = 12))

        assertThat(dao.observeActive("metadata", "hash").first().map { it.id })
            .containsExactly("both", "hash-only", "metadata-only").inOrder()
        assertThat(dao.observeActive(null, null).first()).isEmpty()
    }

    @Test fun `a conflicting UUID insert cannot overwrite or revive a deleted annotation`() = runTest {
        dao.insert(row("same"))
        dao.softDelete("same", 20)
        val tombstone = dao.findById("same")!!
        val failure = runCatching { dao.insert(row("same").copy(text = "replacement")) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SQLiteConstraintException::class.java)
        assertThat(dao.findById("same")).isEqualTo(tombstone)
        assertThat(dao.observeActive("metadata", "hash").first()).isEmpty()
    }
}
