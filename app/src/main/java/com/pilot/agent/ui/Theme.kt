package com.pilot.agent.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PilotColorScheme = darkColorScheme(
    primary = Color(0xFF6FD3C0),
    onPrimary = Color(0xFF06201C),
    primaryContainer = Color(0xFF13332E),
    onPrimaryContainer = Color(0xFF9BE8D8),
    secondary = Color(0xFF8AA0B8),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE7EBF2),
    surface = Color(0xFF161A21),
    onSurface = Color(0xFFE7EBF2),
    surfaceVariant = Color(0xFF1E242D),
    onSurfaceVariant = Color(0xFF9AA6B5),
    outline = Color(0xFF33404F),
    error = Color(0xFFEF7A6D),
    onError = Color(0xFF2A0B08),
)

@Composable
fun PilotTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PilotColorScheme, content = content)
}
