package io.theficos.ereader.ui.theme

import android.content.Context
import android.app.UiModeManager
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class AppThemePreferencesStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val prefs: SharedPreferences
        get() = context.getSharedPreferences("app_theme_prefs", Context.MODE_PRIVATE)

    @Before fun clearPreferences() {
        prefs.edit().clear().commit()
    }

    @Test fun `a fresh install follows the system with Quire's palettes`() {
        assertThat(AppThemePreferencesStore(context).flow.value).isEqualTo(AppTheme())
    }

    @Test fun `a change applies immediately and survives a reload`() {
        val store = AppThemePreferencesStore(context)
        val theme = AppTheme(mode = ThemeMode.DARK, light = Palette.SAGE, dark = Palette.MIDNIGHT)
        store.update(theme)
        assertThat(store.flow.value).isEqualTo(theme)
        assertThat(AppThemePreferencesStore(context).flow.value).isEqualTo(theme)
    }

    @Test fun `launch brightness follows a saved forced mode and initialization is idempotent`() {
        prefs.edit().putString("mode", "DARK").commit()
        val platform = shadowOf(context.getSystemService(UiModeManager::class.java))
        AppThemePreferencesStore(context)
        assertThat(platform.applicationNightMode).isEqualTo(UiModeManager.MODE_NIGHT_YES)
        AppThemePreferencesStore(context)
        assertThat(platform.applicationNightMode).isEqualTo(UiModeManager.MODE_NIGHT_YES)
    }

    @Test fun `both forced launch brightnesses reset when system mode is selected`() {
        val store = AppThemePreferencesStore(context)
        val platform = shadowOf(context.getSystemService(UiModeManager::class.java))
        mapOf(
            ThemeMode.LIGHT to UiModeManager.MODE_NIGHT_NO,
            ThemeMode.DARK to UiModeManager.MODE_NIGHT_YES,
        ).forEach { (mode, expected) ->
            store.update(AppTheme(mode = mode))
            assertThat(platform.applicationNightMode).isEqualTo(expected)
            store.update(AppTheme(mode = ThemeMode.SYSTEM))
            assertThat(platform.applicationNightMode).isEqualTo(UiModeManager.MODE_NIGHT_AUTO)
            assertThat(AppThemePreferencesStore(context).flow.value.mode).isEqualTo(ThemeMode.SYSTEM)
        }
    }

    @Test @Config(sdk = [30])
    fun `older Android retains saved app themes without calling the new platform API`() {
        val platform = shadowOf(context.getSystemService(UiModeManager::class.java))
        val before = platform.applicationNightMode
        val store = AppThemePreferencesStore(context)
        store.update(AppTheme(mode = ThemeMode.DARK))
        assertThat(store.flow.value.mode).isEqualTo(ThemeMode.DARK)
        assertThat(platform.applicationNightMode).isEqualTo(before)
    }

    @Test fun `a failed platform service cannot prevent the app theme opening`() {
        val failingContext = object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? {
                if (name == Context.UI_MODE_SERVICE) throw IllegalStateException("unavailable fixture service")
                return super.getSystemService(name)
            }
        }
        failingContext.syncLaunchNightMode(ThemeMode.DARK)
    }

    @Test fun `each old single choice migrates to the appearance it gave`() {
        mapOf(
            "SYSTEM" to AppTheme(),
            "LIGHT" to AppTheme(mode = ThemeMode.LIGHT),
            "DARK" to AppTheme(mode = ThemeMode.DARK),
            "SAGE" to AppTheme(mode = ThemeMode.LIGHT, light = Palette.SAGE),
            "MIDNIGHT" to AppTheme(mode = ThemeMode.DARK, dark = Palette.MIDNIGHT),
            "UNKNOWN_THEME" to AppTheme(),
        ).forEach { (legacy, expected) ->
            prefs.edit().clear().putString("theme", legacy).commit()
            assertWithMessage(legacy).that(AppThemePreferencesStore(context).flow.value).isEqualTo(expected)
            // Migrated once: the old key is gone and the new ones read back the same.
            assertWithMessage(legacy).that(prefs.contains("theme")).isFalse()
            assertWithMessage(legacy).that(AppThemePreferencesStore(context).flow.value).isEqualTo(expected)
        }
    }

    @Test fun `unknown or mismatched saved values fall back one by one`() {
        prefs.edit()
            .putString("mode", "SOMETIMES")
            .putString("light", "MIDNIGHT")
            .putString("dark", "SAGE")
            .commit()
        assertThat(AppThemePreferencesStore(context).flow.value).isEqualTo(AppTheme())

        prefs.edit()
            .putString("mode", "DARK")
            .putString("light", "SAGE")
            .putString("dark", "NOPE")
            .commit()
        assertThat(AppThemePreferencesStore(context).flow.value)
            .isEqualTo(AppTheme(mode = ThemeMode.DARK, light = Palette.SAGE))
    }
}
