package dev.shutchat.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shutchat.app.domain.expiry.Expiry
import dev.shutchat.app.domain.model.Chat
import dev.shutchat.app.domain.model.InviteKind
import dev.shutchat.desktop.DesktopApp

/** Creates a code, with the kind and the deadline written inside it. */
@Composable
fun CreateDialog(app: DesktopApp, onClose: () -> Unit, onCreated: (Chat) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var group by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var preset by remember { mutableStateOf(Expiry.Preset.ONE_WEEK) }
    var created by remember { mutableStateOf<Chat?>(null) }
    var copied by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (created == null) "New chat" else "Your code") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                if (created == null) {
                    Text(
                        "The kind is written inside the code, so whoever uses it joins the right kind of conversation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = !group, onClick = { group = false })
                        Column(Modifier.padding(end = 16.dp)) {
                            Text("Direct chat", fontWeight = FontWeight.Medium)
                            Text(
                                "Two devices, one room. The code is the key.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = group, onClick = { group = true })
                        Column {
                            Text("Group", fontWeight = FontWeight.Medium)
                            Text(
                                "Several people with the same code. You can add more at any time.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (group) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text("Group name (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("How long should the code last?", fontWeight = FontWeight.Medium)
                    Text(
                        "The date is inside the code, so it means the same to everyone. " +
                            "When the code turns off, nobody can reach the room any more.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Expiry.Preset.entries.forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = preset == option, onClick = { preset = option })
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = preset == option, onClick = { preset = option })
                            Text(option.label)
                        }
                    }
                } else {
                    val chat = created!!
                    QrCode(chat.inviteCode.orEmpty(), size = 240.dp)
                    Spacer(Modifier.height(12.dp))
                    CodeText(chat.inviteCode.orEmpty())
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (chat.inviteExpiryDay == null) {
                            "Endless code: it stays valid until someone regenerates it."
                        } else {
                            "Turns off on " + Expiry.describeDeadline(chat.inviteExpiryDay, app.clock)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Pass this code to whoever should join. It is all that is needed: there are no accounts and no server storing it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(chat.inviteCode.orEmpty()))
                        copied = true
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (copied) "Code copied" else "Copy")
                    }
                }
            }
        },
        confirmButton = {
            if (created == null) {
                Button(onClick = {
                    val kind = if (group) InviteKind.GROUP else InviteKind.DIRECT
                    created = app.createChat(kind, Expiry.presetExpiryDay(preset, app.clock), title)
                }) { Text("Generate the code") }
            } else {
                Button(onClick = { created?.let(onCreated) }) { Text("Open the chat") }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

/** Joins with a code typed, pasted or scanned elsewhere. */
@Composable
fun JoinDialog(app: DesktopApp, onClose: () -> Unit, onJoined: (Chat) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Join with a code") },
        text = {
            Column {
                Text(
                    "Paste the code you were given. It is 24 characters in groups of four: " +
                        "you can type it with spaces or in lower case.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = {
                        code = it
                        error = null
                    },
                    label = { Text("Code") },
                    placeholder = { Text("XXXX-XXXX-XXXX-XXXX-XXXX-XXXX") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = {
                    clipboard.getText()?.text?.let { code = it.trim() }
                }) { Text("Paste from clipboard") }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val chat = app.joinChat(code)
                if (chat == null) error = "Invalid code" else onJoined(chat)
            }) { Text("Join the room") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

/** Group details: name, members, invite code and the way out. */
@Composable
fun GroupPane(app: DesktopApp, chat: Chat, onBack: () -> Unit, onDeleted: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val members by app.members.collectAsState()
    val chats by app.chats.collectAsState()
    val roster = members[chat.id].orEmpty()
    val live = chats.firstOrNull { it.id == chat.id } ?: chat

    var renaming by remember { mutableStateOf(false) }
    var nameDraft by remember(chat.id) { mutableStateOf(live.title) }
    var pendingRemoval by remember { mutableStateOf<String?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    var preset by remember { mutableStateOf(Expiry.Preset.ONE_WEEK) }
    var showRotate by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
            Text("Group details", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { renaming = true }) { Text("Rename") }
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(seed = chat.id, name = live.title, size = 56.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(live.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${roster.size} members · ${roster.count { it.online }} online now",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionLabel("Group code")
        if (live.inviteCode == null) {
            Card {
                Text(
                    "No active code. Generate one to let someone in.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        live.inviteCode?.let { code ->
            Card {
                QrCode(code, size = 200.dp)
                Spacer(Modifier.height(10.dp))
                CodeText(code)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (live.inviteExpiryDay == null) {
                        "Endless code: it stays valid until someone regenerates it."
                    } else {
                        "Turns off on " + Expiry.describeDeadline(live.inviteExpiryDay, app.clock)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Copy")
                    }
                    TextButton(onClick = { showRotate = true }) { Text("Generate a new code") }
                }
            }
        }

        SectionLabel("Members")
        Text(
            "These are the devices that used the code. There is no central list: if someone leaves, " +
                "they disappear here the next time they connect.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        roster.forEach { member ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    Avatar(seed = member.avatarSeed, name = member.displayName, size = 38.dp)
                    Box(Modifier.align(Alignment.BottomEnd)) { OnlineDot(member.online, 11.dp) }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        member.displayName + if (member.isSelf) " (you)" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (member.online) "Online" else "Offline",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!member.isSelf) {
                    IconButton(onClick = { pendingRemoval = member.nodeId }) {
                        Icon(Icons.Default.PersonRemove, contentDescription = "Remove")
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        OutlinedButton(
            onClick = { confirmLeave = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.ExitToApp, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Leave the group")
        }
    }

    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Group name") },
            text = {
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    app.renameChat(chat.id, nameDraft)
                    renaming = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }

    pendingRemoval?.let { node ->
        val name = roster.firstOrNull { it.nodeId == node }?.displayName.orEmpty()
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("Remove $name?") },
            text = {
                Text(
                    "They will disappear from this list. If they still have the code they can come back, " +
                        "because access depends on the code, not on a permission.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    app.removeMember(chat.id, node)
                    pendingRemoval = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("Cancel") } },
        )
    }

    if (showRotate) {
        AlertDialog(
            onDismissRequest = { showRotate = false },
            title = { Text("Generate a new code") },
            text = {
                Column {
                    Text(
                        "The old code stops working and the messages stay on this device only.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Expiry.Preset.entries.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(selected = preset == option, onClick = { preset = option }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = preset == option, onClick = { preset = option })
                            Text(option.label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    app.rotateCode(chat.id, preset)
                    showRotate = false
                }) { Text("Generate") }
            },
            dismissButton = { TextButton(onClick = { showRotate = false }) { Text("Cancel") } },
        )
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave the group?") },
            text = { Text("The conversation is removed from this device. The others keep their copy.") },
            confirmButton = {
                TextButton(onClick = {
                    app.leaveGroup(chat.id)
                    confirmLeave = false
                    onDeleted()
                }) { Text("Leave") }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp),
    ) {
        content()
    }
}
