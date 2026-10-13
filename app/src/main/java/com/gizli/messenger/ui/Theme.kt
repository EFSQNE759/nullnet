package com.gizli.messenger.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DefaultColors = darkColorScheme(
    primary = Color(0xFF00E0A4),
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

private val AmoledColors = darkColorScheme(
    primary = Color(0xFF00E0A4),
    onPrimary = Color(0xFF000000),
    secondary = Color(0xFF00B487),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF0A0A0A),
    onSurfaceVariant = Color(0xFF9AA4AE),
    outline = Color(0xFF1A1A1A),
    error = Color(0xFFFF6B6B)
)

private val OceanColors = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    onPrimary = Color(0xFF00202E),
    secondary = Color(0xFF81D4FA),
    background = Color(0xFF071A24),
    onBackground = Color(0xFFE1F5FE),
    surface = Color(0xFF0C2632),
    onSurface = Color(0xFFE1F5FE),
    surfaceVariant = Color(0xFF14384A),
    onSurfaceVariant = Color(0xFFB3D9E8),
    outline = Color(0xFF1E4A5F),
    error = Color(0xFFFF6B6B)
)

private val SunsetColors = darkColorScheme(
    primary = Color(0xFFFF8A65),
    onPrimary = Color(0xFF331000),
    secondary = Color(0xFFFFB74D),
    background = Color(0xFF1A0F14),
    onBackground = Color(0xFFFDE7E0),
    surface = Color(0xFF26161C),
    onSurface = Color(0xFFFDE7E0),
    surfaceVariant = Color(0xFF3A2029),
    onSurfaceVariant = Color(0xFFE0BFB8),
    outline = Color(0xFF4A2A34),
    error = Color(0xFFFF5252)
)

private val ForestColors = darkColorScheme(
    primary = Color(0xFF81C784),
    onPrimary = Color(0xFF0A2410),
    secondary = Color(0xFFAED581),
    background = Color(0xFF0B140C),
    onBackground = Color(0xFFE8F5E9),
    surface = Color(0xFF142016),
    onSurface = Color(0xFFE8F5E9),
    surfaceVariant = Color(0xFF1E2E20),
    onSurfaceVariant = Color(0xFFC3D9C5),
    outline = Color(0xFF2C4030),
    error = Color(0xFFFF6B6B)
)

private val VioletColors = darkColorScheme(
    primary = Color(0xFFB388FF),
    onPrimary = Color(0xFF1B0033),
    secondary = Color(0xFFEA80FC),
    background = Color(0xFF120A1A),
    onBackground = Color(0xFFF3E5F5),
    surface = Color(0xFF1C1126),
    onSurface = Color(0xFFF3E5F5),
    surfaceVariant = Color(0xFF2A1A38),
    onSurfaceVariant = Color(0xFFD4C2E0),
    outline = Color(0xFF3A2648),
    error = Color(0xFFFF6B6B)
)

data class ThemeOption(val id: String, val label: String)

val themeOptions = listOf(
    ThemeOption("default", "Mevcut (Yeşil)"),
    ThemeOption("amoled", "AMOLED (Siyah)"),
    ThemeOption("ocean", "Okyanus"),
    ThemeOption("sunset", "Gün Batımı"),
    ThemeOption("forest", "Orman"),
    ThemeOption("violet", "Mor")
)

private fun schemeFor(id: String) = when (id) {
    "amoled" -> AmoledColors
    "ocean" -> OceanColors
    "sunset" -> SunsetColors
    "forest" -> ForestColors
    "violet" -> VioletColors
    else -> DefaultColors
}

@Composable
fun GizliTheme(themeId: String = "default", content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = schemeFor(themeId), content = content)
}
