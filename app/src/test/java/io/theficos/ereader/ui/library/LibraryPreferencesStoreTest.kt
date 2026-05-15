package io.theficos.ereader.ui.library

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class LibraryPreferencesStoreTest {

    private fun fresh() = LibraryPreferencesStore(ApplicationProvider.getApplicationContext()).also {
        it.update(LibrarySort.RECENTLY_READ)
    }

    @Test fun `default sort is RECENTLY_READ when nothing is stored`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.getSharedPreferences("library_prefs", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        val store = LibraryPreferencesStore(ctx)
        assertThat(store.flow.value).isEqualTo(LibrarySort.RECENTLY_READ)
    }

    @Test fun `sort round-trips through update and reload`() {
        val store1 = fresh()
        store1.update(LibrarySort.AUTHOR)
        assertThat(store1.flow.value).isEqualTo(LibrarySort.AUTHOR)

        val store2 = LibraryPreferencesStore(ApplicationProvider.getApplicationContext())
        assertThat(store2.flow.value).isEqualTo(LibrarySort.AUTHOR)
    }

    @Test fun `unknown stored value falls back to default`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.getSharedPreferences("library_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putString("library_sort", "NONSENSE").apply()
        val store = LibraryPreferencesStore(ctx)
        assertThat(store.flow.value).isEqualTo(LibrarySort.RECENTLY_READ)
    }
}
