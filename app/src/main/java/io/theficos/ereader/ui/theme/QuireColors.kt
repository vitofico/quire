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
