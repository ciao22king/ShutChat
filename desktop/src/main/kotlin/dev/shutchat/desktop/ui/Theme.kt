package dev.shutchat.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFF7C9CFF),
    onPrimary = Color(0xFF0B1020),
    primaryContainer = Color(0xFF23305C),
    onPrimaryContainer = Color(0xFFDCE3FF),
    secondary = Color(0xFF5BE9C6),
    onSecondary = Color(0xFF06231C),
    background = Color(0xFF101014),
    onBackground = Color(0xFFECECF2),
    surface = Color(0xFF16161C),
    onSurface = Color(0xFFECECF2),
    surfaceVariant = Color(0xFF22222B),
    onSurfaceVariant = Color(0xFFA0A0B0),
    error = Color(0xFFE5484D),
)

private val Light = lightColorScheme(
    primary = Color(0xFF3E63DD),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE3FF),
    onPrimaryContainer = Color(0xFF0B1020),
    secondary = Color(0xFF0E9E7E),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF7F7FA),
    onBackground = Color(0xFF14141A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF14141A),
    surfaceVariant = Color(0xFFECECF2),
    onSurfaceVariant = Color(0xFF5B5B6B),
    error = Color(0xFFC7262C),
)

/** Applies the theme the user picked in Settings: system, light or dark. */
@Composable
fun ShutChatTheme(theme: String, content: @Composable () -> Unit) {
    val dark = when (theme) {
        "light" -> false
        "dark" -> true
        else -> !java.awt.Desktop.isDesktopSupported() ||
            java.awt.GraphicsEnvironment.isHeadless() ||
            !isSystemDark()
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}

/** Best-effort read of the desktop's colour preference, without extra libraries. */
private fun isSystemDark(): Boolean {
    val gtkTheme = System.getenv("GTK_THEME")?.lowercase()
    if (gtkTheme != null) return !gtkTheme.contains("light")
    val colorScheme = System.getenv("COLOR_SCHEME")?.lowercase()
    if (colorScheme != null) return colorScheme.contains("dark")
    return true
}

/** Deterministic colour for an avatar, from the node id or chat id. */
fun avatarColor(seed: String): Color {
    val palette = listOf(
        Color(0xFF7C9CFF), Color(0xFF5BE9C6), Color(0xFFFFB86C), Color(0xFFFF8FA3),
        Color(0xFFB98CFF), Color(0xFF6FD3A8), Color(0xFFF2C94C), Color(0xFF6BCBEF),
    )
    return palette[(seed.hashCode() and Int.MAX_VALUE) % palette.size]
}
