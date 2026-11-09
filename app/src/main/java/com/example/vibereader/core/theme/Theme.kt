package com.example.vibereader.core.theme

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * VibeReader 2026 design system.
 *
 * The palette stays deliberately monochrome, but each role now has a distinct
 * surface-container tone. That gives cards, sheets and chips a soft, layered
 * depth instead of the previous flat black/white blocks.
 */

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFF7F7FA),
    onPrimary = Color(0xFF141419),
    primaryContainer = Color(0xFF2A2A32),
    onPrimaryContainer = Color(0xFFF7F7FA),
    secondary = Color(0xFFC5C5CF),
    onSecondary = Color(0xFF1A1A20),
    secondaryContainer = Color(0xFF24242B),
    onSecondaryContainer = Color(0xFFECECF2),
    tertiary = Color(0xFFA9A9B6),
    onTertiary = Color(0xFF1A1A20),
    tertiaryContainer = Color(0xFF202027),
    onTertiaryContainer = Color(0xFFE7E7EE),
    background = Color(0xFF0B0B0F),
    onBackground = Color(0xFFF4F4F8),
    surface = Color(0xFF101015),
    onSurface = Color(0xFFF4F4F8),
    surfaceVariant = Color(0xFF1A1A21),
    onSurfaceVariant = Color(0xFFB6B6C2),
    surfaceTint = Color(0xFFF7F7FA),
    surfaceBright = Color(0xFF2A2A32),
    surfaceDim = Color(0xFF08080B),
    surfaceContainerLowest = Color(0xFF08080B),
    surfaceContainerLow = Color(0xFF121218),
    surfaceContainer = Color(0xFF15151B),
    surfaceContainerHigh = Color(0xFF1B1B22),
    surfaceContainerHighest = Color(0xFF22222A),
    outline = Color(0xFF3A3A45),
    outlineVariant = Color(0xFF26262E),
    inverseSurface = Color(0xFFF4F4F8),
    inverseOnSurface = Color(0xFF1A1A20),
    inversePrimary = Color(0xFF1A1A20),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color(0xFF000000),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1A1A20),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEAEAF0),
    onPrimaryContainer = Color(0xFF1A1A20),
    secondary = Color(0xFF565660),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF0F0F5),
    onSecondaryContainer = Color(0xFF2A2A31),
    tertiary = Color(0xFF70707B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF3F3F7),
    onTertiaryContainer = Color(0xFF2A2A31),
    background = Color(0xFFF6F6FA),
    onBackground = Color(0xFF1A1A20),
    surface = Color(0xFFFCFCFE),
    onSurface = Color(0xFF1A1A20),
    surfaceVariant = Color(0xFFF0F0F5),
    onSurfaceVariant = Color(0xFF5E5E69),
    surfaceTint = Color(0xFF1A1A20),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFEAEAF0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFAFAFD),
    surfaceContainerHigh = Color(0xFFF1F1F6),
    surfaceContainerHighest = Color(0xFFEBEBF1),
    outline = Color(0xFFD6D6DE),
    outlineVariant = Color(0xFFE7E7EE),
    inverseSurface = Color(0xFF2E2E35),
    inverseOnSurface = Color(0xFFF6F6FA),
    inversePrimary = Color(0xFFF7F7FA),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    scrim = Color(0xFF000000),
)

private val OledColorScheme = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF1F1F25),
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = Color(0xFFC8C8D2),
    onSecondary = Color(0xFF141419),
    secondaryContainer = Color(0xFF1A1A20),
    onSecondaryContainer = Color(0xFFF0F0F5),
    tertiary = Color(0xFFA9A9B6),
    onTertiary = Color(0xFF141419),
    tertiaryContainer = Color(0xFF17171C),
    onTertiaryContainer = Color(0xFFEDEDF3),
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF121217),
    onSurfaceVariant = Color(0xFFC2C2CC),
    surfaceTint = Color.White,
    surfaceBright = Color(0xFF24242B),
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF09090C),
    surfaceContainer = Color(0xFF0D0D11),
    surfaceContainerHigh = Color(0xFF131318),
    surfaceContainerHighest = Color(0xFF1A1A20),
    outline = Color(0xFF3D3D48),
    outlineVariant = Color(0xFF24242C),
    inverseSurface = Color(0xFFF4F4F8),
    inverseOnSurface = Color(0xFF141419),
    inversePrimary = Color(0xFF141419),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color.Black,
)

private val SepiaColorScheme = lightColorScheme(
    primary = Color(0xFF3A3127),
    onPrimary = Color(0xFFFFFAF1),
    primaryContainer = Color(0xFFE7DBC9),
    onPrimaryContainer = Color(0xFF2C251D),
    secondary = Color(0xFF665C4F),
    onSecondary = Color(0xFFFFFAF1),
    secondaryContainer = Color(0xFFF3E9DA),
    onSecondaryContainer = Color(0xFF3A3127),
    tertiary = Color(0xFF776B5C),
    onTertiary = Color(0xFFFFFAF1),
    tertiaryContainer = Color(0xFFF6EDDF),
    onTertiaryContainer = Color(0xFF3A3127),
    background = Color(0xFFF4EDE0),
    onBackground = Color(0xFF2D261E),
    surface = Color(0xFFFBF5EA),
    onSurface = Color(0xFF2D261E),
    surfaceVariant = Color(0xFFEDE3D3),
    onSurfaceVariant = Color(0xFF5C5347),
    surfaceTint = Color(0xFF3A3127),
    surfaceBright = Color(0xFFFFFBF3),
    surfaceDim = Color(0xFFE4D9C8),
    surfaceContainerLowest = Color(0xFFFFFBF4),
    surfaceContainerLow = Color(0xFFFFFAF1),
    surfaceContainer = Color(0xFFF8F1E5),
    surfaceContainerHigh = Color(0xFFEFE5D5),
    surfaceContainerHighest = Color(0xFFE8DDCB),
    outline = Color(0xFFC6B8A5),
    outlineVariant = Color(0xFFDCD0BF),
    inverseSurface = Color(0xFF3A3127),
    inverseOnSurface = Color(0xFFFFFAF1),
    inversePrimary = Color(0xFFF4EDE0),
    error = Color(0xFF9B2F22),
    onError = Color(0xFFFFFAF1),
    errorContainer = Color(0xFFFFDAD4),
    onErrorContainer = Color(0xFF3E0A03),
    scrim = Color(0xFF000000),
)

/**
 * Modern, tightly tracked type scale. Headers use Medium/SemiBold weights and
 * a 1sp letter-spacing for that premium editorial feel; long-form body copy
 * stays Regular and airy for comfortable reading.
 */
private val PremiumTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.6.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.8.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 1.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.3.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.2.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.2.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.15.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.4.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.sp,
    ),
)

private val PremiumShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun VibeReaderTheme(
    themeOption: ThemeOption = ThemeOption.SYSTEM,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = when (themeOption) {
        ThemeOption.OLED_BLACK -> OledColorScheme
        ThemeOption.SEPIA -> SepiaColorScheme
        ThemeOption.LIGHT -> LightColorScheme
        ThemeOption.SYSTEM -> if (darkTheme) DarkColorScheme else LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = PremiumTypography,
        shapes = PremiumShapes,
        content = content,
    )
}
