package com.khossastudio.agent.ui.compose

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Cores estilo Siri para o orbe e elementos de destaque. */
object SiriPalette {
    val Blue = Color(0xFF00A2FF)
    val Purple = Color(0xFF9D00FF)
    val Pink = Color(0xFFFF00C8)
    val Cyan = Color(0xFF00FFF2)
    val Highlight = Color(0xFFFFFFFF)

    val Ring = listOf(Blue, Purple, Pink, Cyan, Blue)
}

private val DarkColorScheme = darkColorScheme(
    primary = SiriPalette.Blue,
    secondary = SiriPalette.Purple,
    tertiary = SiriPalette.Pink,
    background = Color(0xFF0A0A0A),
    surface = Color(0xFF1A1A1A),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFFE0E0E0),
    onSurface = Color(0xFFE0E0E0),
    error = Color(0xFFFF4B4B)
)

private val LightColorScheme = lightColorScheme(
    primary = SiriPalette.Blue,
    secondary = SiriPalette.Purple,
    tertiary = SiriPalette.Pink,
    background = Color(0xFFF5F5F7),
    surface = Color.White,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFF1D1D1F),
    onSurface = Color(0xFF1D1D1F),
    error = Color(0xFFD32F2F)
)

@Composable
fun KhossaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
