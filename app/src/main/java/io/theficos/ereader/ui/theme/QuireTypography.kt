package io.theficos.ereader.ui.theme

import android.content.res.AssetManager
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * The full Lora the reader bundles for books (reader/src/main/assets/fonts), which the app's own
 * screens share rather than shipping a second, trimmed copy that lacked most accented and
 * Cyrillic letters.
 */
internal const val LORA_ASSET = "fonts/Lora.ttf"

/**
 * Lora at each weight the UI uses. The file is a variable font (weights 400 to 700), so every
 * weight is an instance of that one file: the variation setting is what makes SemiBold a real
 * 600 rather than the regular outlines thickened by synthesis.
 */
internal fun loraFontFamily(assets: AssetManager): FontFamily = FontFamily(
    lora(assets, FontWeight.Normal),
    lora(assets, FontWeight.SemiBold),
)

private fun lora(assets: AssetManager, weight: FontWeight) = Font(
    path = LORA_ASSET,
    assetManager = assets,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

/**
 * The full type scale, so no style falls back to Material's Roboto default: headings and book
 * titles (display, headline, titleLarge, titleMedium) are Lora SemiBold, everything else is the
 * system sans. Sizes and line heights are Material's unless tuned here.
 */
internal fun quireTypography(lora: FontFamily) = Typography().run {
    fun TextStyle.lora() = copy(fontFamily = lora, fontWeight = FontWeight.SemiBold)
    fun TextStyle.sans(weight: FontWeight) = copy(fontFamily = FontFamily.SansSerif, fontWeight = weight)
    copy(
        displayLarge = displayLarge.lora(),
        displayMedium = displayMedium.lora(),
        displaySmall = TextStyle(
            fontFamily = lora,
            fontWeight = FontWeight.SemiBold,
            fontSize = 28.sp,
            letterSpacing = (-0.01).em,
        ),
        headlineLarge = headlineLarge.lora(),
        headlineMedium = headlineMedium.lora(),
        headlineSmall = headlineSmall.lora(),
        titleLarge = titleLarge.lora(),
        titleMedium = TextStyle(
            fontFamily = lora,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            lineHeight = 20.sp,
        ),
        titleSmall = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        ),
        bodyLarge = bodyLarge.sans(FontWeight.Normal),
        bodyMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        ),
        bodySmall = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
        ),
        labelLarge = labelLarge.sans(FontWeight.Medium),
        labelMedium = labelMedium.sans(FontWeight.Medium),
        labelSmall = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            letterSpacing = 0.14.em,
        ),
    )
}
