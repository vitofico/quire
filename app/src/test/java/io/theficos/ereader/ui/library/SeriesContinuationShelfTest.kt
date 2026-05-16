package io.theficos.ereader.ui.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure-Kotlin tests for the label-builder helper. The composable's rendering
 * behavior is exercised end-to-end by [LibraryViewModelTest] via the StateFlow
 * it consumes; the on-empty "render nothing" branch is verified by inspection
 * (single `if (books.isEmpty()) return`).
 *
 * A Robolectric Compose runner would be a nice-to-have, but adding the
 * `androidx.compose.ui:ui-test-junit4` dependency is out of scope for PR8;
 * none of the existing UI surfaces have Compose tests in this module yet.
 */
class SeriesContinuationShelfTest {

    @Test fun `label is null when series name is null`() {
        assertThat(buildSeriesLabel(null, null)).isNull()
        assertThat(buildSeriesLabel(null, 2.0)).isNull()
    }

    @Test fun `label is null when series name is blank`() {
        assertThat(buildSeriesLabel("", 2.0)).isNull()
        assertThat(buildSeriesLabel("   ", 2.0)).isNull()
    }

    @Test fun `label shows series name only when index is null`() {
        assertThat(buildSeriesLabel("Foundation", null)).isEqualTo("Foundation")
    }

    @Test fun `label drops trailing zero on whole-number index`() {
        assertThat(buildSeriesLabel("Foundation", 1.0)).isEqualTo("Foundation · Book 1")
        assertThat(buildSeriesLabel("Foundation", 12.0)).isEqualTo("Foundation · Book 12")
    }

    @Test fun `label preserves fractional index for half-book entries`() {
        assertThat(buildSeriesLabel("Discworld", 2.5)).isEqualTo("Discworld · Book 2.5")
    }
}
