package io.theficos.ereader.ui.reader

import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.reader.ReaderTheme
import org.junit.Test

class ReaderThemeLabelTest {

    @Test fun `multi-word theme names read as words, not as enum constants`() {
        assertThat(ReaderTheme.DARK_SEPIA.displayLabel()).isEqualTo("Dark sepia")
    }

    @Test fun `single-word theme names are unchanged`() {
        assertThat(ReaderTheme.LIGHT.displayLabel()).isEqualTo("Light")
        assertThat(ReaderTheme.DARK.displayLabel()).isEqualTo("Dark")
        assertThat(ReaderTheme.SEPIA.displayLabel()).isEqualTo("Sepia")
    }

    @Test fun `no theme label leaks an underscore into the UI`() {
        ReaderTheme.values().forEach { theme ->
            assertThat(theme.displayLabel()).doesNotContain("_")
        }
    }
}
