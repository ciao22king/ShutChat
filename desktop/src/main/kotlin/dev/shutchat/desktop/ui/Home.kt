package dev.shutchat.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shutchat.app.core.Format
import dev.shutchat.app.domain.model.Chat
import dev.shutchat.app.domain.model.ChatStatus
import dev.shutchat.desktop.DesktopApp

/** Left column: identity, transport status, search and the conversation list. */
@Composable
fun Sidebar(
    app: DesktopApp,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onNewChat: () -> Unit,
    onJoin: () -> Unit,
    onSettings: () -> Unit,
) {
    val chats by app.chats.collectAsState()
    val messages by app.messages.collectAsState()
    val relay by app.relayActive.collectAsState()
    val relayLabel by app.relayLabel.collectAsState()
    val lan by app.lanActive.collectAsState()
    val peers by app.connectedPeers.collectAsState()

    var query by remember { mutableStateOf("") }
    var showArchived by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Chat?>(null) }

    val visible = chats
        .filter { if (showArchived) it.status == ChatStatus.ARCHIVED else it.status != ChatStatus.ARCHIVED }
        .filter { chat ->
            if (query.isBlank()) {
                true
            } else {
                val text = buildString {
                    append(app.titleOf(chat))
                    append(' ')
                    append(chat.lastMessagePreview.orEmpty())
                    messages[chat.id].orEmpty().takeLast(200).forEach { append(' ').append(it.body) }
                }
                text.contains(query, ignoreCase = true)
            }
        }
        .sortedWith(compareByDescending<Chat> { it.pinned }.thenByDescending { it.lastActivityAt })

    Column(
        Modifier
            .width(320.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "ShutChat",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        }
        StatusLine(
            active = relay,
            color = Color(0xFF6FD3A8),
            text = if (relay) "Relay ${relayLabel.ifBlank { "connected" }}" else "Relay off",
        )
        StatusLine(active = lan, color = Color(0xFF7C9CFF), text = if (lan) "Local network on" else "Local network off")
        StatusLine(
            active = peers > 0,
            color = Color(0xFF6FD3A8),
            text = if (peers == 0) "No peer connected" else "$peers peers connected",
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (showArchived) "Archived" else "Chats",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { showArchived = !showArchived }) {
                Text(if (showArchived) "Back" else "Archived")
            }
        }

        LazyColumn(Modifier.weight(1f)) {
            items(visible, key = { it.id }) { chat ->
                ChatRow(
                    app = app,
                    chat = chat,
                    selected = chat.id == selectedId,
                    onClick = { onSelect(chat.id) },
                    onDelete = { pendingDelete = chat },
                )
            }
            if (visible.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No chats yet", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Create a code and pass it to whoever you want to talk to, or join with a code you were given.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.material3.Button(onClick = onNewChat, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("New")
            }
            androidx.compose.material3.OutlinedButton(onClick = onJoin, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Login, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Join")
            }
        }
    }

    pendingDelete?.let { chat ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this chat?") },
            text = {
                Text(
                    "The conversation and its messages are removed from this device only. " +
                        "Anyone who still has the code keeps seeing the room.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    app.deleteChat(chat.id)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ChatRow(
    app: DesktopApp,
    chat: Chat,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val background = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    } else {
        Color.Transparent
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(seed = chat.id, name = app.titleOf(chat), size = 42.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    app.titleOf(chat),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (chat.pinned) {
                    Icon(
                        Icons.Default.PushPin,
                        contentDescription = "Pinned",
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (chat.muted) {
                    Icon(
                        Icons.Default.NotificationsOff,
                        contentDescription = "Muted",
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                previewText(app, chat),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                chat.lastMessageAt?.let { Format.shortTime(it) }.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (chat.unreadCount > 0) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 7.dp, vertical = 1.dp),
                    ) {
                        Text(
                            chat.unreadCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Chat menu", modifier = Modifier.size(16.dp))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (chat.pinned) "Unpin" else "Pin to top") },
                            leadingIcon = {
                                Icon(
                                    if (chat.pinned) Icons.Outlined.PushPin else Icons.Default.PushPin,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                app.setPinned(chat.id, !chat.pinned)
                                menu = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (chat.muted) "Re-enable notifications" else "Mute") },
                            leadingIcon = {
                                Icon(
                                    if (chat.muted) Icons.Outlined.Notifications else Icons.Default.NotificationsOff,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                app.setMuted(chat.id, !chat.muted)
                                menu = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (chat.status == ChatStatus.ARCHIVED) "Unarchive" else "Archive") },
                            leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null) },
                            onClick = {
                                app.setArchived(chat.id, chat.status != ChatStatus.ARCHIVED)
                                menu = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete chat") },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                            onClick = {
                                menu = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun previewText(app: DesktopApp, chat: Chat): String {
    val body = chat.lastMessagePreview ?: return when {
        chat.isGroup -> "Group · share the code to add someone"
        else -> "No messages"
    }
    val sender = chat.lastMessageSender
    return if (sender.isNullOrBlank() || sender == app.displayName.value) body else "$sender: $body"
}
