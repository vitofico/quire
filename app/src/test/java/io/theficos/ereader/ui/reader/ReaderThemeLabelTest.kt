package io.theficos.ereader.ui.reader

import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.reader.ReaderTheme
import io.theficos.ereader.ui.englishContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class ReaderThemeLabelTest {

    @Test fun `multi-word theme names read as words, not as enum constants`() {
        assertThat(ReaderTheme.DARK_SEPIA.displayLabel().asString(englishContext())).isEqualTo("Dark sepia")
    }

    @Test fun `single-word theme names are unchanged`() {
        assertThat(ReaderTheme.LIGHT.displayLabel().asString(englishContext())).isEqualTo("Light")
        assertThat(ReaderTheme.DARK.displayLabel().asString(englishContext())).isEqualTo("Dark")
        assertThat(ReaderTheme.SEPIA.displayLabel().asString(englishContext())).isEqualTo("Sepia")
    }

    @Test fun `no theme label leaks an underscore into the UI`() {
        ReaderTheme.values().forEach { theme ->
            assertThat(theme.displayLabel().asString(englishContext())).doesNotContain("_")
        }
    }
}
