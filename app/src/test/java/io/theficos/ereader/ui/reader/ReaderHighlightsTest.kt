package io.theficos.ereader.ui.reader

import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.core.model.Document
import io.theficos.ereader.core.model.DocumentIdentity
import io.theficos.ereader.core.model.HighlightColour
import io.theficos.ereader.data.local.HighlightRepository
import io.theficos.ereader.data.local.db.HighlightDao
import io.theficos.ereader.data.local.db.HighlightEntity
import io.theficos.ereader.ui.uiText
import io.theficos.quire.R
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class ReaderHighlightsTest {
    private val dao = EditingDao()
    private var sequence = 0
    private val repository = HighlightRepository(dao, nowMs = { 100L }, newId = { "highlight-${++sequence}" })
    private val book = Document(1L, DocumentIdentity(metadataId = "book", contentHash = "hash"), "Title", "Author", "local:", "unused", null, 0)
    private val selected = Locator(Url("chapter.xhtml")!!, MediaType.XHTML, text = Locator.Text(highlight = "A selected passage."))

    @Test fun `list loading ends only after the identity query emits and a load failure can be retried`() = runTest {
        val saved = repository.create(book, selected.toJSON().toString(), "Saved quote", HighlightColour.GREEN)
        val state = ReaderHighlights(repository, backgroundScope)
        state.attach(book)
        assertThat(state.loading.value).isTrue()
        runCurrent()
        assertThat(state.loading.value).isFalse()
        assertThat(state.items.value.single().id).isEqualTo(saved.id)

        dao.failLoads = true
        state.reload()
        assertThat(state.loading.value).isTrue()
        runCurrent()
        assertThat(state.loading.value).isFalse()
        assertThat(state.loadError.value).isEqualTo(uiText(R.string.reader_highlights_load_error))
        assertThat(state.items.value.single().text).isEqualTo("Saved quote")

        dao.failLoads = false
        state.reload()
        runCurrent()
        assertThat(state.loadError.value).isNull()
        assertThat(state.notice.value).isNull()
        assertThat(state.items.value.single().id).isEqualTo(saved.id)
    }

    @Test fun `selection editing is scoped to highlight mode and Back can close editor before mode`() = runTest {
        val state = ReaderHighlights(repository, backgroundScope)
        state.attach(book)
        runCurrent()
        state.select(selected)
        assertThat(state.editor.value).isNull()
        state.enterMode()
        state.select(selected)
        assertThat(state.editor.value?.text).isEqualTo("A selected passage.")
        state.dismissEditor()
        assertThat(state.editor.value).isNull()
        assertThat(state.mode.value).isTrue()
        assertThat(state.leaveMode()).isTrue()
        assertThat(state.mode.value).isFalse()
    }

    @Test fun `choosing a colour persists the captured quote and repository flow supplies its decoration`() = runTest {
        val state = ReaderHighlights(repository, backgroundScope)
        state.attach(book)
        state.enterMode()
        state.select(selected)
        state.saveColour(HighlightColour.PINK)
        runCurrent()
        assertThat(state.editor.value).isNull()
        assertThat(state.busy.value).isFalse()
        val saved = state.items.value.single()
        assertThat(saved.text).isEqualTo("A selected passage.")
        assertThat(saved.colour).isEqualTo(HighlightColour.PINK)
        assertThat(saved.bookTitle).isEqualTo("Title")
        assertThat(Locator.fromJSON(org.json.JSONObject(saved.locator))?.text?.highlight).isEqualTo(saved.text)
    }

    @Test fun `failed save retains the passage for retry without inventing a saved decoration`() = runTest {
        val state = ReaderHighlights(repository, backgroundScope)
        state.attach(book)
        state.enterMode()
        state.select(selected)
        dao.failWrites = true
        state.saveColour(HighlightColour.GREEN)
        runCurrent()
        assertThat(state.editor.value?.text).isEqualTo("A selected passage.")
        assertThat(state.error.value).isEqualTo(uiText(R.string.reader_highlight_save_failed))
        assertThat(state.items.value).isEmpty()
        assertThat(state.busy.value).isFalse()
        dao.failWrites = false
        state.saveColour(HighlightColour.GREEN)
        runCurrent()
        assertThat(state.items.value).hasSize(1)
        assertThat(state.error.value).isNull()
        assertThat(state.editor.value).isNull()
    }

    @Test fun `recolour and remove operate on the same UUID and removal is hidden by the flow`() = runTest {
        val state = ReaderHighlights(repository, backgroundScope)
        state.attach(book)
        state.enterMode()
        state.select(selected)
        state.saveColour(HighlightColour.YELLOW)
        runCurrent()
        val id = state.items.value.single().id
        assertThat(state.edit(id)).isTrue()
        state.saveColour(HighlightColour.PINK)
        runCurrent()
        assertThat(state.items.value.single().id).isEqualTo(id)
        assertThat(state.items.value.single().colour).isEqualTo(HighlightColour.PINK)
        state.edit(id)
        dao.failWrites = true
        state.remove()
        runCurrent()
        assertThat(state.editor.value).isInstanceOf(HighlightEditor.Existing::class.java)
        assertThat(state.items.value).hasSize(1)
        dao.failWrites = false
        state.remove()
        runCurrent()
        assertThat(state.items.value).isEmpty()
        assertThat(dao.rows.value.single().deletedAt).isNotNull()
    }

    @Test fun `a pending write cannot be duplicated or silently dismissed`() = runTest {
        val state = ReaderHighlights(repository, backgroundScope)
        dao.insertGate = CompletableDeferred()
        state.attach(book)
        state.enterMode()
        state.select(selected)
        state.saveColour(HighlightColour.YELLOW)
        runCurrent()
        state.saveColour(HighlightColour.PINK)
        state.dismissEditor()
        assertThat(state.leaveMode()).isFalse()
        assertThat(state.editor.value).isNotNull()
        assertThat(state.busy.value).isTrue()
        dao.insertGate?.complete(Unit)
        runCurrent()
        assertThat(state.items.value.single().colour).isEqualTo(HighlightColour.YELLOW)
        assertThat(state.busy.value).isFalse()
        assertThat(state.leaveMode()).isTrue()
    }

    @Test fun `switching books cancels pending editing and cannot attach its result to the new book`() = runTest {
        val state = ReaderHighlights(repository, backgroundScope)
        dao.insertGate = CompletableDeferred()
        state.attach(book)
        state.enterMode()
        state.select(selected)
        state.saveColour(HighlightColour.YELLOW)
        runCurrent()
        state.attach(book.copy(id = 2, identity = DocumentIdentity(contentHash = "other")))
        dao.insertGate?.complete(Unit)
        runCurrent()
        assertThat(state.items.value).isEmpty()
        assertThat(state.editor.value).isNull()
        assertThat(state.mode.value).isFalse()
        assertThat(state.busy.value).isFalse()
    }

    private class EditingDao : HighlightDao {
        val rows = MutableStateFlow<List<HighlightEntity>>(emptyList())
        var failWrites = false
        var failLoads = false
        var insertGate: CompletableDeferred<Unit>? = null
        override suspend fun insert(highlight: HighlightEntity) {
            insertGate?.await()
            if (failWrites) throw IOException("test write failure")
            rows.value = rows.value + highlight
        }
        override suspend fun findById(id: String) = rows.value.firstOrNull { it.id == id }
        override fun observeActive(metadataId: String?, contentHash: String?): Flow<List<HighlightEntity>> = if (failLoads) flow {
            throw IOException("test load failure")
        } else rows.map { all ->
            all.filter { it.deletedAt == null && ((metadataId != null && it.metadataId == metadataId) ||
                (contentHash != null && it.contentHash == contentHash)) }
        }
        override suspend fun setColour(id: String, colour: String, now: Long) {
            if (failWrites) throw IOException("test write failure")
            rows.value = rows.value.map { if (it.id == id) it.copy(colour = colour, updatedAt = now) else it }
        }
        override suspend fun softDelete(id: String, now: Long) {
            if (failWrites) throw IOException("test write failure")
            rows.value = rows.value.map { if (it.id == id) it.copy(deletedAt = now, updatedAt = now) else it }
        }
    }
}
