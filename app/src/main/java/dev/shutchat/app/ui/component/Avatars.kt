package dev.shutchat.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.shutchat.app.core.Format

/**
 * Deterministic avatar.
 *
 * Two letters on a tint derived from the node id. No network, no upload, and the
 * same peer always gets the same colour on every device, which is what makes it
 * feel like an identity rather than decoration.
 */
@Composable
fun InitialsAvatar(
    seed: String,
    name: String,
    size: Dp = 48.dp,
    modifier: Modifier = Modifier,
) {
    val palette = AVATAR_TINTS
    val tint = palette[Format.colorSeed(seed) % palette.size]
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(tint),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = Format.initials(name),
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            style = if (size >= 56.dp) {
                MaterialTheme.typography.titleLarge
            } else {
                MaterialTheme.typography.titleMedium
            },
        )
    }
}

/** Avatar with a presence dot, used in conversation headers and member lists. */
@Composable
fun PresenceAvatar(
    seed: String,
    name: String,
    online: Boolean,
    size: Dp = 48.dp,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        InitialsAvatar(seed = seed, name = name, size = size)
        if (online) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(size / 4)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.background)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(dev.shutchat.app.ui.theme.LocalChatColors.current.online),
            )
        }
    }
}

/**
 * Placeholder shown when a list is empty.
 *
 * The copy explains *why* it is empty in this app's terms: there is no server to
 * fetch anything from, so an empty list is the expected first state.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp),
        )
        action?.let {
            Box(modifier = Modifier.padding(top = 24.dp)) { it() }
        }
    }
}

/** Section header used above grouped rows in settings. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 20.dp, bottom = 8.dp),
    )
}

/** Small pill used for statuses: online, scaduto, infinito, in attesa. */
@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

private val AVATAR_TINTS = listOf(
    Color(0xFF1F5C4A),
    Color(0xFF2E6E8E),
    Color(0xFF7A4E9B),
    Color(0xFFA85751),
    Color(0xFFB07C2E),
    Color(0xFF3F7A5A),
    Color(0xFF4A5A8C),
    Color(0xFF8A5B3D),
)
