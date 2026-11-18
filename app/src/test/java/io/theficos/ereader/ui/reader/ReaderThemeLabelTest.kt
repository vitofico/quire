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

    @Test fun `every reader theme has its English display label`() {
        val context = englishContext()
        val labels = ReaderTheme.entries.associateWith { it.displayLabel().asString(context) }
        assertThat(labels).containsExactly(
            ReaderTheme.LIGHT, "Light",
            ReaderTheme.DARK, "Dark",
            ReaderTheme.SEPIA, "Sepia",
            ReaderTheme.DARK_SEPIA, "Dark sepia",
        )
    }
}
