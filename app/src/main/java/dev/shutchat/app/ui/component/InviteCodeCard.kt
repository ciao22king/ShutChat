package dev.shutchat.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.shutchat.app.domain.expiry.Expiry
import dev.shutchat.app.ui.theme.CodeStyle
import dev.shutchat.app.ui.theme.LocalChatColors

/**
 * The invite code card.
 *
 * This is the centre of the app: it is what the "+" button produces, and the
 * only thing a user has to move to another person. Three facts have to be
 * legible at a glance, so they get three separate lines:
 *
 *  1. the code itself, grouped and monospaced so it can be read aloud,
 *  2. the day it stops working, in the user's own timezone,
 *  3. whether it is infinite.
 *
 * The expiry line is tinted by urgency rather than being a plain label, because
 * "expires tomorrow" is the one thing a user must not miss.
 */
@Composable
fun InviteCodeCard(
    code: String,
    expiryDay: Int?,
    clock: dev.shutchat.app.core.Clock,
    modifier: Modifier = Modifier,
    title: String? = null,
    onCopy: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
) {
    val chatColors = LocalChatColors.current
    val expired = Expiry.isExpired(expiryDay, clock)
    val permanent = expiryDay == null
    val urgencyColor = when {
        expired -> chatColors.expired
        permanent -> chatColors.permanent
        else -> chatColors.expiringSoon
    }
    val deadlineLabel = Expiry.describeDeadline(expiryDay, clock)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(18.dp),
            )
            .padding(18.dp),
    ) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
        }

        Text(
            text = code,
            style = CodeStyle,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.size(12.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = if (permanent) Icons.Default.AllInclusive else Icons.Default.Schedule,
                contentDescription = null,
                tint = urgencyColor,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = when {
                    permanent -> "Nessuna scadenza: il codice non si spegne mai"
                    expired -> "Codice spento il $deadlineLabel"
                    else -> "Si spegne il $deadlineLabel"
                },
                style = MaterialTheme.typography.bodySmall,
                color = urgencyColor,
            )
        }

        if (onCopy != null || onShare != null) {
            Spacer(Modifier.size(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                onCopy?.let { action ->
                    CardAction(
                        icon = Icons.Default.ContentCopy,
                        label = "Copia",
                        onClick = action,
                        modifier = Modifier.weight(1f),
                    )
                }
                onShare?.let { action ->
                    CardAction(
                        icon = Icons.Default.Share,
                        label = "Condividi",
                        onClick = action,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun CardAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
