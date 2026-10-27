package dev.shutchat.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/** Theme choice from settings. */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    companion object {
        fun fromKey(key: String): ThemeMode = when (key) {
            "light" -> LIGHT
            "dark" -> DARK
            else -> SYSTEM
        }
    }
}

/**
 * Colors that do not map onto a Material role but are used all over the chat
 * surface, so they are carried alongside the scheme instead of being recomputed
 * per call site.
 */
data class ChatColors(
    val outgoingBubble: androidx.compose.ui.graphics.Color,
    val onOutgoingBubble: androidx.compose.ui.graphics.Color,
    val incomingBubble: androidx.compose.ui.graphics.Color,
    val onIncomingBubble: androidx.compose.ui.graphics.Color,
    val online: androidx.compose.ui.graphics.Color,
    val expiringSoon: androidx.compose.ui.graphics.Color,
    val expired: androidx.compose.ui.graphics.Color,
    val permanent: androidx.compose.ui.graphics.Color,
)

val LocalChatColors = staticCompositionLocalOf {
    ChatColors(
        outgoingBubble = BubbleOutgoing,
        onOutgoingBubble = androidx.compose.ui.graphics.Color.White,
        incomingBubble = BubbleIncomingLight,
        onIncomingBubble = Charcoal,
        online = Mint,
        expiringSoon = Amber,
        expired = Coral,
        permanent = Slate,
    )
}

@Composable
fun ShutChatTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        dark -> darkScheme()
        else -> lightScheme()
    }
    val chatColors = if (dark) {
        ChatColors(
            outgoingBubble = BubbleOutgoingDark,
            onOutgoingBubble = androidx.compose.ui.graphics.Color.White,
            incomingBubble = BubbleIncomingDark,
            onIncomingBubble = OffWhite,
            online = Mint,
            expiringSoon = Amber,
            expired = Coral,
            permanent = MintSoft,
        )
    } else {
        ChatColors(
            outgoingBubble = BubbleOutgoing,
            onOutgoingBubble = androidx.compose.ui.graphics.Color.White,
            incomingBubble = BubbleIncomingLight,
            onIncomingBubble = Charcoal,
            online = InkGreen,
            expiringSoon = Amber,
            expired = Coral,
            permanent = Slate,
        )
    }

    CompositionLocalProvider(LocalChatColors provides chatColors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = ShutChatTypography,
            content = content,
        )
    }
}
