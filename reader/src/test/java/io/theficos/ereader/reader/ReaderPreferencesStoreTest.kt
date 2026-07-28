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

    @Test fun `tapNavigationEnabled round-trips through update and reload`() {
        val store1 = freshStore()
        store1.update { it.copy(tapNavigationEnabled = false) }
        assertThat(store1.flow.value.tapNavigationEnabled).isFalse()

        val store2 = ReaderPreferencesStore(ApplicationProvider.getApplicationContext())
        assertThat(store2.flow.value.tapNavigationEnabled).isFalse()
    }

    @Test fun `default pageMargins is 1_4`() {
        val store = freshStore()
        assertThat(store.flow.value.pageMargins).isWithin(0.001).of(1.4)
    }

    @Test fun `pageMargins round-trips through update and reload`() {
        val store1 = freshStore()
        store1.update { it.copy(pageMargins = 1.8) }
        assertThat(store1.flow.value.pageMargins).isWithin(0.001).of(1.8)

        val store2 = ReaderPreferencesStore(ApplicationProvider.getApplicationContext())
        assertThat(store2.flow.value.pageMargins).isWithin(0.001).of(1.8)
    }

    @Test fun `defaults for the new typography and immersive fields`() {
        val store = freshStore()
        assertThat(store.flow.value.paragraphIndent).isNull()
        assertThat(store.flow.value.paragraphSpacing).isNull()
        assertThat(store.flow.value.usePublisherStyles).isFalse()
        assertThat(store.flow.value.immersiveReading).isTrue()
    }

    @Test fun `paragraphIndent round-trips (including back to null)`() {
        val store1 = freshStore()
        store1.update { it.copy(paragraphIndent = 1.5) }
        assertThat(store1.flow.value.paragraphIndent).isWithin(0.001).of(1.5)

        val store2 = ReaderPreferencesStore(context())
        assertThat(store2.flow.value.paragraphIndent).isWithin(0.001).of(1.5)

        store2.update { it.copy(paragraphIndent = null) }
        val store3 = ReaderPreferencesStore(context())
        assertThat(store3.flow.value.paragraphIndent).isNull()
    }

    @Test fun `paragraphSpacing round-trips`() {
        val store1 = freshStore()
        store1.update { it.copy(paragraphSpacing = 1.0) }
        assertThat(store1.flow.value.paragraphSpacing).isWithin(0.001).of(1.0)

        val store2 = ReaderPreferencesStore(context())
        assertThat(store2.flow.value.paragraphSpacing).isWithin(0.001).of(1.0)
    }

    @Test fun `usePublisherStyles and immersiveReading round-trip`() {
        val store1 = freshStore()
        store1.update { it.copy(usePublisherStyles = true, immersiveReading = false) }

        val store2 = ReaderPreferencesStore(context())
        assertThat(store2.flow.value.usePublisherStyles).isTrue()
        assertThat(store2.flow.value.immersiveReading).isFalse()
    }

    @Test fun `theme round-trips through update and reload`() {
        val store1 = freshStore()
        store1.update { it.copy(theme = ReaderTheme.DARK_SEPIA) }

        val store2 = ReaderPreferencesStore(context())
        assertThat(store2.flow.value.theme).isEqualTo(ReaderTheme.DARK_SEPIA)
    }

    @Test fun `an unknown stored theme falls back to LIGHT`() {
        // What a downgrade looks like: an older build reads DARK_SEPIA and must not crash.
        rawPrefs().edit().putString("theme", "NOT_A_THEME").apply()

        val store = ReaderPreferencesStore(context())
        assertThat(store.flow.value.theme).isEqualTo(ReaderTheme.LIGHT)
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
