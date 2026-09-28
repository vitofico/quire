package io.theficos.ereader.ui.theme

import android.content.Context
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class QuireTypographyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `the UI's Lora ships in the APK`() {
        // The file lives in the reader module's assets. A blocking asset font that fails to load
        // throws on first draw, so moving or renaming it would crash the app's first screen.
        context.assets.open(LORA_ASSET).use { assertThat(it.read()).isNotEqualTo(-1) }
    }

    @Test fun `each Lora weight draws that weight from the variable font`() {
        // Without the variation setting, SemiBold would draw the file's default regular outlines.
        val fonts = (loraFontFamily(context.assets) as FontListFontFamily).fonts
        assertThat(fonts.map { it.weight }).containsExactly(FontWeight.Normal, FontWeight.SemiBold)
        fonts.forEach { font ->
            val wght = (font as AndroidFont).variationSettings.settings.single { it.axisName == "wght" }
            assertWithMessage("${font.weight}").that(wght.toVariationValue(null))
                .isEqualTo(font.weight.weight.toFloat())
        }
    }
}
