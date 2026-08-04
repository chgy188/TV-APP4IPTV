package com.example.composedtv.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 深色主题配色（TV 环境以深色为主）
val TvColorScheme = darkColorScheme(
    primary = Color(0xFF4C9AFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1A3A5C),
    onPrimaryContainer = Color(0xFFB0D4FF),
    secondary = Color(0xFFFF6B35),
    onSecondary = Color.White,
    background = Color(0xFF0F1117),
    onBackground = Color(0xFFE6E9EF),
    surface = Color(0xFF1A1D27),
    onSurface = Color(0xFFE6E9EF),
    surfaceVariant = Color(0xFF252938),
    onSurfaceVariant = Color(0xFF9AA3B2),
    error = Color(0xFFEF4444),
    onError = Color.White,
)

val FavoriteColor = Color(0xFFFFD700)
val OverlayColor = Color(0xAA000000)

@Composable
fun ComposedTVTheme(
    colorScheme: ColorScheme = TvColorScheme,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
