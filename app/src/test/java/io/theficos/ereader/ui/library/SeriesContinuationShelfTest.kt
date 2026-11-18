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

    @Test fun `series labels preserve names and format only present book numbers`() {
        val context = englishContext()
        val rows = listOf(
            Triple(null, null, null),
            Triple(null, 2.0, null),
            Triple("", 2.0, null),
            Triple("   ", 2.0, null),
            Triple("Foundation", null, "Foundation"),
            Triple("Foundation", 1.0, "Foundation · Book 1"),
            Triple("Foundation", 12.0, "Foundation · Book 12"),
            Triple("Discworld", 2.5, "Discworld · Book 2.5"),
        )
        for ((series, index, expected) in rows) {
            assertThat(buildSeriesLabel(series, index)?.asString(context)).isEqualTo(expected)
        }
    }
}
