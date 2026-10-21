package io.theficos.ereader.ui.theme

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class AppThemePreferencesStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun clearPreferences() {
        context.getSharedPreferences("app_theme_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `existing installs continue to follow the system`() {
        assertThat(AppThemePreferencesStore(context).flow.value).isEqualTo(AppTheme.SYSTEM)
    }

    @Test fun `every app theme updates immediately and survives a reload`() {
        val store = AppThemePreferencesStore(context)
        AppTheme.entries.forEach { theme ->
            store.update(theme)
            assertThat(store.flow.value).isEqualTo(theme)
            assertThat(AppThemePreferencesStore(context).flow.value).isEqualTo(theme)
        }
    }

    @Test fun `unknown stored themes fall back to the system`() {
        context.getSharedPreferences("app_theme_prefs", Context.MODE_PRIVATE)
            .edit().putString("theme", "UNKNOWN_THEME").commit()
        assertThat(AppThemePreferencesStore(context).flow.value).isEqualTo(AppTheme.SYSTEM)
    }
}
