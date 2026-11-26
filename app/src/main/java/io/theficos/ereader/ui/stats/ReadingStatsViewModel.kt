package io.theficos.ereader.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ReadingStatsUiState(val stats: ReadingStats, val recording: Boolean)

class ReadingStatsViewModel(
    private val repository: ReadingStatsRepository,
    nowMs: () -> Long = System::currentTimeMillis,
    zone: () -> ZoneId = ZoneId::systemDefault,
    firstDayOfWeek: () -> DayOfWeek = { WeekFields.of(Locale.getDefault()).firstDayOfWeek },
) : ViewModel() {
    /** Ticks now and just after each midnight, so a screen left open moves on to the new day. */
    private val days = flow {
        while (true) {
            emit(Unit)
            val now = Instant.ofEpochMilli(nowMs()).atZone(zone())
            val midnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
            delay(Duration.between(now, midnight).toMillis() + 1_000)
        }
    }

    /** Null until the first computation, which runs off the main thread. */
    val state: StateFlow<ReadingStatsUiState?> = combine(
        repository.sessions, repository.bookProgress, repository.wordCounts, repository.recording, days,
    ) { sessions, progress, counts, recording, _ ->
        ReadingStatsUiState(
            stats = readingStats(
                sessions = sessions,
                progress = progress,
                wordCounts = counts.associate { it.bookKey to it.words },
                nowMs = nowMs(),
                zone = zone(),
                firstDayOfWeek = firstDayOfWeek(),
            ),
            recording = recording,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun clear() {
        viewModelScope.launch { repository.clear() }
    }
}
