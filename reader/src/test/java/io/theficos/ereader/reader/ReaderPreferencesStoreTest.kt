package io.theficos.ereader.reader

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReaderPreferencesStoreTest {

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
}
