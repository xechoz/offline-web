package io.github.xechoz.offlineweb.demo

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF146C5A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6F4EF),
    onPrimaryContainer = Color(0xFF0B3B31),
    secondary = Color(0xFF4F635C),
    surface = Color(0xFFFBFCF9),
    background = Color(0xFFF7F8F5),
    onSurface = Color(0xFF17202A),
    onSurfaceVariant = Color(0xFF5C6B64),
)

@Composable
fun OfflineWebDemoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content,
    )
}
