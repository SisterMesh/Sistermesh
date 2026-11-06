package com.sistermesh.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Scheme = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    secondary = Mint,
    onSecondary = Teal,
    background = Canvas,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Fog,
    onSurfaceVariant = Muted,
    outline = Line,
    error = Coral
)

@Composable
fun SisterMeshTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = SmTypography, content = content)
}
