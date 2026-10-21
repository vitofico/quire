package io.theficos.ereader.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

private val Surface          = Color(0xFFF8F4EC)
private val SurfaceContainer = Color(0xFFFEFBF4)
private val Outline          = Color(0xFFEFE5D2)
private val OnSurface        = Color(0xFF1F1A14)
private val OnSurfaceMuted   = Color(0xFF8A7355)
private val Accent           = Color(0xFF7A2E2A)
private val AccentDeep       = Color(0xFF4A1A18)
private val OnAccent         = Color(0xFFF5EFE3)

private val DarkSurface          = Color(0xFF1A1612)
private val DarkSurfaceContainer = Color(0xFF241F18)
private val DarkOutline          = Color(0xFF3A322A)
private val DarkOnSurface        = Color(0xFFEDE5D5)
private val DarkOnSurfaceMuted   = Color(0xFF9A8B72)
private val DarkAccent           = Color(0xFFC26A66)
private val DarkAccentDeep       = Color(0xFFA04846)
private val DarkOnAccent         = Color(0xFF1A1612)

internal val QuireLightColors = lightColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
    primaryContainer = AccentDeep,
    onPrimaryContainer = OnAccent,
    background = Surface,
    onBackground = OnSurface,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceContainer,
    onSurfaceVariant = OnSurfaceMuted,
    outline = Outline,
    outlineVariant = Outline,
)

internal val QuireDarkColors = darkColorScheme(
    primary = DarkAccent,
    onPrimary = DarkOnAccent,
    primaryContainer = DarkAccentDeep,
    onPrimaryContainer = DarkOnAccent,
    background = DarkSurface,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceContainer,
    onSurfaceVariant = DarkOnSurfaceMuted,
    outline = DarkOutline,
    outlineVariant = DarkOutline,
)

internal val QuireSageColors = lightColorScheme(
    primary = Color(0xFF355E43),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD2E8CE),
    onPrimaryContainer = Color(0xFF193820),
    secondary = Color(0xFF50634C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE7D4),
    onSecondaryContainer = Color(0xFF283D25),
    tertiary = Color(0xFF675A35),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEFE2B7),
    onTertiaryContainer = Color(0xFF3F3314),
    background = Color(0xFFEFF3E9),
    onBackground = Color(0xFF222D23),
    surface = Color(0xFFEFF3E9),
    onSurface = Color(0xFF222D23),
    surfaceVariant = Color(0xFFE3EBDD),
    onSurfaceVariant = Color(0xFF4C5E4D),
    surfaceTint = Color(0xFF355E43),
    surfaceDim = Color(0xFFD5DECF),
    surfaceBright = Color(0xFFF5F8F1),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFEAF0E3),
    surfaceContainer = Color(0xFFE4EBDC),
    surfaceContainerHigh = Color(0xFFDDE6D5),
    surfaceContainerHighest = Color(0xFFD6DFCE),
    outline = Color(0xFF71816D),
    outlineVariant = Color(0xFFC3CFBC),
    inverseSurface = Color(0xFF28372B),
    inverseOnSurface = Color(0xFFE8F1E4),
    inversePrimary = Color(0xFFAED2A6),
)

internal val QuireMidnightColors = darkColorScheme(
    primary = Color(0xFFABC7FF),
    onPrimary = Color(0xFF142F59),
    primaryContainer = Color(0xFF28466D),
    onPrimaryContainer = Color(0xFFDBE8FF),
    secondary = Color(0xFFB9C7DF),
    onSecondary = Color(0xFF253247),
    secondaryContainer = Color(0xFF30405A),
    onSecondaryContainer = Color(0xFFDAE5F7),
    tertiary = Color(0xFFD8BFE8),
    onTertiary = Color(0xFF3E2B4E),
    tertiaryContainer = Color(0xFF554165),
    onTertiaryContainer = Color(0xFFF1DAFF),
    background = Color(0xFF111C2B),
    onBackground = Color(0xFFDFE7F3),
    surface = Color(0xFF111C2B),
    onSurface = Color(0xFFDFE7F3),
    surfaceVariant = Color(0xFF26364B),
    onSurfaceVariant = Color(0xFFBBC8DA),
    surfaceTint = Color(0xFFABC7FF),
    surfaceDim = Color(0xFF0C1522),
    surfaceBright = Color(0xFF324157),
    surfaceContainerLowest = Color(0xFF0C1522),
    surfaceContainerLow = Color(0xFF152131),
    surfaceContainer = Color(0xFF1B293C),
    surfaceContainerHigh = Color(0xFF223249),
    surfaceContainerHighest = Color(0xFF2B3C54),
    outline = Color(0xFF8797AD),
    outlineVariant = Color(0xFF3D4F66),
    inverseSurface = Color(0xFFDFE7F3),
    inverseOnSurface = Color(0xFF26364B),
    inversePrimary = Color(0xFF355B91),
)
