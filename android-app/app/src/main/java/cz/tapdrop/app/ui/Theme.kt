package cz.tapdrop.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Blue = Color(0xFF4058C7)
private val Green = Color(0xFF2EB365)

private val Dark = darkColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF252A33),
    background = Color(0xFF121212),
    surface = Color(0xFF1A1A1A),
    surfaceContainer = Color(0xFF232323),
    surfaceVariant = Color(0xFF303030),
    outline = Color(0xFF8A8A8A),
    onSurface = Color(0xFFF7F7F7),
    onSurfaceVariant = Color(0xFFC7C7C7),
)
private val Light = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7EAF9),
    background = Color(0xFFFAFAFA),
    surface = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF1F1F1),
    surfaceVariant = Color(0xFFE8E8E8),
    outline = Color(0xFF777777),
    onSurface = Color(0xFF171717),
    onSurfaceVariant = Color(0xFF525252),
)

val OkGreen = Green
val WarnRed = Color(0xFFE5534B)

@Composable
fun TapTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) Dark else Light
    MaterialTheme(colorScheme = scheme, content = content)
}
