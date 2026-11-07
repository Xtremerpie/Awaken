package com.awaken.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

data class AwakenPalette(
    val id: String,
    val name: String,
    val primary: Color,
    val secondary: Color,
    val background: Color,
)

object Palettes {
    val Gold = AwakenPalette("gold", "Sunrise Gold", Color(0xFFFFC24A), Color(0xFFFF7A1A), Color(0xFF050505))
    val Ember = AwakenPalette("ember", "Ember", Color(0xFFFF8A3D), Color(0xFFE5322D), Color(0xFF080403))
    val Dawn = AwakenPalette("dawn", "Dawn Rose", Color(0xFFFFB3A7), Color(0xFFFF6F91), Color(0xFF070405))
    val Aurora = AwakenPalette("aurora", "Aurora", Color(0xFF6FE7C8), Color(0xFF4FA3FF), Color(0xFF030606))
    val Mono = AwakenPalette("mono", "Monochrome", Color(0xFFEDEDED), Color(0xFF9A9A9A), Color(0xFF000000))
    val builtIn = listOf(Gold, Ember, Dawn, Aurora, Mono)
}

val LocalPalette = compositionLocalOf { Palettes.Gold }

private val Display = FontFamily.SansSerif

val AwakenTypography = Typography(
    displayLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.Thin, fontSize = 72.sp, letterSpacing = (-2).sp, lineHeight = 76.sp),
    displayMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.ExtraLight, fontSize = 52.sp, letterSpacing = (-1.5).sp),
    displaySmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.Light, fontSize = 36.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.Light, fontSize = 28.sp, letterSpacing = 0.sp),
    headlineSmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.Normal, fontSize = 22.sp),
    titleLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.Medium, fontSize = 20.sp, letterSpacing = 0.2.sp),
    titleMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.Medium, fontSize = 16.sp, letterSpacing = 0.15.sp),
    bodyLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.8.sp),
    labelMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 1.4.sp),
    labelSmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 1.6.sp),
)

@Composable
fun AwakenTheme(palette: AwakenPalette = Palettes.Gold, content: @Composable () -> Unit) {
    // AWAKEN is always dark: deep black with gold/orange accents.
    @Suppress("UNUSED_VARIABLE") val unused = isSystemInDarkTheme()
    val scheme = darkColorScheme(
        primary = palette.primary,
        onPrimary = Color(0xFF1A0E00),
        secondary = palette.secondary,
        onSecondary = Color(0xFF1A0800),
        tertiary = palette.secondary,
        background = palette.background,
        onBackground = Color(0xFFF4EDE2),
        surface = Color(0xFF0D0B09),
        onSurface = Color(0xFFF4EDE2),
        surfaceVariant = Color(0xFF181410),
        onSurfaceVariant = Color(0xFFB9AE9E),
        surfaceContainer = Color(0xFF110E0B),
        surfaceContainerHigh = Color(0xFF1A1612),
        outline = Color(0xFF4A4036),
        outlineVariant = Color(0xFF2A241D),
        error = Color(0xFFFF6B5E),
    )
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme, typography = AwakenTypography, content = content)
    }
}
