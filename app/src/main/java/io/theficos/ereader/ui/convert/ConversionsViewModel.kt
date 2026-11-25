package io.theficos.ereader.ui.convert

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.ereader.data.library.convert.Conversion
import io.theficos.ereader.data.library.convert.ConversionRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ConversionsViewModel(private val repository: ConversionRepository) : ViewModel() {
    /** Null until the first read, so an empty list never flashes before the rows. */
    val conversions: StateFlow<List<Conversion>?> =
        repository.conversions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // Opening the screen is a good moment to make sure the worker is on it.
        viewModelScope.launch { repository.resume() }
    }

    fun cancel(id: String) {
        viewModelScope.launch { repository.cancel(id) }
    }

    fun retry(id: String) {
        viewModelScope.launch { repository.retry(id) }
    }

    fun remove(id: String) {
        viewModelScope.launch { repository.remove(id) }
    }
}
