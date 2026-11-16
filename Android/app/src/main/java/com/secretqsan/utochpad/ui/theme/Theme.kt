package com.secretqsan.utochpad.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Material colours follow the soft (neumorphic) sheet: one base tone, a muted
// slate-blue accent and no hard outlines. The visible surfaces themselves are
// drawn by the soft palette in ui/SoftStyle.kt.
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF82A9DB),
    onPrimary = Color(0xFF101B28),
    primaryContainer = Color(0xFF2B3846),
    onPrimaryContainer = Color(0xFFD6E6F7),
    secondary = Color(0xFF9FB4C9),
    onSecondary = Color(0xFF101B28),
    secondaryContainer = Color(0xFF2A343F),
    onSecondaryContainer = Color(0xFFDCE6EF),
    tertiary = Color(0xFFC3B59E),
    onTertiary = Color(0xFF241F16),
    background = Color(0xFF23272E),
    onBackground = Color(0xFFDEE4EC),
    surface = Color(0xFF2B313A),
    onSurface = Color(0xFFDEE4EC),
    surfaceVariant = Color(0xFF313842),
    onSurfaceVariant = Color(0xFFAAB4C0),
    outline = Color(0xFF4A5361),
    outlineVariant = Color(0xFF39414C),
    error = Color(0xFFF3908A),
    onError = Color(0xFF2B0B09),
    errorContainer = Color(0xFF45211F),
    onErrorContainer = Color(0xFFFFDAD6),
)

// Light soft sheet.
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF4A6F9E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3F2),
    onPrimaryContainer = Color(0xFF1A2C42),
    secondary = Color(0xFF64788C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE6EF),
    onSecondaryContainer = Color(0xFF1D2A35),
    tertiary = Color(0xFF75664F),
    onTertiary = Color.White,
    background = Color(0xFFE6EBF2),
    onBackground = Color(0xFF37424F),
    surface = Color(0xFFF3F6FA),
    onSurface = Color(0xFF37424F),
    surfaceVariant = Color(0xFFDCE2EA),
    onSurfaceVariant = Color(0xFF586474),
    outline = Color(0xFFA9B4C2),
    outlineVariant = Color(0xFFC9D1DB),
    error = Color(0xFFB0443C),
    onError = Color.White,
    errorContainer = Color(0xFFF6DAD7),
    onErrorContainer = Color(0xFF410E0B),
)

@Composable
fun UtochpadTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    // Dynamic colour would tint the carefully neutral soft sheet, so the
    // Material palette always comes from the two fixed schemes.
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
