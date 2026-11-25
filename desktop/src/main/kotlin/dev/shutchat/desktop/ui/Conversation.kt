package dev.shutchat.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shutchat.app.core.Format
import dev.shutchat.app.domain.model.Chat
import dev.shutchat.app.domain.model.ChatStatus
import dev.shutchat.app.domain.model.Message
import dev.shutchat.app.domain.model.MessageKind
import dev.shutchat.app.domain.model.MessageStatus
import dev.shutchat.desktop.DesktopApp
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/** Right pane: transcript, composer and the actions on a single message. */
@Composable
fun ConversationPane(app: DesktopApp, chat: Chat, onOpenGroup: () -> Unit, onBack: () -> Unit) {
    val messagesByChat by app.messages.collectAsState()
    val members by app.members.collectAsState()
    val typingByChat by app.typing.collectAsState()
    val peers by app.connectedPeers.collectAsState()
    val chats by app.chats.collectAsState()

    val messages = messagesByChat[chat.id].orEmpty()
    val roster = members[chat.id].orEmpty()
    val typingNames = typingByChat[chat.id].orEmpty()
    val liveChat = chats.firstOrNull { it.id == chat.id } ?: chat

    var replyTo by remember(chat.id) { mutableStateOf<Message?>(null) }
    var editing by remember(chat.id) { mutableStateOf<Message?>(null) }
    var draft by remember(chat.id) { mutableStateOf(app.drafts.value[chat.id].orEmpty()) }
    var pendingDelete by remember { mutableStateOf<Message?>(null) }
    var showMenu by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, chat.id) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    LaunchedEffect(chat.id) {
        draft = app.drafts.value[chat.id].orEmpty()
        replyTo = null
        editing = null
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Avatar(seed = chat.id, name = app.titleOf(liveChat), size = 40.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    app.titleOf(liveChat),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle(liveChat, roster.size, peers, roster.count { it.online }),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { app.call(chat.id, video = false) }) {
                Icon(Icons.Default.Call, contentDescription = "Call")
            }
            IconButton(onClick = { app.call(chat.id, video = true) }) {
                Icon(Icons.Default.Videocam, contentDescription = "Video call")
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Conversation menu")
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    if (chat.isGroup) {
                        DropdownMenuItem(
                            text = { Text("Group details") },
                            onClick = {
                                showMenu = false
                                onOpenGroup()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(if (liveChat.muted) "Re-enable notifications" else "Mute") },
                        onClick = {
                            app.setMuted(chat.id, !liveChat.muted)
                            showMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (liveChat.pinned) "Unpin" else "Pin to top") },
                        onClick = {
                            app.setPinned(chat.id, !liveChat.pinned)
                            showMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (liveChat.status == ChatStatus.ARCHIVED) "Unarchive" else "Archive") },
                        onClick = {
                            app.setArchived(chat.id, liveChat.status != ChatStatus.ARCHIVED)
                            showMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Invite code") },
                        onClick = {
                            showMenu = false
                            onOpenGroup()
                        },
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            var lastDay = ""
            messages.forEach { message ->
                val day = Format.daySeparator(message.createdAt, app.clock.nowMillis())
                if (day != lastDay) {
                    lastDay = day
                    item(key = "day-${message.id}") {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                day,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item(key = message.id) {
                    MessageRow(
                        app = app,
                        message = message,
                        onReply = { replyTo = it },
                        onEdit = {
                            editing = it
                            draft = it.body
                        },
                        onDelete = { pendingDelete = it },
                    )
                }
            }
            if (typingNames.isNotEmpty()) {
                item {
                    Text(
                        when {
                            typingNames.size == 1 -> "${typingNames[0]} is typing…"
                            else -> "${typingNames.size} people are typing…"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
            }
        }

        Composer(
            app = app,
            chat = liveChat,
            draft = draft,
            replyTo = replyTo,
            editing = editing,
            onDraftChange = {
                draft = it
                app.setDraft(chat.id, it)
            },
            onCancelReply = { replyTo = null },
            onCancelEdit = {
                editing = null
                draft = ""
                app.setDraft(chat.id, "")
            },
            onSend = { text ->
                if (editing != null) {
                    app.editMessage(editing!!, text)
                    editing = null
                } else {
                    app.send(chat.id, text, replyTo)
                    replyTo = null
                }
                draft = ""
                app.setDraft(chat.id, "")
            },
        )
    }

    pendingDelete?.let { message ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this message?") },
            text = {
                Text(
                    "It will be removed from the others' devices too, if they are reachable. " +
                        "Anyone who was offline may not receive the deletion.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    app.deleteMessage(message)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

private fun subtitle(chat: Chat, memberCount: Int, peers: Int, online: Int): String = when {
    chat.status == ChatStatus.EXPIRED -> "Code expired"
    chat.isGroup -> "$memberCount members · $online online"
    peers > 0 -> "Connected"
    else -> "No peer connected"
}

@Composable
private fun MessageRow(
    app: DesktopApp,
    message: Message,
    onReply: (Message) -> Unit,
    onEdit: (Message) -> Unit,
    onDelete: (Message) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val own = message.outgoing
    val bubbleColor = if (own) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (own) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface

    if (message.kind == MessageKind.SYSTEM) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.Center) {
            Text(
                message.body,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Column(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalAlignment = if (own) Alignment.End else Alignment.Start,
    ) {
        if (!own) {
            Text(
                message.senderName,
                style = MaterialTheme.typography.labelSmall,
                color = avatarColor(message.senderNodeId),
                modifier = Modifier.padding(start = 6.dp, bottom = 2.dp),
            )
        }
        Box {
            Column(
                Modifier
                    .widthIn(max = 560.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(bubbleColor)
                    .clickable { menu = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                message.replyToPreview?.let { preview ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.14f))
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                    ) {
                        Text(
                            preview,
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor.copy(alpha = 0.85f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }

                if (message.deleted) {
                    Text(
                        "Message deleted",
                        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                        color = textColor.copy(alpha = 0.7f),
                    )
                } else {
                    AttachmentBody(app, message, textColor)
                    if (message.kind == MessageKind.TEXT) {
                        SelectionContainer {
                            Text(message.body, style = MaterialTheme.typography.bodyMedium, color = textColor)
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (message.editedAt != null && !message.deleted) {
                        Text(
                            "edited",
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor.copy(alpha = 0.6f),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    if (message.hops > 0) {
                        Text(
                            "via ${message.hops} ${if (message.hops == 1) "relay" else "relays"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor.copy(alpha = 0.6f),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        Format.shortTime(message.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.6f),
                    )
                    if (own) {
                        Spacer(Modifier.width(4.dp))
                        StatusIcon(message.status)
                    }
                }
            }

            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                listOf("👍", "❤️", "😂", "😮", "😢", "🙏").forEach { emoji ->
                    DropdownMenuItem(
                        text = { Text(emoji) },
                        onClick = {
                            app.toggleReaction(message, emoji)
                            menu = false
                        },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Reply") },
                    onClick = {
                        menu = false
                        onReply(message)
                    },
                )
                if (own && !message.deleted && message.kind == MessageKind.TEXT) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = {
                            menu = false
                            onEdit(message)
                        },
                    )
                }
                if (message.kind == MessageKind.TEXT && !message.deleted) {
                    DropdownMenuItem(
                        text = { Text("Copy text") },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                        onClick = {
                            clipboard.setText(AnnotatedString(message.body))
                            menu = false
                        },
                    )
                }
                if (own && !message.deleted) {
                    DropdownMenuItem(
                        text = { Text("Delete for everyone") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = {
                            menu = false
                            onDelete(message)
                        },
                    )
                }
            }
        }

        if (message.reactions.isNotEmpty()) {
            val grouped = message.reactions.groupingBy { it.emoji }.eachCount()
            Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                grouped.forEach { (emoji, count) ->
                    Text(
                        "$emoji $count",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusIcon(status: MessageStatus) {
    val icon = when (status) {
        MessageStatus.READ -> Icons.Default.Done
        MessageStatus.DELIVERED -> Icons.Default.Check
        MessageStatus.SENDING -> Icons.Default.Check
        MessageStatus.QUEUED, MessageStatus.PENDING -> Icons.Default.Check
        MessageStatus.FAILED -> Icons.Default.Check
    }
    val tint = when (status) {
        MessageStatus.READ -> Color(0xFF3BC97A)
        MessageStatus.DELIVERED -> Color(0xFF9FD3B5)
        else -> Color(0xFFB0B0BE)
    }
    Icon(icon, contentDescription = status.name, modifier = Modifier.size(13.dp), tint = tint)
}

/** Image, file or voice note inside a bubble. */
@Composable
private fun AttachmentBody(app: DesktopApp, message: Message, textColor: Color) {
    val attachment = message.attachment ?: return
    when (message.kind) {
        MessageKind.IMAGE -> {
            val path = attachment.localPath
            if (path != null && attachment.progress >= 1f) {
                ImagePreview(path, Modifier.fillMaxWidth().heightIn(max = 260.dp))
            } else {
                Box(
                    Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Photo arriving", style = MaterialTheme.typography.labelMedium, color = textColor)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { attachment.progress },
                            modifier = Modifier.width(160.dp),
                        )
                    }
                }
            }
        }

        MessageKind.AUDIO -> {
            val playing by app.player.playing.collectAsState()
            val progress by app.player.progress.collectAsState()
            val isPlaying = playing == message.id
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    val path = attachment.localPath
                    if (path != null) app.player.toggle(message.id, File(path))
                }) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = textColor,
                    )
                }
                Column(Modifier.width(150.dp)) {
                    LinearProgressIndicator(
                        progress = { if (isPlaying) progress else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        attachment.durationMillis?.let { Format.duration(it) } ?: "Voice message",
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.75f),
                    )
                }
            }
        }

        MessageKind.FILE -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = textColor)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.widthIn(max = 300.dp)) {
                    Text(
                        attachment.fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        Format.fileSize(attachment.sizeBytes) + if (attachment.progress < 1f) {
                            " · ${(attachment.progress * 100).toInt()}%"
                        } else {
                            ""
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.75f),
                    )
                }
                if (attachment.progress >= 1f && attachment.localPath != null) {
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = { openFile(attachment.localPath) }) { Text("Open") }
                }
            }
        }

        else -> Unit
    }
}

private fun openFile(path: String?) {
    if (path == null) return
    runCatching {
        if (java.awt.Desktop.isDesktopSupported()) {
            java.awt.Desktop.getDesktop().open(File(path))
        }
    }
}

@Composable
private fun Composer(
    app: DesktopApp,
    chat: Chat,
    draft: String,
    replyTo: Message?,
    editing: Message?,
    onDraftChange: (String) -> Unit,
    onCancelReply: () -> Unit,
    onCancelEdit: () -> Unit,
    onSend: (String) -> Unit,
) {
    val recording by app.recorder.recording.collectAsState()
    val elapsed by app.recorder.elapsedMillis.collectAsState()
    var attachMenu by remember { mutableStateOf(false) }
    var lastTypingPing by remember { mutableStateOf(0L) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        (editing ?: replyTo)?.let { target ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (editing != null) "Edit message" else "Reply to ${target.senderName}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { if (editing != null) onCancelEdit() else onCancelReply() }) {
                    Text("Cancel")
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        if (recording) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFE5484D)))
                Spacer(Modifier.width(8.dp))
                Text("Recording ${Format.duration(elapsed)}", modifier = Modifier.weight(1f))
                TextButton(onClick = { app.recorder.cancel() }) { Text("Delete") }
                Button(onClick = {
                    val finished = app.recorder.stop()
                    if (finished != null) app.sendVoiceNote(chat.id, finished)
                }) { Text("Send") }
            }
            return@Column
        }

        Row(verticalAlignment = Alignment.Bottom) {
            Box {
                IconButton(onClick = { attachMenu = true }) {
                    Icon(Icons.Default.AttachFile, contentDescription = "Attach")
                }
                DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Photo") },
                        leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                        onClick = {
                            attachMenu = false
                            pickFile(imagesOnly = true)?.let { app.sendAttachment(chat.id, it, MessageKind.IMAGE) }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("File") },
                        leadingIcon = { Icon(Icons.Default.InsertDriveFile, contentDescription = null) },
                        onClick = {
                            attachMenu = false
                            pickFile(imagesOnly = false)?.let { app.sendAttachment(chat.id, it, MessageKind.FILE) }
                        },
                    )
                }
            }
            IconButton(onClick = {
                if (!app.recorder.start()) app.notifier.notify("Microphone", "Microphone not available")
            }) {
                Icon(Icons.Default.Mic, contentDescription = "Hold to record")
            }
            OutlinedTextField(
                value = draft,
                onValueChange = {
                    onDraftChange(it)
                    val now = System.currentTimeMillis()
                    if (it.isNotEmpty() && now - lastTypingPing > 2_500) {
                        lastTypingPing = now
                        app.onTyping(chat.id)
                    }
                },
                placeholder = {
                    Text(if (chat.status == ChatStatus.EXPIRED) "Code expired" else "Write a message")
                },
                enabled = chat.status != ChatStatus.EXPIRED,
                maxLines = 5,
                keyboardOptions = KeyboardOptions.Default,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            IconButton(
                enabled = draft.isNotBlank() && chat.status != ChatStatus.EXPIRED,
                onClick = { onSend(draft) },
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private fun pickFile(imagesOnly: Boolean): File? {
    val chooser = JFileChooser().apply {
        dialogTitle = if (imagesOnly) "Choose a photo" else "Choose a file"
        fileSelectionMode = JFileChooser.FILES_ONLY
        if (imagesOnly) {
            fileFilter = FileNameExtensionFilter("Images", "jpg", "jpeg", "png", "gif", "webp", "bmp")
        }
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}
