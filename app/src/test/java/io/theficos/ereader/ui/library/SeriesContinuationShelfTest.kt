package io.theficos.ereader.ui.library

import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.ui.englishContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Resource labels keep series metadata unchanged while translating the surrounding text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class SeriesContinuationShelfTest {

    @Test fun `label is null when series name is null`() {
        assertThat(buildSeriesLabel(null, null)?.asString(englishContext())).isNull()
        assertThat(buildSeriesLabel(null, 2.0)?.asString(englishContext())).isNull()
    }

    @Test fun `label is null when series name is blank`() {
        assertThat(buildSeriesLabel("", 2.0)?.asString(englishContext())).isNull()
        assertThat(buildSeriesLabel("   ", 2.0)?.asString(englishContext())).isNull()
    }

    @Test fun `label shows series name only when index is null`() {
        assertThat(buildSeriesLabel("Foundation", null)?.asString(englishContext())).isEqualTo("Foundation")
    }

    @Test fun `label drops trailing zero on whole-number index`() {
        assertThat(buildSeriesLabel("Foundation", 1.0)?.asString(englishContext())).isEqualTo("Foundation · Book 1")
        assertThat(buildSeriesLabel("Foundation", 12.0)?.asString(englishContext())).isEqualTo("Foundation · Book 12")
    }

    @Test fun `label preserves fractional index for half-book entries`() {
        assertThat(buildSeriesLabel("Discworld", 2.5)?.asString(englishContext())).isEqualTo("Discworld · Book 2.5")
    }
}
