package dev.shutchat.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.shutchat.app.p2p.MeshDiagnostics
import dev.shutchat.app.ui.component.SectionHeader
import dev.shutchat.app.ui.containerViewModel
import dev.shutchat.app.ui.theme.CodeStyle
import dev.shutchat.app.ui.theme.LocalChatColors

/**
 * Settings.
 *
 * Ordered by what a user actually needs to decide: who they are, then how the app
 * behaves, then how it reaches people, and finally a diagnostics block. The
 * diagnostics are not hidden in a debug menu because this app's failure mode is
 * "nobody can reach me", and the user is the only one who can check that.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val viewModel = containerViewModel { SettingsViewModel(it) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val nodeId by viewModel.nodeId.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var editingName by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            SectionHeader("Your profile")

            SettingRow(
                title = "Name",
                subtitle = state.displayName.ifBlank { "Not set" },
                onClick = {
                    nameDraft = state.displayName
                    editingName = true
                },
            )
            SettingRow(
                title = "Status",
                subtitle = state.status.ifBlank { "Available" },
            )
            SettingRow(
                title = "Device identifier",
                subtitle = nodeId.take(16),
                trailing = {
                    IconButton(onClick = { clipboard.setText(AnnotatedString(nodeId)) }) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copy",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
            )

            SectionHeader("Appearance")

            SettingSwitch(
                title = "Follow the system",
                subtitle = "Light or dark theme, following the phone",
                checked = state.theme == "system",
                onCheckedChange = { viewModel.setTheme(if (it) "system" else "dark") },
            )
            SettingSwitch(
                title = "Dark theme",
                subtitle = "Active when not following the system",
                checked = state.theme == "dark",
                enabled = state.theme != "system",
                onCheckedChange = { viewModel.setTheme(if (it) "dark" else "light") },
            )
            SettingSwitch(
                title = "Dynamic colours",
                subtitle = "Use the Android 12+ palette",
                checked = state.dynamicColor,
                onCheckedChange = viewModel::setDynamicColor,
            )

            SectionHeader("Conversation")

            SettingSwitch(
                title = "Read receipts",
                subtitle = "Notify when you open a message",
                checked = state.readReceipts,
                onCheckedChange = viewModel::setReadReceipts,
            )
            SettingSwitch(
                title = "Typing indicator",
                subtitle = "Show others when you are typing",
                checked = state.typingIndicator,
                onCheckedChange = viewModel::setTypingIndicator,
            )
            SettingSwitch(
                title = "Download attachments automatically",
                subtitle = "Otherwise the file arrives only when you open it",
                checked = state.autoDownload,
                onCheckedChange = viewModel::setAutoDownload,
            )

            Text(
                text = "Keep messages for ${state.retentionDays} days",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 14.dp),
            )
            Slider(
                value = state.retentionDays.toFloat(),
                onValueChange = { viewModel.setRetentionDays(it.toInt()) },
                valueRange = 1f..365f,
                steps = 0,
            )
            Text(
                text = "Older messages are deleted from this device only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionHeader("Notifications")

            SettingSwitch(
                title = "Notifications",
                subtitle = "Alerts for new messages",
                checked = state.notificationsEnabled,
                onCheckedChange = viewModel::setNotificationsEnabled,
            )
            SettingSwitch(
                title = "Sounds",
                subtitle = "A tone for every message",
                checked = state.messageSounds,
                enabled = state.notificationsEnabled,
                onCheckedChange = viewModel::setMessageSounds,
            )
            SettingSwitch(
                title = "Vibration",
                subtitle = "Vibrate on new messages",
                checked = state.vibration,
                enabled = state.notificationsEnabled,
                onCheckedChange = viewModel::setVibration,
            )
            SettingSwitch(
                title = "Stay listening",
                subtitle = "Keeps receiving when the app is closed, with a fixed notification",
                checked = state.backgroundMode,
                onCheckedChange = viewModel::setBackgroundMode,
            )

            SectionHeader("How to reach others")

            SettingSwitch(
                icon = Icons.Default.Wifi,
                title = "Local network",
                subtitle = "Connects to devices on the same Wi-Fi",
                checked = state.lanDiscovery,
                onCheckedChange = viewModel::setLanDiscovery,
            )
            SettingSwitch(
                icon = Icons.Default.Wifi,
                title = "Wi-Fi (mDNS)",
                subtitle = "Works without a router too, when you are close",
                checked = state.nearbyDiscovery,
                onCheckedChange = viewModel::setNearbyDiscovery,
            )
            SettingSwitch(
                icon = Icons.Default.Hub,
                title = "Forward for others",
                subtitle = "Pass on messages for the rooms you know, to extend the reach",
                checked = state.relayEnabled,
                onCheckedChange = viewModel::setRelayEnabled,
            )
            SettingSwitch(
                icon = Icons.Default.Public,
                title = "Relay over the internet",
                subtitle = "On: needed to message people far away. It goes through the public " +
                    "broker broker.hivemq.com, which sees encrypted data only: not the messages, " +
                    "not your name. Turn it off to stay on the local network only.",
                checked = state.publicRelay,
                onCheckedChange = viewModel::setPublicRelay,
            )

            SectionHeader("Updates")

            UpdateRow(
                version = state.appVersion,
                updateState = state.updateState,
                onCheck = viewModel::checkForUpdates,
            )

            SectionHeader("Data on this device")

            DiagnosticsBlock(
                diagnostics = state.diagnostics,
                chatCount = state.chatCount,
                messageCount = state.messageCount,
            )

            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(LocalChatColors.current.expired.copy(alpha = 0.12f))
                    .clickable { confirmClear = true }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.DeleteSweep,
                    contentDescription = null,
                    tint = LocalChatColors.current.expired,
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Delete all messages", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Rooms and their codes stay",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = "ShutChat has no server. There is no account, no backup and no place " +
                    "where your messages are kept: they travel between devices and " +
                    "stay where they arrived.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(40.dp))
        }
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
                    viewModel.setDisplayName(nameDraft.trim())
                    editingName = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingName = false }) { Text("Cancel") }
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete all messages?") },
            text = {
                Text(
                    "They are removed from this device only. The others keep seeing " +
                        "their copy, because there is no place to delete them for everyone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAllMessages()
                    confirmClear = false
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        trailing?.invoke()
    }
}

/**
 * The update row.
 *
 * Every outcome gets its own line of text: a check that silently does nothing is
 * indistinguishable from one that failed, and the user is the only one who can
 * tell whether the phone had a connection at that moment.
 */
@Composable
private fun UpdateRow(
    version: String,
    updateState: UpdateCheckState,
    onCheck: () -> Unit,
) {
    val checking = updateState is UpdateCheckState.Checking
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Installed version", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = version.ifBlank { "sconosciuta" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onCheck, enabled = !checking) {
                if (checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (checking) "Checking…" else "Check for updates")
            }
        }
        val message = when (updateState) {
            is UpdateCheckState.Idle -> null
            is UpdateCheckState.Checking -> null
            is UpdateCheckState.UpToDate -> "You already have the latest version."
            is UpdateCheckState.Available -> "Version ${updateState.version} is available."
            is UpdateCheckState.Failed -> updateState.reason
        }
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (updateState is UpdateCheckState.Failed) {
                    LocalChatColors.current.expired
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (updateState is UpdateCheckState.Available && updateState.url != null) {
            Text(
                text = "Download the APK",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clickable { openUrl(updateState.url) },
            )
            // Android refuses to update an app whose new APK is signed with a different
            // key, and it reports that only as "App not installed", which reads like a
            // corrupt download. Anyone coming from a build made before the signing key
            // was pinned will hit exactly that, so say what to do about it.
            Text(
                text = "If the install says \"App not installed\", uninstall ShutChat and " +
                    "reinstall: very old versions were signed with another key. " +
                    "Messages stay on the phone until you uninstall.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** Opens a link in whatever the phone uses for links. */
private fun openUrl(url: String) {
    runCatching {
        val intent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse(url),
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext()?.startActivity(intent)
    }
}

private fun appContext(): android.content.Context? = dev.shutchat.app.ShutChatApp.get()

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(14.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

/**
 * Live transport state.
 *
 * Deliberately technical: it is the one screen a user reaches for when a message
 * will not go through, so it shows port, peer count and relay counters rather
 * than a friendly green dot that hides the reason.
 */
@Composable
private fun DiagnosticsBlock(
    diagnostics: MeshDiagnostics,
    chatCount: Int,
    messageCount: Int,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DiagnosticLine(
            icon = Icons.Default.Dns,
            label = "Local network",
            value = when {
                !diagnostics.lanActive -> "spenta"
                diagnostics.lanPort != 0 -> "active on port ${diagnostics.lanPort}"
                else -> "starting"
            },
        )
        DiagnosticLine(
            icon = Icons.Default.Wifi,
            label = "Wi-Fi",
            value = if (diagnostics.nearbyActive) "attivo" else "spento",
        )
        DiagnosticLine(
            icon = Icons.Default.Public,
            label = "Relay over the internet",
            value = when {
                !diagnostics.relayActive && diagnostics.relayError == null -> "spento"
                diagnostics.relayActive -> "collegato"
                else -> "not connected: ${diagnostics.relayError}"
            },
        )
        DiagnosticLine(
            icon = Icons.Default.Devices,
            label = "Connected peers",
            value = diagnostics.connectedPeers.toString(),
        )
        DiagnosticLine(
            icon = Icons.Default.Forum,
            label = "Rooms listening",
            value = diagnostics.roomsAdvertised.toString(),
        )
        DiagnosticLine(
            icon = Icons.Default.Hub,
            label = "Frames forwarded",
            value = diagnostics.framesRelayed.toString(),
        )
        DiagnosticLine(
            icon = Icons.Default.Info,
            label = "Undecryptable messages",
            value = diagnostics.decryptFailures.toString(),
        )
        DiagnosticLine(
            icon = Icons.Default.Palette,
            label = "Local content",
            value = "$chatCount chats, $messageCount messages",
        )
    }
}

@Composable
private fun DiagnosticLine(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = CodeStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
