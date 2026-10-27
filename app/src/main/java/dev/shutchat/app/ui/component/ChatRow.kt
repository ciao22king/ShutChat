package dev.shutchat.app.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shutchat.app.core.Clock
import dev.shutchat.app.core.Format
import dev.shutchat.app.domain.expiry.Expiry
import dev.shutchat.app.domain.model.Chat
import dev.shutchat.app.domain.model.ChatStatus
import dev.shutchat.app.ui.theme.LocalChatColors

/**
 * One row in the conversation list.
 *
 * The trailing line is the interesting part: instead of a timestamp alone it
 * carries the state of the code, because in ShutChat a conversation can go quiet
 * for a reason no other messenger has — the invite expired and nobody can reach
 * the room any more.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatRow(
    chat: Chat,
    clock: Clock,
    memberCount: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chatColors = LocalChatColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            InitialsAvatar(seed = chat.id, name = chat.title, size = 52.dp)
            if (chat.isGroup) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Groups,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = chat.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (chat.pinned) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.PushPin,
                        contentDescription = "fissata",
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (chat.muted) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.NotificationsOff,
                        contentDescription = "silenziata",
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = chat.lastMessageAt?.let { Format.shortTime(it) } ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.size(3.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = previewOf(chat, memberCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (chat.unreadCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = if (chat.unreadCount > 99) "99+" else chat.unreadCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }

            Spacer(Modifier.size(4.dp))
            CodeStateLine(chat = chat, clock = clock)
        }
    }
}

@Composable
private fun CodeStateLine(chat: Chat, clock: Clock) {
    val chatColors = LocalChatColors.current
    val expired = chat.status == ChatStatus.EXPIRED || Expiry.isExpired(chat.inviteExpiryDay, clock)
    val permanent = chat.inviteExpiryDay == null
    val color = when {
        expired -> chatColors.expired
        permanent -> chatColors.permanent
        else -> chatColors.expiringSoon
    }
    val label = when {
        expired -> "Codice spento · " + Expiry.describeDeadline(chat.inviteExpiryDay, clock)
        permanent -> "Codice infinito"
        else -> "Codice attivo fino al " + Expiry.describeDeadline(chat.inviteExpiryDay, clock)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.Schedule,
            contentDescription = null,
            modifier = Modifier.size(11.dp),
            tint = color,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun previewOf(chat: Chat, memberCount: Int): String {
    val preview = chat.lastMessagePreview
    val sender = chat.lastMessageSender
    return when {
        preview.isNullOrBlank() && chat.isGroup -> "$memberCount membri · in attesa del primo messaggio"
        preview.isNullOrBlank() -> "Nessun messaggio"
        sender.isNullOrBlank() -> preview
        else -> "$sender: $preview"
    }
}

/** Small banner shown at the top of a conversation whose code has expired. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExpiredCodeBanner(
    deadlineLabel: String,
    onRotate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(LocalChatColors.current.expired.copy(alpha = 0.14f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Schedule,
            contentDescription = null,
            tint = LocalChatColors.current.expired,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "Il codice si è spento il $deadlineLabel. Nessuno può più raggiungere questa chat.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "Rigenera",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .combinedClickable(onClick = onRotate, onLongClick = {})
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** Banner offering to add more people to a group that is short on members. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GroupInviteHint(
    memberCount: Int,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Groups,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (memberCount <= 1) {
                "Sei l'unico nel gruppo. Condividi il codice per far entrare qualcuno."
            } else {
                "$memberCount membri nel gruppo."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "Aggiungi",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .combinedClickable(onClick = onAdd, onLongClick = {})
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** Two-column arrangement used by the "+" sheet's option tiles. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OptionTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .combinedClickable(onClick = onClick, onLongClick = {})
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
