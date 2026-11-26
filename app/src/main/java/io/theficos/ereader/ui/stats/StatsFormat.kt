package io.theficos.ereader.ui.stats

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import java.util.Locale
import kotlin.math.roundToLong

/**
 * A reading time in the locale's own words ("3 hr, 20 min", or "3 hours, 20 minutes" when
 * [wide], for screen readers). Rounded to the minute; an estimate ([rough]) of an hour or more
 * is rounded to five minutes, since its precision is no better than that.
 */
internal fun formatDuration(seconds: Long, locale: Locale, wide: Boolean = false, rough: Boolean = false): String {
    var minutes = (seconds.coerceAtLeast(0) / 60.0).roundToLong()
    if (rough && minutes >= 60) minutes = (minutes / 5.0).roundToLong() * 5
    val hours = minutes / 60
    val rest = minutes % 60
    val format = MeasureFormat.getInstance(
        locale,
        if (wide) MeasureFormat.FormatWidth.WIDE else MeasureFormat.FormatWidth.SHORT,
    )
    val parts = buildList {
        if (hours > 0) add(Measure(hours, MeasureUnit.HOUR))
        if (rest > 0 || hours == 0L) add(Measure(rest, MeasureUnit.MINUTE))
    }
    return format.formatMeasures(*parts.toTypedArray())
}
