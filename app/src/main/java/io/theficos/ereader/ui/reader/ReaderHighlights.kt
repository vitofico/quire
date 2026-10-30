package io.theficos.ereader.ui.reader

import io.theficos.ereader.core.model.Document
import io.theficos.ereader.core.model.Highlight
import io.theficos.ereader.core.model.HighlightColour
import io.theficos.ereader.data.local.HighlightRepository
import io.theficos.ereader.ui.UiText
import io.theficos.ereader.ui.uiText
import io.theficos.quire.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import org.readium.r2.shared.publication.Locator

/** Reader-owned editing state; the repository remains the source of saved decorations. */
internal class ReaderHighlights(
    private val repository: HighlightRepository,
    private val scope: CoroutineScope,
) {
    private var document: Document? = null
    private var observation: Job? = null
    private var mutation: Job? = null
    private var mutationGeneration = 0
    private val _items = MutableStateFlow<List<Highlight>>(emptyList())
    val items = _items.asStateFlow()
    private val _mode = MutableStateFlow(false)
    val mode = _mode.asStateFlow()
    private val _editor = MutableStateFlow<HighlightEditor?>(null)
    val editor = _editor.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<UiText?>(null)
    val error = _error.asStateFlow()
    private val _notice = MutableStateFlow<UiText?>(null)
    val notice = _notice.asStateFlow()

    fun attach(book: Document) {
        if (document == book) return
        mutationGeneration++
        mutation?.cancel()
        observation?.cancel()
        document = book
        _items.value = emptyList()
        _mode.value = false
        _editor.value = null
        _busy.value = false
        _error.value = null
        _notice.value = null
        observation = scope.launch {
            repository.observe(book.identity).catch {
                _notice.value = uiText(R.string.reader_highlights_load_failed)
            }.collect { saved ->
                _items.value = saved
                val editing = _editor.value as? HighlightEditor.Existing
                if (editing != null && saved.none { it.id == editing.highlight.id }) {
                    _editor.value = null
                    _error.value = null
                }
            }
        }
    }

    fun enterMode() { _mode.value = true }

    fun leaveMode(): Boolean {
        if (_busy.value) return false
        dismissEditor()
        _mode.value = false
        return true
    }

    fun select(locator: Locator) {
        if (!_mode.value || _busy.value || document == null) return
        val text = locator.text.highlight?.takeIf { it.isNotBlank() } ?: return
        _error.value = null
        _editor.value = HighlightEditor.New(locator.toJSON().toString(), text)
    }

    fun edit(id: String): Boolean {
        if (!_mode.value || _busy.value) return false
        val highlight = _items.value.firstOrNull { it.id == id } ?: return false
        _error.value = null
        _editor.value = HighlightEditor.Existing(highlight)
        return true
    }

    fun dismissEditor() {
        if (_busy.value) return
        _editor.value = null
        _error.value = null
    }

    fun saveColour(colour: HighlightColour) = change { book, editor ->
        when (editor) {
            is HighlightEditor.New -> repository.create(book, editor.locator, editor.text, colour)
            is HighlightEditor.Existing -> repository.setColour(editor.highlight.id, colour)
        }
    }

    fun remove() {
        if (_editor.value !is HighlightEditor.Existing) return
        change { _, editor -> repository.delete((editor as HighlightEditor.Existing).highlight.id) }
    }

    private fun change(action: suspend (Document, HighlightEditor) -> Unit) {
        val book = document ?: return
        val selected = _editor.value ?: return
        if (_busy.value) return
        val generation = ++mutationGeneration
        _busy.value = true
        _error.value = null
        mutation = scope.launch {
            try {
                action(book, selected)
                if (generation == mutationGeneration) _editor.value = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == mutationGeneration && _editor.value == selected) {
                    _error.value = uiText(R.string.reader_highlight_save_failed)
                }
            } finally {
                if (generation == mutationGeneration) _busy.value = false
            }
        }
    }

    fun notify(message: UiText) { _notice.value = message }
    fun clearNotice(message: UiText) { if (_notice.value == message) _notice.value = null }
}

internal sealed interface HighlightEditor {
    val text: String
    data class New(val locator: String, override val text: String) : HighlightEditor
    data class Existing(val highlight: Highlight) : HighlightEditor {
        override val text: String get() = highlight.text
    }
}
