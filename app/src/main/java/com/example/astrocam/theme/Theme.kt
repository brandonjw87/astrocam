package com.example.astrocam.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Red-on-black "night vision" palette so the screen doesn't ruin dark adaptation. */
private val NightColorScheme =
  darkColorScheme(
    primary = Color(0xFFE53935),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF4A0E0E),
    onPrimaryContainer = Color(0xFFFFB4AB),
    secondary = Color(0xFFB71C1C),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF3A0B0B),
    onSecondaryContainer = Color(0xFFFF8A80),
    background = Color.Black,
    onBackground = Color(0xFFFF6E6E),
    surface = Color(0xFF0A0000),
    onSurface = Color(0xFFFF6E6E),
    surfaceVariant = Color(0xFF1A0505),
    onSurfaceVariant = Color(0xFFCC5555),
    outline = Color(0xFF7A2020),
    error = Color(0xFFFFB4AB),
  )

@Composable
fun AstroCamTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = NightColorScheme, typography = Typography, content = content)
}
