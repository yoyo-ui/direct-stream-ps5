package com.directstream.ps5.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.directstream.ps5.data.ThemeMode

val Charcoal = Color(0xFF0F1115)
val Surface1 = Color(0xFF171A21)
val Surface2 = Color(0xFF1F232C)
val Violet = Color(0xFF8B7CFF)
val Blue = Color(0xFF5AA9FF)
val Good = Color(0xFF4ADE80)
val Warn = Color(0xFFFBBF24)
val Bad = Color(0xFFFF6B6B)

private val Dark = darkColorScheme(
    primary = Violet, onPrimary = Color(0xFF14102E), secondary = Blue, onSecondary = Color(0xFF05182B),
    background = Charcoal, onBackground = Color(0xFFE8EAF0), surface = Surface1, onSurface = Color(0xFFE8EAF0),
    surfaceVariant = Surface2, onSurfaceVariant = Color(0xFFA7ADBB), error = Bad, outline = Color(0xFF3A404D)
)
private val Light = lightColorScheme(
    primary = Color(0xFF5B4BE0), secondary = Color(0xFF1F6FD1), background = Color(0xFFF6F7FB), surface = Color.White,
    surfaceVariant = Color(0xFFE9EBF3), error = Color(0xFFC62828)
)

@Composable
fun DirectStreamTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> isSystemInDarkTheme() }
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
