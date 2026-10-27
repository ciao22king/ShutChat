package dev.shutchat.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * ShutChat's palette.
 *
 * The app's identity is "quiet and closed": a deep ink green rather than the
 * saturated blue every messenger uses, with warm neutrals so long transcripts
 * stay easy to read. Dark is the primary target — this is an app people leave
 * open on a table — so the dark scheme is tuned first and light derives from it.
 */

// Brand
val InkGreen = Color(0xFF1F5C4A)
val InkGreenDark = Color(0xFF0E3B2E)
val Mint = Color(0xFF7FD1B0)
val MintSoft = Color(0xFFB7E4CE)
val Sand = Color(0xFFF6F3EC)

// Message bubbles
val BubbleOutgoing = Color(0xFF1F5C4A)
val BubbleOutgoingDark = Color(0xFF2C7A62)
val BubbleIncomingLight = Color(0xFFFFFFFF)
val BubbleIncomingDark = Color(0xFF1E2622)

// Accents
val Amber = Color(0xFFE0A458)
val Coral = Color(0xFFE07A5F)
val Slate = Color(0xFF5A6B63)

// Neutrals
val Charcoal = Color(0xFF121715)
val CharcoalSoft = Color(0xFF1A211E)
val CharcoalRaised = Color(0xFF232C28)
val OffWhite = Color(0xFFF2F4F1)
val PaperGrey = Color(0xFFE7EAE6)

private val LightColors = lightColorScheme(
    primary = InkGreen,
    onPrimary = Color.White,
    primaryContainer = MintSoft,
    onPrimaryContainer = InkGreenDark,
    secondary = Slate,
    onSecondary = Color.White,
    secondaryContainer = PaperGrey,
    onSecondaryContainer = Charcoal,
    tertiary = Amber,
    onTertiary = Charcoal,
    background = Sand,
    onBackground = Charcoal,
    surface = Color.White,
    onSurface = Charcoal,
    surfaceVariant = PaperGrey,
    onSurfaceVariant = Slate,
    outline = Color(0xFFB8C0BA),
    outlineVariant = Color(0xFFD6DBD7),
    error = Coral,
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Mint,
    onPrimary = InkGreenDark,
    primaryContainer = InkGreen,
    onPrimaryContainer = MintSoft,
    secondary = MintSoft,
    onSecondary = Charcoal,
    secondaryContainer = CharcoalRaised,
    onSecondaryContainer = OffWhite,
    tertiary = Amber,
    onTertiary = Charcoal,
    background = Charcoal,
    onBackground = OffWhite,
    surface = CharcoalSoft,
    onSurface = OffWhite,
    surfaceVariant = CharcoalRaised,
    onSurfaceVariant = MintSoft,
    outline = Color(0xFF3C4742),
    outlineVariant = Color(0xFF2C3632),
    error = Coral,
    onError = Charcoal,
)

internal fun lightScheme() = LightColors
internal fun darkScheme() = DarkColors
