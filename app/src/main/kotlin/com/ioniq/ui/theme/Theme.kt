package com.ioniq.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val IoniqBlue = Color(0xFF003DA5)
private val IoniqDark = Color(0xFF0A1929)
private val IoniqAccent = Color(0xFF00B4E6)
private val IoniqGreen = Color(0xFF4CAF50)

private val DarkColorScheme = darkColorScheme(
    primary = IoniqAccent,
    onPrimary = IoniqDark,
    secondary = IoniqGreen,
    background = IoniqDark,
    surface = Color(0xFF112240),
    onBackground = Color.White,
    onSurface = Color(0xFFE0E0E0),
)

private val LightColorScheme = lightColorScheme(
    primary = IoniqBlue,
    onPrimary = Color.White,
    secondary = IoniqGreen,
    background = Color(0xFFF5F5F5),
    surface = Color.White,
)

@Composable
fun IoniqTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
