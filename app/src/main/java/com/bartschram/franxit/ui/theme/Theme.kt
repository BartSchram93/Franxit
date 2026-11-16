package com.bartschram.franxit.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val PriceDown = Color(0xFF1F9D61)
val PriceUp = Color(0xFFD9483B)
val ChartLine = Color(0xFF2F6FDB)
val Warning = Color(0xFFD9822B)

private val Light = lightColorScheme(
    primary = Color(0xFF111111),
    onPrimary = Color.White,
    secondaryContainer = Color(0xFFEAEAE6),
    onSecondaryContainer = Color(0xFF111111),
    background = Color(0xFFF6F6F3),
    onBackground = Color(0xFF111111),
    surface = Color(0xFFF6F6F3),
    onSurface = Color(0xFF111111),
    surfaceContainerLowest = Color.White,
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFF707070),
    outlineVariant = Color(0xFFE7E7E3),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFF2F2F2),
    onPrimary = Color(0xFF111111),
    secondaryContainer = Color(0xFF2A2A2A),
    onSecondaryContainer = Color(0xFFF2F2F2),
    background = Color(0xFF0F0F0F),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF0F0F0F),
    onSurface = Color(0xFFF2F2F2),
    surfaceContainerLowest = Color(0xFF1A1A1A),
    surfaceContainer = Color(0xFF1A1A1A),
    surfaceContainerHigh = Color(0xFF222222),
    onSurfaceVariant = Color(0xFF9A9A9A),
    outlineVariant = Color(0xFF2A2A2A),
)

@Composable
fun FranxitTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
