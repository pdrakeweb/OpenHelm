package dev.openhelm.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * One fixed dark theme. A cockpit at night is the design target: dark surfaces, restrained
 * accents, no light mode to blind anyone at 02:00.
 */
private val OpenHelmColors = darkColorScheme(
    primary = Color(0xFF64B5F6),
    onPrimary = Color(0xFF06263F),
    secondary = Color(0xFF80CBC4),
    background = Color(0xFF0B1620),
    onBackground = Color(0xFFDDE7EE),
    surface = Color(0xFF122334),
    onSurface = Color(0xFFDDE7EE),
    surfaceVariant = Color(0xFF1B3247),
    onSurfaceVariant = Color(0xFFB8C7D4),
    error = Color(0xFFEF9A9A),
)

@Composable
fun OpenHelmTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = OpenHelmColors, content = content)
}
