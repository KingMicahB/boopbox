package com.cardplayer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Warm, playful palette (cream + orange, Yoto-ish).
val Cream = Color(0xFFFFF6E9)
val CreamDark = Color(0xFFFFEBD2)
val Orange = Color(0xFFFF7A00)
val Teal = Color(0xFF2EC4B6)
val Sunny = Color(0xFFFFC857)
val Ink = Color(0xFF2B2118)
val InkSoft = Color(0xFF6B5A4A)
val Berry = Color(0xFFE63970)

// Night versions: warm charcoal instead of pure black, cream text.
val Night = Color(0xFF1C1612)
val NightSurface = Color(0xFF2A2219)
val NightSurfaceVariant = Color(0xFF3A3024)
val NightText = Color(0xFFFFF1DE)
val NightTextSoft = Color(0xFFC9B9A4)

/** Colours a card can be given in the editor. */
val CardColorChoices: List<Long> = listOf(
    0xFFFFB74DL, 0xFF2EC4B6L, 0xFFE63970L, 0xFF4F9DFFL,
    0xFF6CC551L, 0xFF9B5DE5L, 0xFFFFC857L, 0xFFFF7A00L
)

val EmojiChoices: List<String> = listOf(
    "🎵", "🎶", "🎧", "📚", "🎸", "🥁", "🎹", "🎤",
    "🦁", "🐻", "🐸", "🦄", "🐶", "🐱", "🦊", "🐧",
    "🚂", "🚀", "🚜", "⛵", "🌈", "⭐", "🌙", "☀️",
    "🍎", "🍓", "🦖", "🐙", "🧸", "🎈", "🏰", "🧚"
)

private val LightColors = lightColorScheme(
    primary = Orange,
    onPrimary = Color.White,
    primaryContainer = Sunny,
    onPrimaryContainer = Ink,
    secondary = Teal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFF3EF),
    onSecondaryContainer = Ink,
    background = Cream,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = CreamDark,
    onSurfaceVariant = InkSoft,
    error = Berry,
    onError = Color.White,
    outline = Color(0xFFD9C7B0)
)

private val DarkColors = darkColorScheme(
    primary = Orange,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF7A4A00),
    onPrimaryContainer = NightText,
    secondary = Teal,
    onSecondary = Night,
    secondaryContainer = Color(0xFF1F4A46),
    onSecondaryContainer = NightText,
    background = Night,
    onBackground = NightText,
    surface = NightSurface,
    onSurface = NightText,
    surfaceVariant = NightSurfaceVariant,
    onSurfaceVariant = NightTextSoft,
    error = Berry,
    onError = Color.White,
    outline = Color(0xFF6B5A4A)
)

private val AppTypography = Typography(
    displayLarge = TextStyle(fontSize = 48.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 54.sp),
    headlineMedium = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

/** @param themeMode "system", "light" or "dark" (see AppSettings.themeMode). */
@Composable
fun BoopBoxTheme(themeMode: String = "system", content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
