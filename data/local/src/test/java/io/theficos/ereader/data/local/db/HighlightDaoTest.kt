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
    @Test fun `atomic scoped writes cannot edit or delete another account's annotation`() = runTest {
        dao.insert(row("same").copy(ownerScope = "account-a"))
        dao.insert(row("same").copy(ownerScope = "account-b", text = "B quote"))
        dao.setColour("same", "PINK", 20, "account-b")
        dao.softDelete("same", 21, "account-b")
        assertThat(dao.findById("same", "account-a")!!.colour).isEqualTo("YELLOW")
        assertThat(dao.findById("same", "account-a")!!.deletedAt).isNull()
        assertThat(dao.findById("same", "account-b")!!.deletedAt).isEqualTo(21)
        assertThat(dao.observeActive("metadata", "hash", "account-a").first().map { it.text }).containsExactly("quote")
    }

    @Test fun `claim resolves UUID collision deterministically without rebinding foreign rows`() = runTest {
        dao.insert(row("same").copy(updatedAt = 20, deletedAt = 20))
        dao.insert(row("same").copy(ownerScope = "account-a", identityHashVersion = 2))
        dao.insert(row("same").copy(ownerScope = "account-b", text = "B quote"))
        db.highlightSyncDao().claimUnowned("account-a")
        assertThat(db.highlightSyncDao().unowned()).isEmpty()
        assertThat(dao.findById("same", "account-a")!!.deletedAt).isEqualTo(20)
        assertThat(dao.findById("same", "account-a")!!.identityHashVersion).isEqualTo(2)
        assertThat(dao.findById("same", "account-b")!!.text).isEqualTo("B quote")
        assertThat(db.highlightSyncDao().dirty("account-a")).hasSize(1)
    }

    @Test fun `zero timestamp snapshots are dirty until acknowledged`() = runTest {
        val zero = row("zero").copy(ownerScope = "account-a", createdAt = 0, updatedAt = 0)
        dao.insert(zero)
        assertThat(db.highlightSyncDao().dirty("account-a")).hasSize(1)
        db.highlightSyncDao().merge(listOf(zero))
        assertThat(db.highlightSyncDao().dirty("account-a")).isEmpty()
    }

    @Test fun `UTF8 comparator agrees with Unicode codepoint order beyond BMP and preserves null author tie`() {
        val supplementary = row("same").copy(text = "\uD800\uDC00")
        val bmp = row("same").copy(text = "\uE000")
        assertThat(compareHighlights(supplementary, bmp)).isGreaterThan(0)
        assertThat(compareHighlights(row("same").copy(bookAuthor = ""), row("same"))).isGreaterThan(0)
    }

    @Test fun `hash version never regresses and composite payload winners remain dirty`() = runTest {
        val newer = row("same").copy(ownerScope = "account-a", updatedAt = 20, text = "Local edit", syncedAt = 20)
        dao.insert(newer)
        val stale = newer.copy(updatedAt = 10, text = "Stale remote", identityHashVersion = 3)
        db.highlightSyncDao().merge(listOf(stale))
        val merged = db.highlightSyncDao().find("account-a", "same")!!
        assertThat(merged.text).isEqualTo("Local edit")
        assertThat(merged.identityHashVersion).isEqualTo(3)
        assertThat(db.highlightSyncDao().dirty("account-a")).hasSize(1)
        db.highlightSyncDao().merge(listOf(merged.copy(identityHashVersion = 1, text = "New remote", updatedAt = 30)))
        val latest = db.highlightSyncDao().find("account-a", "same")!!
        assertThat(latest.identityHashVersion).isEqualTo(3)
        assertThat(latest.text).isEqualTo("New remote")
        assertThat(db.highlightSyncDao().dirty("account-a")).hasSize(1)
        db.highlightSyncDao().merge(listOf(latest))
        assertThat(db.highlightSyncDao().dirty("account-a")).isEmpty()
    }

    @Test fun `equal clock payload comparison normalizes both hash versions before deciding winner`() = runTest {
        val green = row("same").copy(ownerScope = "account-a", identityHashVersion = 2, colour = "GREEN", syncedAt = 10)
        val yellow = green.copy(identityHashVersion = 1, colour = "YELLOW")
        dao.insert(green)
        db.highlightSyncDao().merge(listOf(yellow))
        val winner = db.highlightSyncDao().find("account-a", "same")!!
        assertThat(winner.colour).isEqualTo("YELLOW")
        assertThat(winner.identityHashVersion).isEqualTo(2)
        assertThat(db.highlightSyncDao().dirty("account-a")).hasSize(1)
        db.highlightSyncDao().put(yellow)
        db.highlightSyncDao().merge(listOf(green))
        val reversed = db.highlightSyncDao().find("account-a", "same")!!
        assertThat(reversed.colour).isEqualTo(winner.colour)
        assertThat(reversed.identityHashVersion).isEqualTo(winner.identityHashVersion)
    }

}
