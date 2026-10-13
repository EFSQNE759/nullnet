package com.gizli.messenger.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF00E0A4)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF002018),
    secondary = Color(0xFF7FD7C0),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF121821),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1B2430),
    onSurfaceVariant = Color(0xFFB8C2CC),
    outline = Color(0xFF2A3542),
    error = Color(0xFFFF6B6B)
)

@Composable
fun GizliTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
