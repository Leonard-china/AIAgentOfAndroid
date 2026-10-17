package com.aiagent.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val PrimaryBlue = Color(0xFF1E88E5)
val PrimaryBlueDark = Color(0xFF90CAF9)
val SecondaryTeal = Color(0xFF00897B)
val DangerRed = Color(0xFFE53935)
val WarningOrange = Color(0xFFFB8C00)
val SuccessGreen = Color(0xFF43A047)

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryBlueDark,
    secondary = SecondaryTeal,
    tertiary = WarningOrange,
    error = DangerRed,
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    onPrimary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryBlue,
    secondary = SecondaryTeal,
    tertiary = WarningOrange,
    error = DangerRed,
    background = Color(0xFFF8F9FA),
    surface = Color.White,
    onPrimary = Color.White,
    onBackground = Color(0xFF202124),
    onSurface = Color(0xFF202124)
)

@Composable
fun AIAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
