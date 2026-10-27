package dev.shutchat.app.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.shutchat.app.domain.model.Chat
import dev.shutchat.app.ui.component.ChatRow
import dev.shutchat.app.ui.component.EmptyState
import dev.shutchat.app.ui.component.OptionTile
import dev.shutchat.app.ui.containerViewModel

/**
 * Conversation list.
 *
 * The header carries the transport state rather than hiding it in settings: with
 * no server, "am I reachable?" is the single most useful thing to show, and a
 * user who sees no transport active knows immediately why nothing is arriving.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenChat: (String) -> Unit,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
    onSettings: () -> Unit,
) {
    val viewModel = containerViewModel { HomeViewModel(it) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showAddSheet by remember { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<Chat?>(null) }
    var confirmDelete by remember { mutableStateOf<Chat?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("ShutChat", style = MaterialTheme.typography.titleLarge)
                        TransportStatusLine(
                            peers = state.connectedPeers,
                            lan = state.lanActive,
                            nearby = state.nearbyActive,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onJoin) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = "Entra con un codice")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Impostazioni")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddSheet = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Nuova") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.chats.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.ChatBubbleOutline,
                    title = "Nessuna chat",
                    message = "ShutChat non ha un server: non c'è niente da scaricare. " +
                        "Crea un codice con il + e passalo a chi vuoi sentire, oppure " +
                        "entra con un codice che ti hanno dato.",
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.chats, key = { it.id }) { chat ->
                        ChatRow(
                            chat = chat,
                            clock = viewModel.clock,
                            memberCount = state.memberCounts[chat.id] ?: 1,
                            onClick = {
                                viewModel.onChatOpened(chat.id)
                                onOpenChat(chat.id)
                            },
                            onLongClick = { actionTarget = chat },
                        )
                    }
                    item { Spacer(Modifier.height(96.dp)) }
                }
            }
        }
    }

    if (showAddSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAddSheet = false },
            sheetState = sheetState,
        ) {
            AddSheetContent(
                onCreate = {
                    showAddSheet = false
                    onCreate()
                },
                onJoin = {
                    showAddSheet = false
                    onJoin()
                },
            )
        }
    }

    actionTarget?.let { chat ->
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text(chat.title) },
            text = {
                Column {
                    TextButton(onClick = {
                        viewModel.setPinned(chat.id, !chat.pinned)
                        actionTarget = null
                    }) { Text(if (chat.pinned) "Rimuovi dai fissati" else "Fissa in alto") }
                    TextButton(onClick = {
                        viewModel.setMuted(chat.id, !chat.muted)
                        actionTarget = null
                    }) { Text(if (chat.muted) "Riattiva notifiche" else "Silenzia") }
                    TextButton(onClick = {
                        viewModel.setArchived(chat.id, true)
                        actionTarget = null
                    }) { Text("Archivia") }
                    TextButton(onClick = {
                        actionTarget = null
                        confirmDelete = chat
                    }) { Text("Elimina chat") }
                }
            },
            confirmButton = {
                TextButton(onClick = { actionTarget = null }) { Text("Chiudi") }
            },
        )
    }

    confirmDelete?.let { chat ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Eliminare la chat?") },
            text = {
                Text(
                    "La conversazione e i suoi messaggi vengono rimossi solo da questo " +
                        "dispositivo. Chi ha ancora il codice continua a vedere la stanza.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(chat.id)
                    confirmDelete = null
                }) { Text("Elimina") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Annulla") }
            },
        )
    }

    LaunchedEffect(Unit) {
        if (!state.onboardingDone && state.chats.isNotEmpty()) {
            viewModel.markOnboardingDone()
        }
    }
}

/** One line summarising how, or whether, this device is reachable. */
@Composable
private fun TransportStatusLine(peers: Int, lan: Boolean, nearby: Boolean) {
    val parts = buildList {
        if (lan) add("Rete locale")
        if (nearby) add("Bluetooth")
    }
    val text = when {
        parts.isEmpty() -> "Nessun trasporto attivo"
        peers == 0 -> "In ascolto · ${parts.joinToString(" + ")}"
        peers == 1 -> "1 peer collegato · ${parts.joinToString(" + ")}"
        else -> "$peers peer collegati · ${parts.joinToString(" + ")}"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (lan) {
            Icon(
                Icons.Default.Wifi,
                contentDescription = null,
                modifier = Modifier.size(11.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(3.dp))
        }
        if (nearby) {
            Icon(
                Icons.Default.Bluetooth,
                contentDescription = null,
                modifier = Modifier.size(11.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(3.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The sheet behind the "+" button.
 *
 * Two options only. Anything more and the user has to think; with these two the
 * choice is "I start a room" or "I was given a room".
 */
@Composable
private fun AddSheetContent(onCreate: () -> Unit, onJoin: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .width(36.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Inizia qualcosa",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "Le chat di ShutChat vivono sui dispositivi. Un codice è l'unico modo per far " +
                "entrare qualcuno.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 18.dp),
        )
        OptionTile(
            icon = Icons.Default.Add,
            title = "Crea un codice",
            subtitle = "Chat diretta o gruppo, con scadenza a scelta",
            onClick = onCreate,
        )
        Spacer(Modifier.height(10.dp))
        OptionTile(
            icon = Icons.Default.QrCodeScanner,
            title = "Entra con un codice",
            subtitle = "Scrivilo, incollalo o inquadra il QR",
            onClick = onJoin,
        )
    }
}
