package io.theficos.ereader.ui.stats

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class StatsFormatTest {
    @Test
    fun `durations are rounded to the minute and an estimate to five`() {
        assertThat(formatDuration(0, Locale.US)).isEqualTo("0 min")
        assertThat(formatDuration(89, Locale.US)).isEqualTo("1 min")
        assertThat(formatDuration(3 * 3_600 + 20 * 60, Locale.US)).isEqualTo("3 hr, 20 min")
        assertThat(formatDuration(2 * 3_600, Locale.US)).isEqualTo("2 hr")
        assertThat(formatDuration(3 * 3_600 + 22 * 60, Locale.US, rough = true)).isEqualTo("3 hr, 20 min")
        assertThat(formatDuration(42 * 60, Locale.US, rough = true)).isEqualTo("42 min")
        assertThat(formatDuration(3_600 + 60, Locale.US, wide = true)).isEqualTo("1 hour, 1 minute")
    }
}
