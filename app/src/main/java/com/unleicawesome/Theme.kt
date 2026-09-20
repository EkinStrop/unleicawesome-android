package com.unleicawesome

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFE63946),
    onPrimary = Color.White,
    secondary = Color(0xFFE63946),
    onSecondary = Color.White,
    background = Color(0xFF111111),
    onBackground = Color(0xFFD2D2D2),
    surface = Color(0xFF1C1C1C),
    onSurface = Color(0xFFD2D2D2),
    surfaceVariant = Color(0xFF333333),
    onSurfaceVariant = Color(0xFF888888)
)

@Composable
fun UnleicawesomeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
