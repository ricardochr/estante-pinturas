package com.ricardochr.estantepinturas.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ricardochr.estantepinturas.R

// Paleta: tinta azul de base, neutros cálidos de papel y un acento magenta para lo destacado
private val Light = lightColorScheme(
    primary = Color(0xFF2A5BA8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDE7F7),
    onPrimaryContainer = Color(0xFF0E2A55),
    secondary = Color(0xFF5B6270),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE7E4EE),
    onSecondaryContainer = Color(0xFF22212B),
    tertiary = Color(0xFFB0306E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF8DCE9),
    onTertiaryContainer = Color(0xFF4A0C2B),
    background = Color(0xFFF4F2EE),
    onBackground = Color(0xFF1C1D22),
    surface = Color(0xFFF4F2EE),
    onSurface = Color(0xFF1C1D22),
    surfaceVariant = Color(0xFFE5E2DC),
    onSurfaceVariant = Color(0xFF5F646E),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBFAF7),
    surfaceContainer = Color(0xFFEDEBE6),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFE8E5DF),
    outline = Color(0xFFC6C2BA),
    outlineVariant = Color(0xFFE0DDD6),
    error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8AB4F2),
    onPrimary = Color(0xFF0B1B33),
    primaryContainer = Color(0xFF1E3354),
    onPrimaryContainer = Color(0xFFD7E5FA),
    secondary = Color(0xFFA6ACB8),
    onSecondary = Color(0xFF1B1D22),
    secondaryContainer = Color(0xFF2D2F38),
    onSecondaryContainer = Color(0xFFE6E4EE),
    tertiary = Color(0xFFF08DBB),
    onTertiary = Color(0xFF3E0A24),
    tertiaryContainer = Color(0xFF5A1D3B),
    onTertiaryContainer = Color(0xFFFBDCEA),
    background = Color(0xFF131418),
    onBackground = Color(0xFFEAE8E4),
    surface = Color(0xFF131418),
    onSurface = Color(0xFFEAE8E4),
    surfaceVariant = Color(0xFF2B2D33),
    onSurfaceVariant = Color(0xFFA3A8B2),
    surfaceContainerLowest = Color(0xFF0E0F12),
    surfaceContainerLow = Color(0xFF17181C),
    surfaceContainer = Color(0xFF1C1E23),
    surfaceContainerHigh = Color(0xFF22242A),
    surfaceContainerHighest = Color(0xFF2A2C33),
    outline = Color(0xFF474B54),
    outlineVariant = Color(0xFF2E3038),
    error = Color(0xFFEF8A82),
)

private val fontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

/** Títulos: Barlow Condensed (se descarga de Google Fonts; si no hay conexión usa la del sistema). */
private val Display = FontFamily(
    Font(GoogleFont("Barlow Condensed"), fontProvider, FontWeight.SemiBold),
    Font(GoogleFont("Barlow Condensed"), fontProvider, FontWeight.Bold),
)

/** Texto: Barlow normal, de la misma familia, muy legible en tamaños chicos. */
private val Body = FontFamily(
    Font(GoogleFont("Barlow"), fontProvider, FontWeight.Normal),
    Font(GoogleFont("Barlow"), fontProvider, FontWeight.Medium),
    Font(GoogleFont("Barlow"), fontProvider, FontWeight.SemiBold),
    Font(GoogleFont("Barlow"), fontProvider, FontWeight.Bold),
)

private val AppTypography = Typography().let { t ->
    fun TextStyle.body() = copy(fontFamily = Body)
    t.copy(
        displaySmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = 0.5.sp),
        headlineMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = 0.5.sp),
        headlineSmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = 0.4.sp),
        titleLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 26.sp, letterSpacing = 0.3.sp),
        titleMedium = t.titleMedium.body().copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.body().copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = t.bodyLarge.body(),
        bodyMedium = t.bodyMedium.body(),
        bodySmall = t.bodySmall.body(),
        labelLarge = t.labelLarge.body().copy(fontWeight = FontWeight.SemiBold),
        labelMedium = t.labelMedium.body().copy(fontWeight = FontWeight.Medium),
        labelSmall = t.labelSmall.body().copy(fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun EstanteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
