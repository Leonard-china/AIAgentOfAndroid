package com.aiagent.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val PrimaryBlue = Color(0xFF21766B)
val PrimaryBlueDark = Color(0xFF8ED7C5)
val SecondaryTeal = Color(0xFF00897B)
val DangerRed = Color(0xFFE53935)
val WarningOrange = Color(0xFFFB8C00)
val SuccessGreen = Color(0xFF43A047)

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryBlueDark,
    secondary = SecondaryTeal,
    tertiary = WarningOrange,
    error = DangerRed,
    background = Color(0xFF141615),
    surface = Color(0xFF1C1F1D),
    surfaceVariant = Color(0xFF282E2A),
    onSurfaceVariant = Color(0xFFADB8AF),
    outlineVariant = Color(0xFF363F38),
    surfaceContainer = Color(0xFF202521),
    surfaceContainerLow = Color(0xFF1B201C),
    surfaceContainerHigh = Color(0xFF282F29),
    primaryContainer = Color(0xFF254D42),
    secondaryContainer = Color(0xFF344338),
    onPrimary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryBlue,
    secondary = SecondaryTeal,
    tertiary = WarningOrange,
    error = DangerRed,
    background = Color(0xFFF7F8F5),
    surface = Color.White,
    surfaceVariant = Color(0xFFEBEFE9),
    onSurfaceVariant = Color(0xFF667168),
    outlineVariant = Color(0xFFDDE3DA),
    surfaceContainer = Color(0xFFF0F3EC),
    surfaceContainerLow = Color(0xFFF4F6F1),
    surfaceContainerHigh = Color(0xFFE9EEE5),
    primaryContainer = Color(0xFFD9EBE5),
    secondaryContainer = Color(0xFFE0E9DE),
    onPrimaryContainer = Color(0xFF193A32),
    onPrimary = Color.White,
    onBackground = Color(0xFF202622),
    onSurface = Color(0xFF202622)
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
