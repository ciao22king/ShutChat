package dev.shutchat.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.shutchat.app.p2p.call.CallPhase
import dev.shutchat.app.p2p.call.CallSnapshot
import dev.shutchat.app.p2p.protocol.CallMedia
import dev.shutchat.desktop.DesktopApp

/** Root of the desktop window: sidebar, content pane, overlays. */
@Composable
fun ShutChatDesktopApp(app: DesktopApp) {
    val theme by app.theme.collectAsState()

    ShutChatTheme(theme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val chats by app.chats.collectAsState()
            var selected by remember { mutableStateOf<String?>(null) }
            var showSettings by remember { mutableStateOf(false) }
            var showCreate by remember { mutableStateOf(false) }
            var showJoin by remember { mutableStateOf(false) }
            var showGroup by remember { mutableStateOf(false) }

            val selectedChat = chats.firstOrNull { it.id == selected }

            // Opening a chat is what clears the badge and sends read receipts.
            LaunchedEffect(selected, showSettings) {
                app.openChatId = if (showSettings) null else selected
            }

            Row(Modifier.fillMaxSize()) {
                Sidebar(
                    app = app,
                    selectedId = selected,
                    onSelect = {
                        selected = it
                        showSettings = false
                        showGroup = false
                    },
                    onNewChat = { showCreate = true },
                    onJoin = { showJoin = true },
                    onSettings = { showSettings = true },
                )
                Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant))
                when {
                    showSettings -> SettingsPane(app, onBack = { showSettings = false })

                    selectedChat == null -> EmptyPane(
                        title = "Start something",
                        body = "ShutChat conversations live on the devices. A code is the only way to let someone in.",
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { showCreate = true }) { Text("Create a code") }
                            OutlinedButton(onClick = { showJoin = true }) { Text("Join with a code") }
                        }
                    }

                    showGroup && selectedChat.isGroup -> GroupPane(
                        app = app,
                        chat = selectedChat,
                        onBack = { showGroup = false },
                        onDeleted = {
                            showGroup = false
                            selected = null
                        },
                    )

                    else -> ConversationPane(
                        app = app,
                        chat = selectedChat,
                        onOpenGroup = { showGroup = true },
                        onBack = { selected = null },
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
                        showSettings = false
                        showGroup = false
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
                        showSettings = false
                        showGroup = false
                        showJoin = false
                    },
                )
            }
        }
    }
}

/** Full-window call overlay, mirroring the phone's. */
@Composable
private fun CallOverlay(app: DesktopApp) {
    val call by app.calls.state.collectAsState()
    if (call.phase == CallPhase.IDLE) return

    Box(Modifier.fillMaxSize().background(Color(0xF0101014)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(seed = call.peerNodeId ?: "call", name = call.peerName.ifBlank { "Call" }, size = 96.dp)
            Spacer(Modifier.height(16.dp))
            Text(
                call.peerName.ifBlank { "Call" },
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (call.media == CallMedia.VIDEO) {
                    Icon(Icons.Default.Videocam, contentDescription = null, tint = Color(0xFFB8B8C4))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    when (call.phase) {
                        CallPhase.DIALING -> "Calling..."
                        CallPhase.RINGING ->
                            if (call.media == CallMedia.VIDEO) "Incoming video call" else "Incoming call"

                        CallPhase.CONNECTING -> "Connecting..."
                        CallPhase.ACTIVE -> "In call " + clock(call, app)
                        CallPhase.ENDED -> call.endReason?.label ?: "Call ended"
                        CallPhase.IDLE -> ""
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFFB8B8C4),
                )
            }
            if (call.isBusy && !app.callAudioAvailable) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "No sound device found: the call connects without audio.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFE0A030),
                )
            }
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                when (call.phase) {
                    CallPhase.RINGING -> {
                        Button(onClick = { app.calls.decline() }) { Text("Decline") }
                        Button(onClick = { app.calls.accept() }) { Text("Accept") }
                    }

                    CallPhase.DIALING -> Button(onClick = { app.calls.hangUp() }) { Text("Cancel") }

                    CallPhase.CONNECTING, CallPhase.ACTIVE -> {
                        RoundAction(
                            icon = if (call.muted) Icons.Default.MicOff else Icons.Default.Mic,
                            label = if (call.muted) "Unmute" else "Mute",
                        ) { app.calls.toggleMute() }
                        RoundAction(Icons.Default.CallEnd, "Hang up") { app.calls.hangUp() }
                    }

                    CallPhase.ENDED -> Button(onClick = { app.calls.dismiss() }) { Text("Close") }
                    CallPhase.IDLE -> Unit
                }
            }
        }
    }
}

/** " · 01:23" once the call is up, empty before that. */
private fun clock(call: CallSnapshot, app: DesktopApp): String {
    val elapsed = call.elapsedMillis(app.clock.nowMillis())
    if (elapsed <= 0) return ""
    val total = elapsed / 1000
    return "· %d:%02d".format(total / 60, total % 60)
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(50)),
        ) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFB8B8C4),
            fontWeight = FontWeight.Medium,
        )
    }
}
