package io.theficos.ereader.ui.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator
import io.theficos.quire.R

/** Keeps native selection lifetime separate from the colour picker and clipboard operation. */
internal class ReaderSelectionController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val navigator: () -> EpubNavigatorFragment?,
    private val captureHighlight: suspend () -> Locator?,
    private val highlightMode: () -> Boolean,
    private val onHighlight: (Locator) -> Unit,
    private val onMessage: (Int) -> Unit,
    private val onSelectionChanged: (Boolean) -> Unit,
    private val bookTitle: String,
    private val bookAuthor: String?,
) : ActionMode.Callback {
    private var active: ActionMode? = null
    private var capture: Job? = null
    private var generation = 0
    private var closed = false

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        if (closed) return false
        capture?.cancel()
        generation++
        active = mode
        menu.clear()
        menu.add(Menu.NONE, HIGHLIGHT, 0, context.getString(R.string.reader_highlight))
            .isEnabled = highlightMode()
        menu.add(Menu.NONE, QUOTE, 1, context.getString(R.string.reader_copy_as_quote))
        menu.add(Menu.NONE, android.R.id.copy, 2, context.getString(android.R.string.copy))
        menu.add(Menu.NONE, android.R.id.selectAll, 3, context.getString(android.R.string.selectAll))
        onSelectionChanged(true)
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        val item = menu.findItem(HIGHLIGHT) ?: return false
        val enabled = highlightMode()
        if (item.isEnabled == enabled) return false
        item.isEnabled = enabled
        return true
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        if (closed) return false
        val nav = navigator() ?: return false
        if (item.itemId == android.R.id.selectAll) {
            scope.launch { nav.evaluateJavascript("document.execCommand('selectAll');") }
            return true
        }
        if (item.itemId !in setOf(HIGHLIGHT, QUOTE, android.R.id.copy)) return false
        if (item.itemId == HIGHLIGHT && !highlightMode()) return true
        capture?.cancel()
        val token = generation
        capture = scope.launch {
            try {
                // Neither the action mode nor selection may be closed until this round trip ends.
                val selected = if (item.itemId == HIGHLIGHT) captureHighlight() else nav.currentSelection()?.locator
                if (closed || navigator() !== nav || active !== mode || generation != token) return@launch
                val text = selected?.text?.highlight
                if (text.isNullOrBlank()) {
                    onMessage(if (item.itemId == HIGHLIGHT) R.string.reader_selection_unavailable else R.string.reader_copy_failed)
                    return@launch
                }
                if (item.itemId == HIGHLIGHT) {
                    if (!highlightMode()) return@launch
                    finish(mode, nav, token)
                    onHighlight(selected ?: return@launch)
                } else {
                    val quote = item.itemId == QUOTE
                    val value = if (quote) formatBookQuote(text, bookTitle, bookAuthor) else text
                    val label = context.getString(if (quote) R.string.reader_book_quote else android.R.string.copy)
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText(label, value))
                    finish(mode, nav, token)
                    onMessage(if (quote) R.string.reader_quote_copied else R.string.reader_text_copied)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!closed && active === mode && generation == token) {
                    onMessage(if (item.itemId == HIGHLIGHT) R.string.reader_selection_unavailable else R.string.reader_copy_failed)
                }
            }
        }
        return true
    }

    private fun finish(mode: ActionMode, nav: EpubNavigatorFragment, token: Int) {
        mode.finish()
        if (navigator() === nav && generation == token && active == null) nav.clearSelection()
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        if (active === mode) {
            active = null
            if (!closed) onSelectionChanged(false)
        }
    }

    fun dismissSelection(): Boolean {
        val hadSelection = active != null
        capture?.cancel()
        val token = ++generation
        active?.finish()
        if (generation == token) {
            active = null
            navigator()?.clearSelection()
            if (!closed) onSelectionChanged(false)
        }
        return hadSelection
    }

    fun close() {
        closed = true
        dismissSelection()
    }

    fun refreshMode() { active?.invalidate() }

    private companion object {
        const val HIGHLIGHT = 14301
        const val QUOTE = 14302
    }
}

internal fun formatBookQuote(text: String, title: String, author: String?): String {
    val attribution = listOfNotNull(title.trim().takeIf(String::isNotEmpty), author?.trim()?.takeIf(String::isNotEmpty))
        .joinToString(", ")
    return "“${text.trim()}”" + if (attribution.isEmpty()) "" else "\n— $attribution"
}
