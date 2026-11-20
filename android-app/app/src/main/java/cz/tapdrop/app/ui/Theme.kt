package cz.tapdrop.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Blue = Color(0xFF4C8BF5)

private val Dark = darkColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1E3A6B),
    background = Color(0xFF14171E),
    surface = Color(0xFF14171E),
    surfaceContainer = Color(0xFF1E222C),
    surfaceVariant = Color(0xFF262B37),
)
private val Light = lightColorScheme(primary = Color(0xFF2F6FE0))

val OkGreen = Color(0xFF3DBE78)
val WarnRed = Color(0xFFE5534B)

@Composable
fun TapTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
