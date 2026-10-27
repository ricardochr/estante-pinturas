package com.ricardochr.estantepinturas.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFF1F5FAE),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE2EBF7),
    onPrimaryContainer = Color(0xFF0D2547),
    secondary = Color(0xFF5B6270),
    secondaryContainer = Color(0xFFE6E4DF),
    onSecondaryContainer = Color(0xFF1D1F24),
    background = Color(0xFFF3F2EF),
    onBackground = Color(0xFF1D1F24),
    surface = Color(0xFFF3F2EF),
    onSurface = Color(0xFF1D1F24),
    surfaceVariant = Color(0xFFE6E4DF),
    onSurfaceVariant = Color(0xFF646A75),
    surfaceContainer = Color(0xFFEAE8E3),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    outline = Color(0xFFC9C6BF),
    outlineVariant = Color(0xFFDDDBD5),
    error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF6EA4EC),
    onPrimary = Color(0xFF0D1520),
    primaryContainer = Color(0xFF1F2D42),
    onPrimaryContainer = Color(0xFFD6E4F8),
    secondary = Color(0xFF9AA0AA),
    secondaryContainer = Color(0xFF2E323A),
    onSecondaryContainer = Color(0xFFEBEAE6),
    background = Color(0xFF15171B),
    onBackground = Color(0xFFEBEAE6),
    surface = Color(0xFF15171B),
    onSurface = Color(0xFFEBEAE6),
    surfaceVariant = Color(0xFF2E323A),
    onSurfaceVariant = Color(0xFF9AA0AA),
    surfaceContainer = Color(0xFF1B1E23),
    surfaceContainerLow = Color(0xFF1E2127),
    surfaceContainerHigh = Color(0xFF242830),
    outline = Color(0xFF454A54),
    outlineVariant = Color(0xFF2E323A),
    error = Color(0xFFEF8A82),
)

private val Condensed = FontFamily.SansSerif

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineSmall = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = 0.5.sp),
        titleLarge = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = 0.3.sp),
    )
}

@Composable
fun EstanteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = AppTypography,
        content = content,
    )
}
