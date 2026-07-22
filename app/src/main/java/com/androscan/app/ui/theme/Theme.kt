package com.androscan.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Green = Color(0xFF1B4D3E)
private val GreenLight = Color(0xFF2E7A63)
private val Accent = Color(0xFFC4A35A)
private val SurfaceLight = Color(0xFFF4F7F5)
private val SurfaceDark = Color(0xFF121A17)

private val LightColors = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    secondary = Accent,
    onSecondary = Color(0xFF1A1200),
    background = SurfaceLight,
    onBackground = Color(0xFF14201B),
    surface = Color.White,
    onSurface = Color(0xFF14201B),
    surfaceVariant = Color(0xFFE2EBE6),
    onSurfaceVariant = Color(0xFF3D4F47)
)

private val DarkColors = darkColorScheme(
    primary = GreenLight,
    onPrimary = Color.White,
    secondary = Accent,
    onSecondary = Color(0xFF1A1200),
    background = SurfaceDark,
    onBackground = Color(0xFFE8F0EC),
    surface = Color(0xFF1A2420),
    onSurface = Color(0xFFE8F0EC),
    surfaceVariant = Color(0xFF2A3832),
    onSurfaceVariant = Color(0xFFB8C9C0)
)

@Composable
fun AndroscanTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
