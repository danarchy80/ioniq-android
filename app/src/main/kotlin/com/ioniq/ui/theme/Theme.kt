package com.ioniq.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.ioniq.R

// ─────────────────── Electric Telemetry System palette ───────────────────

val IoniqBg = Color(0xFF0A1929)          // Deep obsidian navy canvas
val IoniqSurface = Color(0xFF112240)     // Card / module fill
val IoniqSurfaceHigh = Color(0xFF172A46) // Surface highlight / trough
val IoniqBorder = Color(0xFF1D3557)      // Structural reticle / border
val IoniqCyan = Color(0xFF00B4E6)        // Primary accent
val IoniqEmerald = Color(0xFF10B981)     // Positive / healthy
val IoniqAmber = Color(0xFFF59E0B)       // Warning / discharge
val IoniqCrimson = Color(0xFFEF4444)     // Critical
val IoniqTextHi = Color(0xFFF1F5F9)      // High-emphasis
val IoniqTextMid = Color(0xFF94A3B8)     // Medium-emphasis
val IoniqTextLow = Color(0xFF64748B)     // Low-emphasis

// ─────────────────── Fonts ───────────────────

val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
)

// ─────────────────── Typography ───────────────────

private val IoniqTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold,
        fontSize = 56.sp, lineHeight = 64.sp, letterSpacing = (-1.5).sp
    ),
    displayMedium = TextStyle(
        fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold,
        fontSize = 40.sp, lineHeight = 48.sp, letterSpacing = (-1).sp
    ),
    headlineLarge = TextStyle(
        fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold,
        fontSize = 28.sp, lineHeight = 36.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold,
        fontSize = 22.sp, lineHeight = 28.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium,
        fontSize = 18.sp, lineHeight = 24.sp
    ),
    titleLarge = TextStyle(
        fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold,
        fontSize = 22.sp, lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium,
        fontSize = 16.sp, lineHeight = 24.sp
    ),
    titleSmall = TextStyle(
        fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = SpaceGrotesk, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = SpaceGrotesk, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = SpaceGrotesk, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 16.sp
    ),
    labelLarge = TextStyle(
        fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.sp
    ),
    labelSmall = TextStyle(
        fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium,
        fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 1.sp
    ),
)

// Cockpit design system is dark-only (preserves dark adaptation in-cabin).
private val CockpitColorScheme = darkColorScheme(
    primary = IoniqCyan,
    onPrimary = IoniqBg,
    secondary = IoniqEmerald,
    onSecondary = Color(0xFF003824),
    tertiary = IoniqAmber,
    error = IoniqCrimson,
    background = IoniqBg,
    onBackground = IoniqTextHi,
    surface = IoniqSurface,
    onSurface = IoniqTextHi,
    surfaceVariant = IoniqSurfaceHigh,
    onSurfaceVariant = IoniqTextMid,
    outline = IoniqBorder,
    outlineVariant = IoniqBorder,
)

@Composable
fun IoniqTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = CockpitColorScheme,
        typography = IoniqTypography,
        content = content
    )
}
