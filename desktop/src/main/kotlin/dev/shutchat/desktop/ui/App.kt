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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.shutchat.app.core.Format
import dev.shutchat.app.domain.expiry.Expiry
import dev.shutchat.app.domain.model.Chat
import dev.shutchat.app.domain.model.ChatStatus
import dev.shutchat.app.domain.model.InviteKind
import dev.shutchat.app.domain.model.Message
import dev.shutchat.app.domain.model.MessageStatus
import dev.shutchat.app.p2p.call.CallPhase
import dev.shutchat.desktop.DesktopApp

private val Palette = darkColorScheme(
    primary = Color(0xFF7C9CFF),
    onPrimary = Color(0xFF0B1020),
    primaryContainer = Color(0xFF23305C),
    background = Color(0xFF101014),
    surface = Color(0xFF16161C),
    surfaceVariant = Color(0xFF22222B),
    onSurface = Color(0xFFECECF2),
    onSurfaceVariant = Color(0xFFA0A0B0),
)

/** Root of the desktop window: sidebar, conversation, overlays. */
@Composable
fun ShutChatDesktopApp(app: DesktopApp) {
    MaterialTheme(colorScheme = Palette) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            var selected by remember { mutableStateOf<String?>(app.chats.value.firstOrNull()?.id) }
            var showCreate by remember { mutableStateOf(false) }
            var showJoin by remember { mutableStateOf(false) }
            var showSettings by remember { mutableStateOf(false) }

            val chats by app.chats.collectAsState()
            val selectedChat = chats.firstOrNull { it.id == selected }

            Row(Modifier.fillMaxSize()) {
                Sidebar(
                    app = app,
                    chats = chats,
                    selected = selected,
                    onSelect = { selected = it },
                    onCreate = { showCreate = true },
                    onJoin = { showJoin = true },
                    onSettings = { showSettings = true },
                )
                Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0xFF26262F)))
                if (selectedChat == null) {
                    EmptyState(onCreate = { showCreate = true }, onJoin = { showJoin = true })
                } else {
                    ChatPane(
                        app = app,
                        chat = selectedChat,
                        onDelete = {
                            app.deleteChat(selectedChat.id)
                            selected = null
                        },
                    )
                }
            }

            CallOverlay(app)

            if (showCreate) {
                CreateDialog(
                    app = app,
                    onClose = { showCreate = false },
                    onCreated = {
                        selected = it.id
                        showCreate = false
                    },
                )
            }
            if (showJoin) {
                JoinDialog(
                    app = app,
                    onClose = { showJoin = false },
                    onJoined = {
                        selected = it.id
                        showJoin = false
                    },
                )
            }
            if (showSettings) {
                SettingsDialog(app = app, onClose = { showSettings = false })
            }
        }
    }
}

@Composable
private fun Sidebar(
    app: DesktopApp,
    chats: List<Chat>,
    selected: String?,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
    onSettings: () -> Unit,
) {
    val relay by app.relayActive.collectAsState()
    val relayLabel by app.relayLabel.collectAsState()
    val lan by app.lanActive.collectAsState()
    val peers by app.connectedPeers.collectAsState()

    Column(
        Modifier
            .width(300.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
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
        Spacer(Modifier.height(6.dp))
        StatusLine(
            active = relay,
            color = Color(0xFF6FD3A8),
            text = if (relay) "relay ${relayLabel.ifBlank { "connected" }}" else "relay off",
        )
        StatusLine(
            active = lan,
            color = Color(0xFF7C9CFF),
            text = "lan ${if (lan) "listening" else "off"} · $peers peer(s)",
        )

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = Color(0xFF26262F))
        Spacer(Modifier.height(8.dp))

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(chats, key = { it.id }) { chat ->
                ChatRow(chat = chat, selected = chat.id == selected, onClick = { onSelect(chat.id) })
            }
        }

        HorizontalDivider(color = Color(0xFF26262F))
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            TextButton(onClick = onCreate, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("New")
            }
            TextButton(onClick = onJoin, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Join")
            }
        }
    }
}

@Composable
private fun StatusLine(active: Boolean, color: Color, text: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (active) color else Color(0xFF4A4A56)),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChatRow(chat: Chat, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                chat.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (chat.status == ChatStatus.EXPIRED) {
                Text("expired", style = MaterialTheme.typography.labelSmall, color = Color(0xFFE5484D))
            }
        }
        val preview = chat.lastMessagePreview
        if (!preview.isNullOrBlank()) {
            Text(
                preview,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun EmptyState(onCreate: () -> Unit, onJoin: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("No conversation open", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Create a code and pass it on, or paste a code you were given.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Row {
            Button(onClick = onCreate) { Text("New chat") }
            Spacer(Modifier.width(12.dp))
            Button(onClick = onJoin) { Text("Join") }
        }
    }
}

@Composable
private fun ChatPane(app: DesktopApp, chat: Chat, onDelete: () -> Unit) {
    val allMessages by app.messages.collectAsState()
    val list = allMessages[chat.id].orEmpty()
    val listState = rememberLazyListState()
    var composer by remember(chat.id) { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(list.size) {
        if (list.isNotEmpty()) listState.animateScrollToItem(list.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(chat.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (chat.isGroup) "Group" else "Direct conversation",
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
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Copy invite code") },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                        onClick = {
                            chat.inviteCode?.let { clipboard.setText(AnnotatedString(it)) }
                            menuOpen = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete conversation") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }

        if (chat.status == ChatStatus.EXPIRED) {
            Text(
                "This invite code has expired: no peer will accept new messages.",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFE5484D),
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(list, key = { it.id }) { message -> MessageBubble(message) }
        }

        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = composer,
                onValueChange = { composer = it },
                modifier = Modifier
                    .weight(1f)
                    .onPreviewKeyEvent { event ->
                        if (event.key == Key.Enter) {
                            if (composer.isNotBlank()) {
                                app.send(chat.id, composer)
                                composer = ""
                            }
                            true
                        } else {
                            false
                        }
                    },
                placeholder = { Text("Write a message") },
                maxLines = 4,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    app.send(chat.id, composer)
                    composer = ""
                },
                enabled = composer.isNotBlank(),
            ) { Text("Send") }
        }
    }
}

@Composable
private fun MessageBubble(message: Message) {
    val mine = message.outgoing
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (mine) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                )
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            if (!mine) {
                Text(
                    message.senderName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            SelectionContainer { Text(message.body, style = MaterialTheme.typography.bodyMedium) }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    Format.shortTime(message.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (mine) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when (message.status) {
                            MessageStatus.DELIVERED, MessageStatus.READ -> "delivered"
                            MessageStatus.SENDING -> "sent"
                            MessageStatus.QUEUED, MessageStatus.PENDING -> "queued"
                            MessageStatus.FAILED -> "failed"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CreateDialog(app: DesktopApp, onClose: () -> Unit, onCreated: (Chat) -> Unit) {
    var group by remember { mutableStateOf(false) }
    var expiry by remember { mutableStateOf(Expiry.Preset.ONE_WEEK) }
    var created by remember { mutableStateOf<Chat?>(null) }
    val clipboard = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (created == null) "New conversation" else "Code ready") },
        text = {
            if (created == null) {
                Column {
                    Text("Whoever receives this code can join the conversation.")
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = group, onCheckedChange = { group = it })
                        Spacer(Modifier.width(8.dp))
                        Text(if (group) "Group" else "Direct")
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Expiry", style = MaterialTheme.typography.labelMedium)
                    Expiry.Preset.entries.forEach { preset ->
                        Row(
                            Modifier.fillMaxWidth().clickable { expiry = preset }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (expiry == preset) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            } else {
                                Spacer(Modifier.width(16.dp))
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(preset.label)
                        }
                    }
                }
            } else {
                Column {
                    SelectionContainer {
                        Text(
                            created?.inviteCode.orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Turns off: " + Expiry.describeDeadline(created?.inviteExpiryDay, app.clock),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = {
                        created?.inviteCode?.let { clipboard.setText(AnnotatedString(it)) }
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Copy")
                    }
                }
            }
        },
        confirmButton = {
            if (created == null) {
                Button(onClick = {
                    val kind = if (group) InviteKind.GROUP else InviteKind.DIRECT
                    created = app.createChat(kind, Expiry.presetExpiryDay(expiry, app.clock))
                }) { Text("Generate") }
            } else {
                Button(onClick = { created?.let(onCreated) }) { Text("Open") }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

@Composable
private fun JoinDialog(app: DesktopApp, onClose: () -> Unit, onJoined: (Chat) -> Unit) {
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Join a conversation") },
        text = {
            Column {
                OutlinedTextField(
                    value = code,
                    onValueChange = {
                        code = it
                        error = null
                    },
                    label = { Text("Invite code") },
                    placeholder = { Text("XXXX-XXXX-XXXX-XXXX-XXXX-XXXX") },
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Color(0xFFE5484D), style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val chat = app.joinChat(code)
                if (chat == null) error = "Invalid code" else onJoined(chat)
            }) { Text("Enter") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
private fun SettingsDialog(app: DesktopApp, onClose: () -> Unit) {
    val name by app.displayName.collectAsState()
    val relay by app.publicRelay.collectAsState()
    val lan by app.lanEnabled.collectAsState()
    val relayLabel by app.relayLabel.collectAsState()
    var draft by remember { mutableStateOf(name) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Settings") },
        text = {
            Column {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Display name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                SettingSwitch("Public relay (long distance)", relay) { app.setPublicRelay(it) }
                SettingSwitch("Local network discovery", lan) { app.setLanEnabled(it) }
                Spacer(Modifier.height(16.dp))
                Text(
                    "Node: ${app.nodeId.take(12)}…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Relay: " + relayLabel.ifBlank { "not connected" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Messages travel encrypted with the invite key. The broker sees opaque bytes only.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                app.setDisplayName(draft)
                onClose()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Full-window call overlay, mirroring the phone's. */
@Composable
private fun CallOverlay(app: DesktopApp) {
    val call by app.calls.state.collectAsState()
    if (call.phase == CallPhase.IDLE) return

    Box(
        Modifier.fillMaxSize().background(Color(0xF0101014)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                call.peerName.ifBlank { "Call" },
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when (call.phase) {
                    CallPhase.DIALING -> "Calling..."
                    CallPhase.RINGING -> "Incoming call"
                    CallPhase.CONNECTING -> "Connecting..."
                    CallPhase.ACTIVE -> "In call"
                    CallPhase.ENDED -> call.endReason?.label ?: "Ended"
                    CallPhase.IDLE -> ""
                },
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFB8B8C4),
            )
            Spacer(Modifier.height(32.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                when (call.phase) {
                    CallPhase.RINGING -> {
                        Button(onClick = { app.calls.decline() }) { Text("Decline") }
                        Button(onClick = { app.calls.accept() }) { Text("Accept") }
                    }

                    CallPhase.DIALING -> Button(onClick = { app.calls.hangUp() }) { Text("Cancel") }

                    CallPhase.CONNECTING, CallPhase.ACTIVE -> {
                        IconButton(onClick = { app.calls.toggleMute() }) {
                            Icon(
                                if (call.muted) Icons.Default.MicOff else Icons.Default.Mic,
                                contentDescription = "Mute",
                                tint = Color.White,
                            )
                        }
                        IconButton(onClick = { app.calls.hangUp() }) {
                            Icon(
                                Icons.Default.CallEnd,
                                contentDescription = "Hang up",
                                tint = Color(0xFFE5484D),
                            )
                        }
                    }

                    CallPhase.ENDED -> Button(onClick = { app.calls.dismiss() }) { Text("Close") }
                    CallPhase.IDLE -> Unit
                }
            }
        }
    }
}
