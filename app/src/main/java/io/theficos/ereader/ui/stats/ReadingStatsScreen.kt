package io.theficos.ereader.ui.stats

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.theficos.ereader.ui.components.QuireCard
import io.theficos.ereader.ui.components.QuireLoading
import io.theficos.ereader.ui.components.SectionLabel
import io.theficos.quire.R
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

private enum class Range { WEEK, MONTH, YEAR }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingStatsScreen(viewModel: ReadingStatsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_title), modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    if (state?.stats?.isEmpty == false) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.common_more))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.stats_clear)) },
                                    onClick = {
                                        menuOpen = false
                                        confirmClear = true
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        val current = state
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            when {
                current == null -> QuireLoading(Modifier.align(Alignment.Center))
                current.stats.isEmpty -> EmptyStats(current.recording)
                else -> StatsContent(current)
            }
        }
    }
    if (confirmClear) {
        ClearStatisticsDialog(
            onConfirm = {
                viewModel.clear()
                confirmClear = false
            },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
internal fun ClearStatisticsDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.stats_clear_title)) },
        text = { Text(stringResource(R.string.stats_clear_body)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.stats_clear_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun EmptyStats(recording: Boolean) {
    Column(
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.stats_empty_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.stats_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!recording) RecordingOffNote()
    }
}

@Composable
private fun RecordingOffNote() {
    Text(
        stringResource(
            R.string.stats_recording_off,
            stringResource(R.string.common_settings),
            stringResource(R.string.settings_record_stats),
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun StatsContent(state: ReadingStatsUiState) {
    val stats = state.stats
    val locale = LocalConfiguration.current.locales[0]
    LazyColumn(
        modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!state.recording) item { RecordingOffNote() }
        item { Headline(stats, locale) }
        item { ReadingTimeCard(stats, locale) }
        item { WhenYouReadCard(stats, locale) }
        item { SpeedCard(stats, locale) }
        item { Heading(stringResource(R.string.stats_books)) }
        items(stats.books, key = { it.bookKey }) { BookCard(it, locale) }
    }
}

@Composable
private fun Heading(text: String) {
    SectionLabel(text, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Headline(stats: ReadingStats, locale: Locale) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TileRow(
            stringResource(R.string.stats_today) to formatDuration(stats.todaySeconds, locale),
            stringResource(R.string.stats_this_week) to formatDuration(stats.weekSeconds, locale),
        )
        TileRow(
            stringResource(R.string.stats_this_month) to formatDuration(stats.monthSeconds, locale),
            stringResource(R.string.stats_all_time) to formatDuration(stats.totalSeconds, locale),
        )
        TileRow(
            stringResource(R.string.stats_current_streak) to
                pluralStringResource(R.plurals.stats_days, stats.currentStreak, stats.currentStreak),
            stringResource(R.string.stats_longest_streak) to
                pluralStringResource(R.plurals.stats_days, stats.longestStreak, stats.longestStreak),
        )
        TileRow(stringResource(R.string.stats_books_finished) to stats.booksFinished.toString())
        Note(stringResource(R.string.stats_streak_note))
    }
}

@Composable
private fun TileRow(vararg tiles: Pair<String, String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        tiles.forEach { (label, value) ->
            QuireCard(modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                Column {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        // A lone tile keeps the width of the others.
        if (tiles.size == 1) Box(Modifier.weight(1f))
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadingTimeCard(stats: ReadingStats, locale: Locale) {
    var range by rememberSaveable { mutableStateOf(Range.MONTH) }
    QuireCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Heading(stringResource(R.string.stats_reading_time))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val ranges = Range.entries
                ranges.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = range == option,
                        onClick = { range = option },
                        shape = SegmentedButtonDefaults.itemShape(index, ranges.size),
                    ) {
                        Text(stringResource(when (option) {
                            Range.WEEK -> R.string.stats_range_week
                            Range.MONTH -> R.string.stats_range_month
                            Range.YEAR -> R.string.stats_range_year
                        }))
                    }
                }
            }
            val dayFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
            val (values, labels, description) = when (range) {
                Range.WEEK, Range.MONTH -> {
                    val days = stats.lastDays(if (range == Range.WEEK) 7 else 30)
                    val labels = days.mapIndexed { i, (day, _) ->
                        when {
                            range == Range.WEEK -> day.dayOfWeek.getDisplayName(TextStyle.NARROW, locale)
                            (days.size - 1 - i) % 7 == 0 -> day.dayOfMonth.toString()
                            else -> ""
                        }
                    }
                    val best = days.maxBy { it.second }
                    Triple(days.map { it.second }, labels, periodDescription(
                        total = days.sumOf { it.second },
                        best = dayFormat.format(best.first),
                        bestSeconds = best.second,
                        template = if (range == Range.WEEK) R.string.stats_week_description else R.string.stats_month_description,
                        locale = locale,
                    ))
                }
                Range.YEAR -> {
                    val months = stats.lastMonths()
                    val best = months.maxBy { it.second }
                    Triple(
                        months.map { it.second },
                        months.map { it.first.month.getDisplayName(TextStyle.NARROW, locale) },
                        periodDescription(
                            total = months.sumOf { it.second },
                            best = "${best.first.month.getDisplayName(TextStyle.FULL_STANDALONE, locale)} ${best.first.year}",
                            bestSeconds = best.second,
                            template = R.string.stats_year_description,
                            locale = locale,
                        ),
                    )
                }
            }
            BarChart(values = values, labels = labels, description = description)
        }
    }
}

@Composable
private fun periodDescription(total: Long, best: String, bestSeconds: Long, template: Int, locale: Locale): String =
    if (total <= 0) stringResource(R.string.stats_no_reading_in_period)
    else stringResource(
        template,
        formatDuration(total, locale, wide = true),
        best,
        formatDuration(bestSeconds, locale, wide = true),
    )

@Composable
private fun WhenYouReadCard(stats: ReadingStats, locale: Locale) {
    QuireCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Heading(stringResource(R.string.stats_when_you_read))
            Text(stringResource(R.string.stats_by_hour), style = MaterialTheme.typography.titleSmall)
            // The device's 12 or 24 hour setting, in the locale's own pattern.
            val is24 = DateFormat.is24HourFormat(LocalContext.current)
            fun pattern(skeleton: String) =
                DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
            val timeFormat = pattern(if (is24) "Hm" else "hma")
            val hourFormat = pattern(if (is24) "H" else "ha")
            val peakHour = stats.byHour.indices.maxBy { stats.byHour[it] }
            BarChart(
                values = stats.byHour,
                labels = stats.byHour.indices.map { if (it % 6 == 0) hourFormat.format(LocalTime.of(it, 0)) else "" },
                description = stringResource(
                    R.string.stats_hour_description,
                    timeFormat.format(LocalTime.of(peakHour, 0)),
                    timeFormat.format(LocalTime.of((peakHour + 1) % 24, 0)),
                    formatDuration(stats.byHour[peakHour], locale, wide = true),
                ),
                barHeight = 96.dp,
            )
            Text(stringResource(R.string.stats_by_weekday), style = MaterialTheme.typography.titleSmall)
            val days = (0 until 7).map { stats.firstDayOfWeek.plus(it.toLong()) }
            val values = days.map { stats.byWeekday[it.value - 1] }
            val peak = days[values.indices.maxBy { values[it] }]
            BarChart(
                values = values,
                labels = days.map { it.getDisplayName(TextStyle.NARROW, locale) },
                description = stringResource(
                    R.string.stats_weekday_description,
                    peak.getDisplayName(TextStyle.FULL_STANDALONE, locale),
                    formatDuration(stats.byWeekday[peak.value - 1], locale, wide = true),
                ),
                barHeight = 96.dp,
            )
        }
    }
}

@Composable
private fun wpmText(wpm: Double): String {
    val rounded = wpm.roundToInt()
    return pluralStringResource(R.plurals.stats_words_per_minute, rounded, rounded)
}

@Composable
private fun SpeedCard(stats: ReadingStats, locale: Locale) {
    QuireCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Heading(stringResource(R.string.stats_speed))
            val overall = stats.wordsPerMinute
            Text(
                if (overall == null) stringResource(R.string.stats_speed_unknown)
                else stringResource(R.string.stats_average_speed, wpmText(overall)),
                style = MaterialTheme.typography.titleMedium,
            )
            if (stats.weeklySpeed.any { it.wordsPerMinute != null }) {
                Text(stringResource(R.string.stats_speed_trend), style = MaterialTheme.typography.titleSmall)
                val dayFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
                val description = stats.weeklySpeed.map { week ->
                    val start = dayFormat.format(week.weekStart)
                    week.wordsPerMinute?.let { stringResource(R.string.stats_speed_week, start, wpmText(it)) }
                        ?: stringResource(R.string.stats_speed_week_unknown, start)
                }.joinToString("; ")
                BarChart(
                    values = stats.weeklySpeed.map { it.wordsPerMinute?.roundToInt()?.toLong() ?: 0L },
                    labels = stats.weeklySpeed.map { it.weekStart.dayOfMonth.toString() },
                    description = "${stringResource(R.string.stats_speed_trend)}. $description",
                    barHeight = 96.dp,
                )
            }
            Note(stringResource(R.string.stats_speed_note))
        }
    }
}

@Composable
private fun BookCard(book: BookStats, locale: Locale) {
    QuireCard(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleMedium)
            book.author?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val line = listOfNotNull(
                formatDuration(book.seconds, locale),
                pluralStringResource(R.plurals.stats_sessions, book.sessions, book.sessions),
                book.wordsPerMinute?.let { wpmText(it) },
            ).joinToString(" · ")
            Text(line, style = MaterialTheme.typography.bodyMedium)
            book.secondsLeft?.let {
                Text(
                    stringResource(R.string.stats_time_left, formatDuration(it, locale, rough = true)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
