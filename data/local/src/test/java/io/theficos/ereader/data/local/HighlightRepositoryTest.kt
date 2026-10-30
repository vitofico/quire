package io.theficos.ereader.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.core.model.Document
import io.theficos.ereader.core.model.DocumentIdentity
import io.theficos.ereader.core.model.HighlightColour
import io.theficos.ereader.data.local.db.DocumentEntity
import io.theficos.ereader.data.local.db.EReaderDatabase
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HighlightRepositoryTest {
    private lateinit var db: EReaderDatabase
    private lateinit var repo: HighlightRepository
    private var now = 100L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), EReaderDatabase::class.java,
        ).allowMainThreadQueries().build()
        db.openHelper.writableDatabase
        repo = HighlightRepository(db.highlightDao(), nowMs = { now })
    }

    @After fun tearDown() = db.close()

    private fun document(
        id: Long = 1,
        identity: DocumentIdentity = DocumentIdentity(metadataId = "book-one", contentHash = "hash-one", identityHashVersion = 2),
    ) = Document(
        id = id, identity = identity, title = "Book title", author = "Author name",
        downloadUrl = "https://example.test/book.epub", localPath = "/book-$id.epub",
        coverPath = null, downloadedAt = 1,
    )

    @Test fun `creation keeps the full locator text identity and attribution under a device UUID`() = runTest {
        val doc = document(identity = DocumentIdentity(metadataId = "URN:UUID:AA-BB", contentHash = "hash-one", identityHashVersion = 2))
        val locator = """{"href":"chapter.xhtml","locations":{"quireDomRange":"{\"version\":1}"}}"""
        val selected = "A passage.\nAnother paragraph."
        val highlight = repo.create(doc, locator, selected, HighlightColour.GREEN)

        assertThat(UUID.fromString(highlight.id).toString()).isEqualTo(highlight.id)
        assertThat(highlight.identity.metadataId).isEqualTo("aabb")
        assertThat(highlight.identity.contentHash).isEqualTo("hash-one")
        assertThat(highlight.identity.identityHashVersion).isEqualTo(2)
        assertThat(highlight.locator).isEqualTo(locator)
        assertThat(highlight.text).isEqualTo(selected)
        assertThat(highlight.bookTitle).isEqualTo(doc.title)
        assertThat(highlight.bookAuthor).isEqualTo(doc.author)
        assertThat(highlight.createdAt).isEqualTo(100L)
        assertThat(highlight.updatedAt).isEqualTo(100L)
        assertThat(repo.observe(doc.identity).first()).containsExactly(highlight)

        val another = repo.create(doc, locator, selected, HighlightColour.PINK)
        assertThat(another.id).isNotEqualTo(highlight.id)
    }

    @Test fun `both identity hints match one annotation without duplicates or unrelated books`() = runTest {
        val doc = document()
        val saved = repo.create(doc, "locator", "quote", HighlightColour.YELLOW)

        assertThat(repo.observe(doc.identity).first().map { it.id }).containsExactly(saved.id)
        // A newly downloaded encoding has a different hash but retains its canonical metadata id.
        assertThat(repo.observe(DocumentIdentity(metadataId = "book-one", contentHash = "new-encoding")).first().map { it.id })
            .containsExactly(saved.id)
        assertThat(repo.observe(DocumentIdentity(contentHash = "hash-one")).first().map { it.id }).containsExactly(saved.id)
        assertThat(repo.observe(DocumentIdentity(metadataId = "different", contentHash = "different")).first()).isEmpty()
    }

    @Test fun `hash-only annotations remain visible when metadata becomes available`() = runTest {
        val doc = document(identity = DocumentIdentity(contentHash = "hash-only"))
        val saved = repo.create(doc, "locator", "quote", HighlightColour.YELLOW)
        val learnedIdentity = DocumentIdentity(metadataId = "newly-known-id", contentHash = "hash-only")

        assertThat(repo.observe(learnedIdentity).first().map { it.id }).containsExactly(saved.id)
    }

    @Test fun `removing one or all downloads keeps annotations for a new document row`() = runTest {
        val originalId = db.documentDao().insert(DocumentEntity(
            metadataId = "bookone", contentHash = "hash-one", title = "Title", author = null,
            downloadUrl = "u", localPath = "p", coverPath = null, downloadedAt = 1,
        ))
        val doc = document(id = originalId)
        val saved = repo.create(doc, "locator", "quote", HighlightColour.YELLOW)
        db.documentDao().deleteById(originalId)

        assertThat(db.highlightDao().findById(saved.id)).isNotNull()
        val replacementId = db.documentDao().insert(DocumentEntity(
            metadataId = "bookone", contentHash = "hash-new", title = "Title", author = null,
            downloadUrl = "u", localPath = "p2", coverPath = null, downloadedAt = 2,
        ))
        assertThat(repo.observe(document(replacementId, DocumentIdentity(metadataId = "book-one", contentHash = "hash-new")).identity)
            .first().map { it.id }).containsExactly(saved.id)
        db.documentDao().deleteAll()
        assertThat(repo.observe(doc.identity).first().map { it.id }).containsExactly(saved.id)
        assertThat(db.highlightDao().findById(saved.id)?.bookAuthor).isEqualTo("Author name")
    }

    @Test fun `colour changes and deletion update observers while preserving a tombstone`() = runTest {
        val doc = document()
        repo.observe(doc.identity).test {
            assertThat(awaitItem()).isEmpty()
            val saved = repo.create(doc, "locator", "quote", HighlightColour.YELLOW)
            assertThat(awaitItem().single().id).isEqualTo(saved.id)
            now = 200L
            repo.setColour(saved.id, HighlightColour.PINK)
            val changed = awaitItem().single()
            assertThat(changed.colour).isEqualTo(HighlightColour.PINK)
            assertThat(changed.createdAt).isEqualTo(100L)
            assertThat(changed.updatedAt).isEqualTo(200L)
            now = 300L
            repo.delete(saved.id)
            assertThat(awaitItem()).isEmpty()
            cancelAndIgnoreRemainingEvents()

            val tombstone = db.highlightDao().findById(saved.id)!!
            assertThat(tombstone.deletedAt).isEqualTo(300L)
            assertThat(tombstone.updatedAt).isEqualTo(300L)
            assertThat(tombstone.locator).isEqualTo("locator")
            assertThat(tombstone.text).isEqualTo("quote")
            now = 400L
            repo.setColour(saved.id, HighlightColour.GREEN)
            repo.delete(saved.id)
            assertThat(db.highlightDao().findById(saved.id)).isEqualTo(tombstone)
        }
    }

    @Test fun `timestamps stay monotonic when the device clock moves backwards`() = runTest {
        val saved = repo.create(document(), "locator", "quote", HighlightColour.YELLOW)
        now = 5L
        repo.setColour(saved.id, HighlightColour.GREEN)
        assertThat(db.highlightDao().findById(saved.id)?.updatedAt).isEqualTo(101L)
        repo.setColour(saved.id, HighlightColour.GREEN)
        assertThat(db.highlightDao().findById(saved.id)?.updatedAt).isEqualTo(101L)
        repo.delete(saved.id)
        assertThat(db.highlightDao().findById(saved.id)?.deletedAt).isEqualTo(102L)
        assertThat(db.highlightDao().findById(saved.id)?.updatedAt).isEqualTo(102L)
    }

    @Test fun `blank or alias-only identity hints cannot match another book`() = runTest {
        repo.create(document(), "locator", "quote", HighlightColour.YELLOW)

        assertThat(repo.observe(DocumentIdentity(metadataId = "  ", contentHash = "  ")).first()).isEmpty()
        assertThat(repo.observe(DocumentIdentity(opdsHref = "https://example.test/catalog")).first()).isEmpty()
    }

    @Test fun `creation rejects missing downloaded identity or empty selection without writing a row`() = runTest {
        val invalid = listOf(
            document(identity = DocumentIdentity(metadataId = "known-id")) to ("locator" to "quote"),
            document(identity = DocumentIdentity(contentHash = "  ")) to ("locator" to "quote"),
            document() to (" " to "quote"),
            document() to ("locator" to "\n"),
        )
        for ((doc, selection) in invalid) {
            val failure = runCatching { repo.create(doc, selection.first, selection.second, HighlightColour.YELLOW) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThat(repo.observe(document().identity).first()).isEmpty()
    }
    @Test fun `open book observation follows account changes and edits never reach a foreign account`() = runTest {
        val scope = MutableStateFlow("account-a")
        repo = HighlightRepository(db.highlightDao(), nowMs = { now }, scopeProvider = { scope.value }, scopeFlow = scope)
        val a = repo.create(document(), "locator", "A private quote", HighlightColour.YELLOW)
        scope.value = "account-b"
        val b = repo.create(document(), "locator", "B private quote", HighlightColour.GREEN)
        scope.value = "account-a"
        repo.observe(document().identity).test {
            assertThat(awaitItem().map { it.id }).containsExactly(a.id)
            scope.value = "account-b"
            assertThat(awaitItem().map { it.id }).containsExactly(b.id)
            repo.delete(a.id)
            repo.setColour(a.id, HighlightColour.PINK)
            assertThat(db.highlightDao().findById(a.id, "account-a")!!.deletedAt).isNull()
            assertThat(db.highlightDao().findById(a.id, "account-a")!!.colour).isEqualTo("YELLOW")
            scope.value = ""
            assertThat(awaitItem()).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `offline rows stay visible and editable until first authenticated account claims them`() = runTest {
        val offline = repo.create(document(), "locator", "Offline quote", HighlightColour.YELLOW)
        val scope = MutableStateFlow("account-a")
        repo = HighlightRepository(db.highlightDao(), nowMs = { now }, scopeProvider = { scope.value }, scopeFlow = scope)
        assertThat(repo.observe(document().identity).first().map { it.id }).containsExactly(offline.id)
        repo.setColour(offline.id, HighlightColour.PINK)
        assertThat(db.highlightDao().findById(offline.id)!!.colour).isEqualTo("PINK")
        db.highlightSyncDao().claimUnowned("account-a")
        scope.value = "account-b"
        assertThat(repo.observe(document().identity).first()).isEmpty()
        scope.value = "account-a"
        assertThat(repo.observe(document().identity).first().map { it.id }).containsExactly(offline.id)
    }

    @Test fun `OPDS account passages remain local and cannot be claimed by a later Quire account`() = runTest {
        val scope = MutableStateFlow("local-opds-account")
        repo = HighlightRepository(db.highlightDao(), nowMs = { now }, scopeProvider = { scope.value }, scopeFlow = scope)
        val local = repo.create(document(), "locator", "Private OPDS quote", HighlightColour.GREEN)
        scope.value = "quire-account"
        db.highlightSyncDao().claimUnowned(scope.value)
        assertThat(db.highlightSyncDao().dirty(scope.value)).isEmpty()
        assertThat(repo.observe(document().identity).first()).isEmpty()
        scope.value = "local-opds-account"
        assertThat(repo.observe(document().identity).first().single().id).isEqualTo(local.id)
    }

    @Test fun `offline creation crossing authenticated claim is retained for next run`() = runTest {
        val scope = MutableStateFlow("")
        repo = HighlightRepository(
            db.highlightDao(),
            scopeProvider = { scope.value }, scopeFlow = scope,
            nowMs = {
                // Creation already captured its offline scope, then authentication/claim completes.
                scope.value = "account-a"
                runBlocking { db.highlightSyncDao().claimUnowned("account-a") }
                100L
            },
        )
        val pending = repo.create(document(), "locator", "Created during login", HighlightColour.YELLOW)
        assertThat(db.highlightSyncDao().unowned().single().id).isEqualTo(pending.id)
        db.highlightSyncDao().claimUnowned("account-a")
        assertThat(db.highlightSyncDao().unowned()).isEmpty()
        assertThat(db.highlightSyncDao().dirty("account-a").single().id).isEqualTo(pending.id)
    }

}
