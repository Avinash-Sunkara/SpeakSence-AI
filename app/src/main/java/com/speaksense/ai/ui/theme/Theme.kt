package com.speaksense.ai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Green = Color(0xFF2ECC71)
val GreenDark = Color(0xFF1E4D36)
val Amber = Color(0xFFFFC857)
val Danger = Color(0xFFFF6B6B)
val Muted = Color(0xFF9BB5A8)

private val DarkColors = darkColorScheme(
    primary = Green,
    onPrimary = Color(0xFF04210F),
    secondary = Color(0xFF9BE8BC),
    onSecondary = Color(0xFF04210F),
    secondaryContainer = GreenDark,
    onSecondaryContainer = Color.White,
    background = Color(0xFF0A110E),
    onBackground = Color.White,
    surface = Color(0xFF111B16),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1A2822),
    onSurfaceVariant = Color(0xFFD5E6DC),
    error = Danger,
    outline = Color(0xFF3C5448),
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold),
)

@Composable
fun SpeakSenseTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, typography = AppTypography, content = content)
}
