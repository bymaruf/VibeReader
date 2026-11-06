package com.example.vibereader.core.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = Color.White,
    secondary = Color(0xFFBDBDBD),
    tertiary = Color(0xFFE0E0E0),
    background = Color.Black,
    surface = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White,
    surfaceVariant = Color.Black,
    onSurfaceVariant = Color(0xFFE0E0E0),
    outline = Color.White.copy(alpha = 0.12f),
)

private val LightColorScheme = lightColorScheme(
    primary = Color.Black,
    secondary = Color(0xFF424242),
    tertiary = Color(0xFF616161),
    background = Color.White,
    surface = Color.White,
    onBackground = Color.Black,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFF0F0F0),
    onSurfaceVariant = Color(0xFF262626),
    outline = Color(0xFFBDBDBD),
)

@Composable
fun VibeReaderTheme(
    themeOption: ThemeOption = ThemeOption.SYSTEM,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = when (themeOption) {
        ThemeOption.OLED_BLACK -> darkColorScheme(
            primary = Color.White,
            secondary = Color(0xFFBDBDBD),
            tertiary = Color(0xFFE0E0E0),
            background = Color.Black,
            surface = Color.Black,
            onBackground = Color.White,
            onSurface = Color.White,
            outline = Color.White.copy(alpha = 0.12f),
            surfaceVariant = Color.Black,
            onSurfaceVariant = Color(0xFFE0E0E0),
        )
        ThemeOption.SEPIA -> lightColorScheme(
            primary = Color(0xFF2D2D2D),
            secondary = Color(0xFF5C554B),
            tertiary = Color(0xFF716A60),
            background = Color(0xFFF8F1E5),
            surface = Color(0xFFF8F1E5),
            onBackground = Color(0xFF2D2D2D),
            onSurface = Color(0xFF2D2D2D),
            surfaceVariant = Color(0xFFECE4D7),
            onSurfaceVariant = Color(0xFF3B352D),
            outline = Color(0xFFB9AE9D),
        )
        ThemeOption.LIGHT -> lightColorScheme(
            primary = Color.Black,
            secondary = Color(0xFF424242),
            tertiary = Color(0xFF616161),
            background = Color.White,
            surface = Color.White,
            onBackground = Color.Black,
            onSurface = Color.Black,
            surfaceVariant = Color(0xFFF0F0F0),
            onSurfaceVariant = Color(0xFF262626),
            outline = Color(0xFFBDBDBD),
        )
        ThemeOption.SYSTEM -> if (darkTheme) DarkColorScheme else LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}