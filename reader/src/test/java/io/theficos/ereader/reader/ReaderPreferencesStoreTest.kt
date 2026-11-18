package io.theficos.ereader.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReaderPreferencesStoreTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun rawPrefs() =
        context().getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)

    private fun freshStore() = ReaderPreferencesStore(ApplicationProvider.getApplicationContext()).also {
        it.update { ReaderPreferences() }
    }

    @Test fun `default tapNavigationEnabled is true`() {
        val store = freshStore()
        assertThat(store.flow.value.tapNavigationEnabled).isTrue()
    }

    @Test fun `default pageMargins is 1_4`() {
        val store = freshStore()
        assertThat(store.flow.value.pageMargins).isWithin(0.001).of(1.4)
    }

    @Test fun `defaults for the new typography and immersive fields`() {
        val store = freshStore()
        assertThat(store.flow.value.paragraphIndent).isNull()
        assertThat(store.flow.value.paragraphSpacing).isNull()
        assertThat(store.flow.value.usePublisherStyles).isFalse()
        assertThat(store.flow.value.immersiveReading).isTrue()
    }

    @Test fun `non-default reader preferences persist and a cleared indent stays null`() {
        val store = freshStore()
        store.update {
            it.copy(
                tapNavigationEnabled = false, pageMargins = 1.8,
                paragraphIndent = 1.5, paragraphSpacing = 1.0,
                usePublisherStyles = true, immersiveReading = false,
                theme = ReaderTheme.DARK_SEPIA, fontFamily = ReaderFontFamily.LITERATA,
            )
        }

        val reloaded = ReaderPreferencesStore(context())
        for (saved in listOf(store.flow.value, reloaded.flow.value)) {
            assertThat(saved.tapNavigationEnabled).isFalse()
            assertThat(saved.pageMargins).isWithin(0.001).of(1.8)
            assertThat(saved.paragraphIndent).isWithin(0.001).of(1.5)
            assertThat(saved.paragraphSpacing).isWithin(0.001).of(1.0)
            assertThat(saved.usePublisherStyles).isTrue()
            assertThat(saved.immersiveReading).isFalse()
            assertThat(saved.theme).isEqualTo(ReaderTheme.DARK_SEPIA)
            assertThat(saved.fontFamily).isEqualTo(ReaderFontFamily.LITERATA)
        }

        reloaded.update { it.copy(paragraphIndent = null) }
        assertThat(ReaderPreferencesStore(context()).flow.value.paragraphIndent).isNull()
    }

    @Test fun `an unknown stored theme falls back to LIGHT`() {
        // What a downgrade looks like: an older build reads DARK_SEPIA and must not crash.
        rawPrefs().edit().putString("theme", "NOT_A_THEME").apply()

        val store = ReaderPreferencesStore(context())
        assertThat(store.flow.value.theme).isEqualTo(ReaderTheme.LIGHT)
    }

    @Test fun `a reader who had picked Charter gets Charis, the face that replaced it`() {
        // CHARIS was saved as CHARTER until the fonts were bundled; the old name must keep working.
        rawPrefs().edit().putString("font_family", "CHARTER").apply()

        val store = ReaderPreferencesStore(context())
        assertThat(store.flow.value.fontFamily).isEqualTo(ReaderFontFamily.CHARIS)
    }

    @Test fun `an unknown stored font family falls back to the system font`() {
        rawPrefs().edit().putString("font_family", "NOT_A_FONT").apply()

        val store = ReaderPreferencesStore(context())
        assertThat(store.flow.value.fontFamily).isEqualTo(ReaderFontFamily.SYSTEM)
    }

    @Test fun `out-of-range stored paragraph values are clamped on load, never throw`() {
        rawPrefs().edit()
            .putFloat("paragraph_indent", 9.0f)
            .putFloat("paragraph_spacing", -1.0f)
            .apply()

        val store = ReaderPreferencesStore(context())
        assertThat(store.flow.value.paragraphIndent).isWithin(0.001).of(3.0)
        assertThat(store.flow.value.paragraphSpacing).isWithin(0.001).of(0.0)
    }

    @Test fun `upgrade over empty prefs yields the intended defaults`() {
        rawPrefs().edit().clear().apply()

        val store = ReaderPreferencesStore(context())
        assertThat(store.flow.value.paragraphIndent).isNull()
        assertThat(store.flow.value.paragraphSpacing).isNull()
        assertThat(store.flow.value.usePublisherStyles).isFalse()
        assertThat(store.flow.value.immersiveReading).isTrue()
    }
}
