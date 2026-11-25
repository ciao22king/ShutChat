package dev.shutchat.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.shutchat.app.update.UpdateResult
import dev.shutchat.desktop.DesktopApp
import kotlinx.coroutines.launch

/** Full settings page, the same sections the phone shows. */
@Composable
fun SettingsPane(app: DesktopApp, onBack: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    val name by app.displayName.collectAsState()
    val theme by app.theme.collectAsState()
    val readReceipts by app.readReceipts.collectAsState()
    val typingIndicator by app.typingIndicator.collectAsState()
    val notifications by app.notifications.collectAsState()
    val relay by app.publicRelay.collectAsState()
    val lan by app.lanEnabled.collectAsState()
    val forwarding by app.forwarding.collectAsState()
    val retention by app.retentionDays.collectAsState()
    val relayLabel by app.relayLabel.collectAsState()
    val relayActive by app.relayActive.collectAsState()
    val lanActive by app.lanActive.collectAsState()
    val peers by app.connectedPeers.collectAsState()
    val chats by app.chats.collectAsState()
    val messages by app.messages.collectAsState()

    var editingName by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf(name) }
    var confirmClear by remember { mutableStateOf(false) }
    var updateState by remember { mutableStateOf<UpdateResult?>(null) }
    var checking by remember { mutableStateOf(false) }

    val messageCount = messages.values.sumOf { it.size }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }

        SectionLabel("Your profile")
        SettingRow("Name", name.ifBlank { "Not set" }) { editingName = true }
        SettingRow("Status", "Available") { }
        SettingRow("Device identifier", app.nodeId.take(16) + "…") {
            clipboard.setText(AnnotatedString(app.nodeId))
        }

        SectionLabel("Appearance")
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Theme", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Light, dark, or whatever the desktop uses",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (value, label) ->
                Row(
                    Modifier.selectable(selected = theme == value, onClick = { app.setTheme(value) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = theme == value, onClick = { app.setTheme(value) })
                    Text(label, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(8.dp))
                }
            }
        }

        SectionLabel("Conversation")
        SwitchRow(
            "Read receipts",
            "Notify when you open a message",
            readReceipts,
        ) { app.setReadReceipts(it) }
        SwitchRow(
            "Typing indicator",
            "Show others when you are typing",
            typingIndicator,
        ) { app.setTypingIndicator(it) }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Keep messages for", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Older messages are deleted from this device only",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            listOf(0 to "Forever", 7 to "7 days", 30 to "30 days", 90 to "90 days").forEach { (days, label) ->
                Row(
                    Modifier.selectable(selected = retention == days, onClick = { app.setRetentionDays(days) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = retention == days, onClick = { app.setRetentionDays(days) })
                    Text(label, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(8.dp))
                }
            }
        }

        SectionLabel("Notifications")
        SwitchRow("Notifications", "Alerts for new messages", notifications) { app.setNotifications(it) }

        SectionLabel("How to reach others")
        SwitchRow("Local network", "Connects to devices on the same Wi-Fi", lan) { app.setLanEnabled(it) }
        SwitchRow(
            "Relay over the internet",
            "Needed to message people far away. It goes through the public broker broker.hivemq.com, " +
                "which sees encrypted data only: not the messages, not your name.",
            relay,
        ) { app.setPublicRelay(it) }
        SwitchRow("Forward for others", "Pass on messages for the rooms you know, to extend the reach", forwarding) {
            app.setForwarding(it)
        }

        Spacer(Modifier.height(10.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(14.dp),
        ) {
            Text("Diagnostics", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            InfoRow("Local network", if (lanActive) "active" else "starting")
            InfoRow("Relay", if (relayActive) "connected ${relayLabel}".trim() else "not connected")
            InfoRow("Connected peers", peers.toString())
            InfoRow("Rooms listening", chats.size.toString())
            InfoRow("Local content", "${chats.size} chats, $messageCount messages")
        }

        SectionLabel("Updates")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Installed version", style = MaterialTheme.typography.bodyMedium)
                Text(DesktopApp.APP_VERSION, style = MaterialTheme.typography.labelSmall)
            }
            Button(
                enabled = !checking,
                onClick = {
                    checking = true
                    scope.launch {
                        updateState = runCatching { app.checkUpdates() }.getOrNull()
                        checking = false
                    }
                },
            ) { Text(if (checking) "Checking…" else "Check for updates") }
        }
        updateState?.let { result ->
            Spacer(Modifier.height(8.dp))
            when (result) {
                is UpdateResult.UpToDate -> Text(
                    "You already have the latest version.",
                    style = MaterialTheme.typography.bodySmall,
                )

                is UpdateResult.Available -> Column {
                    Text(
                        "Version ${result.release.version} is available.",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        result.release.pageUrl,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                is UpdateResult.Failed -> Text(
                    result.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        SectionLabel("Data on this device")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Delete all messages", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Rooms and their codes stay",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = { confirmClear = true }) { Text("Delete") }
        }

        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(
            "ShutChat has no server. There is no account, no backup and no place where your messages are kept: " +
                "they travel between devices and stay where they arrived.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 620.dp),
        )
        Spacer(Modifier.height(24.dp))
    }

    if (editingName) {
        AlertDialog(
            onDismissRequest = { editingName = false },
            title = { Text("What is your name?") },
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
                    app.setDisplayName(nameDraft)
                    editingName = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingName = false }) { Text("Cancel") } },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete all messages?") },
            text = {
                Text(
                    "They are removed from this device only. The others keep seeing their copy, " +
                        "because there is no place to delete them for everyone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    app.clearAllMessages()
                    confirmClear = false
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SettingRow(label: String, value: String, onEdit: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onEdit) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("Edit")
        }
    }
}

@Composable
private fun SwitchRow(label: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
