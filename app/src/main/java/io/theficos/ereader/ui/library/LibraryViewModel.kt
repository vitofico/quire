package io.theficos.ereader.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.ereader.core.model.Document
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.local.ProgressRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val docs: DocumentRepository,
    private val progress: ProgressRepository,
) : ViewModel() {

    val items: StateFlow<List<LibraryRow>> =
        docs.observeLibrary()
            .flatMapLatest { docList ->
                if (docList.isEmpty()) flowOf(emptyList())
                else combine(docList.map { d -> progress.observe(d.id).map { d to it?.percent } }) { it.toList() }
            }
            .map { pairs -> pairs.map { (d, pct) -> LibraryRow(d, pct ?: 0.0) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun delete(document: Document) {
        viewModelScope.launch { docs.delete(document) }
    }
}

data class LibraryRow(val document: Document, val percent: Double)
