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

internal fun quireTypography(lora: FontFamily) = Typography(
    displaySmall = TextStyle(
        fontFamily = lora,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        letterSpacing = (-0.01).em,
    ),
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
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        letterSpacing = 0.14.em,
    ),
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
)
