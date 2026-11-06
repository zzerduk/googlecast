package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val StudioDarkColorScheme = darkColorScheme(
    primary = CyanAccent,
    onPrimary = StudioBackground,
    primaryContainer = CyanContainer,
    onPrimaryContainer = CyanAccent,
    secondary = AmberAccent,
    onSecondary = StudioBackground,
    secondaryContainer = AmberContainer,
    onSecondaryContainer = AmberAccent,
    tertiary = EmeraldLive,
    background = StudioBackground,
    onBackground = TextPrimary,
    surface = StudioSurface,
    onSurface = TextPrimary,
    surfaceVariant = StudioSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    error = ErrorCoral
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = StudioDarkColorScheme,
        typography = Typography,
        content = content
    )
}
