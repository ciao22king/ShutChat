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
import androidx.compose.material.icons.filled.Bluetooth
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
                title = { Text("Impostazioni") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
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
            SectionHeader("Il tuo profilo")

            SettingRow(
                title = "Nome",
                subtitle = state.displayName.ifBlank { "Non impostato" },
                onClick = {
                    nameDraft = state.displayName
                    editingName = true
                },
            )
            SettingRow(
                title = "Stato",
                subtitle = state.status.ifBlank { "Disponibile" },
            )
            SettingRow(
                title = "Identificativo del dispositivo",
                subtitle = nodeId.take(16),
                trailing = {
                    IconButton(onClick = { clipboard.setText(AnnotatedString(nodeId)) }) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copia",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
            )

            SectionHeader("Aspetto")

            SettingSwitch(
                title = "Segui il sistema",
                subtitle = "Tema chiaro o scuro secondo il telefono",
                checked = state.theme == "system",
                onCheckedChange = { viewModel.setTheme(if (it) "system" else "dark") },
            )
            SettingSwitch(
                title = "Tema scuro",
                subtitle = "Attivo quando non segui il sistema",
                checked = state.theme == "dark",
                enabled = state.theme != "system",
                onCheckedChange = { viewModel.setTheme(if (it) "dark" else "light") },
            )
            SettingSwitch(
                title = "Colori dinamici",
                subtitle = "Usa la tavolozza di Android 12 e successivi",
                checked = state.dynamicColor,
                onCheckedChange = viewModel::setDynamicColor,
            )

            SectionHeader("Conversazione")

            SettingSwitch(
                title = "Spunte di lettura",
                subtitle = "Avvisa quando apri un messaggio",
                checked = state.readReceipts,
                onCheckedChange = viewModel::setReadReceipts,
            )
            SettingSwitch(
                title = "Indicatore di scrittura",
                subtitle = "Mostra agli altri quando stai scrivendo",
                checked = state.typingIndicator,
                onCheckedChange = viewModel::setTypingIndicator,
            )
            SettingSwitch(
                title = "Scarica allegati in automatico",
                subtitle = "Altrimenti il file arriva solo quando lo apri",
                checked = state.autoDownload,
                onCheckedChange = viewModel::setAutoDownload,
            )

            Text(
                text = "Conserva i messaggi per ${state.retentionDays} giorni",
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
                text = "I messaggi più vecchi vengono cancellati solo da questo dispositivo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionHeader("Notifiche")

            SettingSwitch(
                title = "Notifiche",
                subtitle = "Avvisi per i messaggi nuovi",
                checked = state.notificationsEnabled,
                onCheckedChange = viewModel::setNotificationsEnabled,
            )
            SettingSwitch(
                title = "Suoni",
                subtitle = "Un tono per ogni messaggio",
                checked = state.messageSounds,
                enabled = state.notificationsEnabled,
                onCheckedChange = viewModel::setMessageSounds,
            )
            SettingSwitch(
                title = "Vibrazione",
                subtitle = "Vibra sui messaggi nuovi",
                checked = state.vibration,
                enabled = state.notificationsEnabled,
                onCheckedChange = viewModel::setVibration,
            )
            SettingSwitch(
                title = "Resta in ascolto",
                subtitle = "Mantiene la ricezione quando l'app è chiusa, con una notifica fissa",
                checked = state.backgroundMode,
                onCheckedChange = viewModel::setBackgroundMode,
            )

            SectionHeader("Come raggiungere gli altri")

            SettingSwitch(
                icon = Icons.Default.Wifi,
                title = "Rete locale",
                subtitle = "Si collega ai dispositivi sulla stessa Wi-Fi",
                checked = state.lanDiscovery,
                onCheckedChange = viewModel::setLanDiscovery,
            )
            SettingSwitch(
                icon = Icons.Default.Bluetooth,
                title = "Bluetooth / Wi-Fi Direct",
                subtitle = "Funziona anche senza router, se siete vicini",
                checked = state.nearbyDiscovery,
                onCheckedChange = viewModel::setNearbyDiscovery,
            )
            SettingSwitch(
                icon = Icons.Default.Hub,
                title = "Inoltra per gli altri",
                subtitle = "Passa i messaggi delle stanze che conosci, per allungare la portata",
                checked = state.relayEnabled,
                onCheckedChange = viewModel::setRelayEnabled,
            )
            SettingSwitch(
                icon = Icons.Default.Public,
                title = "Relay su internet",
                subtitle = "Attivo: serve per scrivere a chi è lontano. Passa dal broker " +
                    "pubblico broker.hivemq.com, che vede solo dati cifrati: non i messaggi, " +
                    "non il tuo nome. Spegnilo per restare solo sulla rete locale.",
                checked = state.publicRelay,
                onCheckedChange = viewModel::setPublicRelay,
            )

            SectionHeader("Aggiornamenti")

            UpdateRow(
                version = state.appVersion,
                updateState = state.updateState,
                onCheck = viewModel::checkForUpdates,
            )

            SectionHeader("Dati su questo dispositivo")

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
                    Text("Cancella tutti i messaggi", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Le stanze e i loro codici restano",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = "ShutChat non ha un server. Non esiste un account, un backup o un posto " +
                    "dove i tuoi messaggi vengano conservati: viaggiano tra i dispositivi e " +
                    "restano dove sono arrivati.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(40.dp))
        }
    }

    if (editingName) {
        AlertDialog(
            onDismissRequest = { editingName = false },
            title = { Text("Come ti chiami?") },
            text = {
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it },
                    label = { Text("Nome") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setDisplayName(nameDraft.trim())
                    editingName = false
                }) { Text("Salva") }
            },
            dismissButton = {
                TextButton(onClick = { editingName = false }) { Text("Annulla") }
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Cancellare tutti i messaggi?") },
            text = {
                Text(
                    "Vengono rimossi solo da questo dispositivo. Gli altri continuano a vedere " +
                        "la loro copia, perché non esiste un posto da cui cancellarli per tutti.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAllMessages()
                    confirmClear = false
                }) { Text("Cancella") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Annulla") }
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
                Text("Versione installata", style = MaterialTheme.typography.titleMedium)
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
                Text(if (checking) "Controllo…" else "Cerca aggiornamenti")
            }
        }
        val message = when (updateState) {
            is UpdateCheckState.Idle -> null
            is UpdateCheckState.Checking -> null
            is UpdateCheckState.UpToDate -> "Hai già l'ultima versione."
            is UpdateCheckState.Available -> "Disponibile la versione ${updateState.version}."
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
                text = "Scarica l'APK",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clickable { openUrl(updateState.url) },
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
            label = "Rete locale",
            value = when {
                !diagnostics.lanActive -> "spenta"
                diagnostics.lanPort != 0 -> "attiva sulla porta ${diagnostics.lanPort}"
                else -> "in avvio"
            },
        )
        DiagnosticLine(
            icon = Icons.Default.Bluetooth,
            label = "Bluetooth",
            value = if (diagnostics.nearbyActive) "attivo" else "spento",
        )
        DiagnosticLine(
            icon = Icons.Default.Public,
            label = "Relay su internet",
            value = when {
                !diagnostics.relayActive && diagnostics.relayError == null -> "spento"
                diagnostics.relayActive -> "collegato"
                else -> "non collegato: ${diagnostics.relayError}"
            },
        )
        DiagnosticLine(
            icon = Icons.Default.Devices,
            label = "Peer collegati",
            value = diagnostics.connectedPeers.toString(),
        )
        DiagnosticLine(
            icon = Icons.Default.Forum,
            label = "Stanze in ascolto",
            value = diagnostics.roomsAdvertised.toString(),
        )
        DiagnosticLine(
            icon = Icons.Default.Hub,
            label = "Frame inoltrati",
            value = diagnostics.framesRelayed.toString(),
        )
        DiagnosticLine(
            icon = Icons.Default.Info,
            label = "Messaggi non decifrati",
            value = diagnostics.decryptFailures.toString(),
        )
        DiagnosticLine(
            icon = Icons.Default.Palette,
            label = "Contenuto locale",
            value = "$chatCount chat, $messageCount messaggi",
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
