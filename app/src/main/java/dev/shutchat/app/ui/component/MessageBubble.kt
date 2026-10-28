package dev.shutchat.app.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shutchat.app.core.Format
import dev.shutchat.app.domain.model.Message
import dev.shutchat.app.domain.model.MessageKind
import dev.shutchat.app.domain.model.MessageStatus
import dev.shutchat.app.ui.theme.LocalChatColors

/**
 * One message.
 *
 * Outgoing bubbles are tinted and right aligned; incoming are surface coloured
 * and left aligned. The status glyph is deliberately subtle — with no server
 * there is no "sent to cloud" tick, so the ticks mean something narrower than in
 * WhatsApp: queued, delivered to a peer, read by a peer.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    showSender: Boolean,
    isFirstInGroup: Boolean,
    isLastInGroup: Boolean,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    audioPlaying: Boolean = false,
    audioPositionMillis: Long = 0L,
    audioDurationMillis: Long = 0L,
    onAudioToggle: (() -> Unit)? = null,
) {
    val chatColors = LocalChatColors.current
    val outgoing = message.isOutgoing
    val bubbleColor = if (outgoing) chatColors.outgoingBubble else chatColors.incomingBubble
    val textColor = if (outgoing) chatColors.onOutgoingBubble else chatColors.onIncomingBubble

    val shape = RoundedCornerShape(
        topStart = if (outgoing) 18.dp else if (isFirstInGroup) 18.dp else 6.dp,
        topEnd = if (outgoing) if (isFirstInGroup) 18.dp else 6.dp else 18.dp,
        bottomStart = if (outgoing) 18.dp else if (isLastInGroup) 18.dp else 6.dp,
        bottomEnd = if (outgoing) if (isLastInGroup) 18.dp else 6.dp else 18.dp,
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start,
    ) {
        if (showSender && !outgoing) {
            Text(
                text = message.senderName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 14.dp, bottom = 2.dp),
            )
        }

        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(shape)
                .background(bubbleColor)
                .combinedClickable(onClick = {}, onLongClick = onLongPress)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Column {
                message.replyToPreview?.let { preview ->
                    ReplyQuote(preview = preview, outgoing = outgoing)
                }

                when (message.kind) {
                    MessageKind.IMAGE -> MediaBody(message = message, textColor = textColor)
                    MessageKind.FILE -> FileBody(message = message, textColor = textColor)
                    MessageKind.AUDIO -> AudioBody(
                        message = message,
                        textColor = textColor,
                        playing = audioPlaying,
                        positionMillis = audioPositionMillis,
                        durationMillis = audioDurationMillis,
                        onToggle = onAudioToggle,
                    )
                    MessageKind.SYSTEM -> Text(
                        text = message.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = textColor.copy(alpha = 0.85f),
                        fontStyle = FontStyle.Italic,
                    )

                    MessageKind.TEXT -> Text(
                        text = if (message.deleted) "Messaggio eliminato" else message.body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (message.deleted) textColor.copy(alpha = 0.6f) else textColor,
                        fontStyle = if (message.deleted) FontStyle.Italic else FontStyle.Normal,
                    )
                }

                if (message.reactions.isNotEmpty()) {
                    Spacer(Modifier.size(4.dp))
                    ReactionStrip(reactions = message.reactions.map { it.emoji })
                }

                Spacer(Modifier.size(3.dp))
                Footer(message = message, outgoing = outgoing, textColor = textColor)
            }
        }
    }
}

@Composable
private fun ReplyQuote(preview: String, outgoing: Boolean) {
    val chatColors = LocalChatColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = if (outgoing) 0.16f else 0.05f))
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .padding(end = 6.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(chatColors.expiringSoon),
        )
        Text(
            text = preview,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = if (outgoing) chatColors.onOutgoingBubble.copy(alpha = 0.9f) else chatColors.onIncomingBubble.copy(alpha = 0.8f),
        )
    }
}

@Composable
private fun MediaBody(message: Message, textColor: Color) {
    val attachment = message.attachment
    Column {
        Box(
            modifier = Modifier
                .size(width = 220.dp, height = 150.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            val path = attachment?.localPath
            if (path != null) {
                coil.compose.AsyncImage(
                    model = java.io.File(path),
                    contentDescription = attachment.fileName,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                TransferProgress(
                    progress = attachment?.progress ?: 0f,
                    label = "Foto in arrivo",
                    textColor = textColor,
                )
            }
        }
        if (!message.body.isNullOrBlank()) {
            Spacer(Modifier.size(6.dp))
            Text(
                text = message.body,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
            )
        }
    }
}

@Composable
private fun FileBody(message: Message, textColor: Color) {
    val attachment = message.attachment
    Column {
        Text(
            text = attachment?.fileName ?: "File",
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (attachment != null) {
            Text(
                text = Format.fileSize(attachment.sizeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = textColor.copy(alpha = 0.7f),
            )
            if (attachment.localPath == null) {
                Spacer(Modifier.size(6.dp))
                TransferProgress(
                    progress = attachment.progress,
                    label = "In arrivo",
                    textColor = textColor,
                )
            }
        }
    }
}

/**
 * A voice note: play button, progress, duration.
 *
 * The bar is drawn from the same [LinearProgressIndicator] the transfer uses, so
 * a note still arriving and a note being played read as the same kind of waiting
 * rather than two unrelated widgets.
 */
@Composable
private fun AudioBody(
    message: Message,
    textColor: Color,
    playing: Boolean,
    positionMillis: Long,
    durationMillis: Long,
    onToggle: (() -> Unit)?,
) {
    val attachment = message.attachment
    val ready = attachment?.localPath != null

    // The stored duration is what the sender measured while recording, so it is
    // available before the file arrives and can label a note still in flight.
    val knownDuration = when {
        durationMillis > 0L -> durationMillis
        attachment?.durationMillis != null -> attachment.durationMillis
        else -> 0L
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.widthIn(min = 180.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(textColor.copy(alpha = if (ready) 0.18f else 0.08f))
                .clickable(enabled = ready && onToggle != null) { onToggle?.invoke() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (playing) "Metti in pausa" else "Riproduci",
                tint = textColor.copy(alpha = if (ready) 1f else 0.45f),
                modifier = Modifier.size(21.dp),
            )
        }

        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f, fill = false)) {
            if (!ready) {
                TransferProgress(
                    progress = attachment?.progress ?: 0f,
                    label = "Vocale in arrivo",
                    textColor = textColor,
                )
            } else {
                LinearProgressIndicator(
                    progress = {
                        if (knownDuration <= 0L) 0f
                        else (positionMillis.toFloat() / knownDuration).coerceIn(0f, 1f)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = textColor,
                    trackColor = textColor.copy(alpha = 0.25f),
                )
                Spacer(Modifier.size(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = Format.duration(if (playing) positionMillis else knownDuration),
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.85f),
                    )
                    if (knownDuration > 0L) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "· ${Format.duration(knownDuration)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor.copy(alpha = 0.55f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferProgress(progress: Float, label: String, textColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            color = textColor,
            trackColor = textColor.copy(alpha = 0.2f),
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = if (progress > 0f) "$label ${(progress * 100).toInt()}%" else label,
            style = MaterialTheme.typography.labelSmall,
            color = textColor.copy(alpha = 0.85f),
        )
    }
}

/** Overlapping emoji chips, deduplicated with a count. */
@Composable
private fun ReactionStrip(reactions: List<String>) {
    val grouped = reactions.groupingBy { it }.eachCount().entries.take(4)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        grouped.forEach { (emoji, count) ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = emoji, style = MaterialTheme.typography.bodySmall)
                if (count > 1) {
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = count.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun Footer(message: Message, outgoing: Boolean, textColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (message.hops > 0) {
            Text(
                text = "via ${message.hops} ${if (message.hops == 1) "salto" else "salti"}",
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.55f),
            )
            Spacer(Modifier.width(6.dp))
        }
        if (message.editedAt != null && !message.deleted) {
            Text(
                text = "modificato",
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.55f),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = Format.shortTime(message.createdAt),
            style = MaterialTheme.typography.labelSmall,
            color = textColor.copy(alpha = 0.6f),
        )
        if (outgoing) {
            Spacer(Modifier.width(4.dp))
            StatusGlyph(status = message.status, tint = textColor)
        }
    }
}

@Composable
private fun StatusGlyph(status: MessageStatus, tint: Color) {
    val alpha = if (status == MessageStatus.READ) 1f else 0.6f
    when (status) {
        MessageStatus.PENDING, MessageStatus.SENDING -> Icon(
            imageVector = Icons.Default.Schedule,
            contentDescription = "in invio",
            modifier = Modifier.size(13.dp),
            tint = tint.copy(alpha = alpha),
        )

        MessageStatus.QUEUED -> Icon(
            imageVector = Icons.Default.Schedule,
            contentDescription = "in attesa di un peer",
            modifier = Modifier.size(13.dp),
            tint = LocalChatColors.current.expiringSoon,
        )

        MessageStatus.DELIVERED -> Icon(
            imageVector = Icons.Default.Done,
            contentDescription = "consegnato",
            modifier = Modifier.size(13.dp),
            tint = tint.copy(alpha = alpha),
        )

        MessageStatus.READ -> Icon(
            imageVector = Icons.Default.DoneAll,
            contentDescription = "letto",
            modifier = Modifier.size(14.dp),
            tint = LocalChatColors.current.online,
        )

        MessageStatus.FAILED -> Icon(
            imageVector = Icons.Default.ErrorOutline,
            contentDescription = "non inviato",
            modifier = Modifier.size(13.dp),
            tint = LocalChatColors.current.expired,
        )
    }
}

/** Animated typing indicator shown under the conversation header. */
@Composable
fun TypingIndicator(names: List<String>, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = names.isNotEmpty(),
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Text(
            text = when (names.size) {
                1 -> "${names[0]} sta scrivendo…"
                else -> "${names.size} persone stanno scrivendo…"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
