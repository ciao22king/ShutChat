package dev.shutchat.app.ui.screens.group

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.shutchat.app.ui.component.InviteCodeCard
import dev.shutchat.app.ui.component.PresenceAvatar
import dev.shutchat.app.ui.component.SectionHeader
import dev.shutchat.app.ui.component.StatusPill
import dev.shutchat.app.ui.containerViewModel
import dev.shutchat.app.util.ShareUtils
import dev.shutchat.app.ui.theme.LocalChatColors

/**
 * Group details.
 *
 * A group in ShutChat is not a list of members on a server: it is the set of
 * devices that hold the same code. So "adding someone" is sharing the code, and
 * the member list is whoever has actually been seen in the room. The screen says
 * that plainly instead of pretending there is a directory to browse.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupScreen(
    chatId: String,
    onBack: () -> Unit,
    onOpenChat: () -> Unit,
) {
    val viewModel = containerViewModel { GroupViewModel(it, chatId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val chat = state.chat
    val context = LocalContext.current
    var renaming by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var showQr by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<dev.shutchat.app.domain.model.ChatMember?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dettagli gruppo") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        newName = state.chat?.title.orEmpty()
                        renaming = true
                    }) {
                        Icon(Icons.Default.Edit, contentDescription = "Rinomina")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            item {
                Text(
                    text = state.chat?.title ?: "Gruppo",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = "${state.members.size} membri · ${state.connected} collegati ora",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 18.dp),
                )
            }

            item {
                SectionHeader("Codice del gruppo")
            }

            item {
                chat?.inviteCode?.let { code ->
                    InviteCodeCard(
                        code = code,
                        expiryDay = chat.inviteExpiryDay,
                        clock = viewModel.clock,
                        onCopy = { ShareUtils.copyCode(context, code) },
                        onShare = { ShareUtils.shareCode(context, code, chat.title) },
                    )
                } ?: Text(
                    text = "Nessun codice attivo.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton(
                        icon = Icons.Default.Share,
                        label = "Condividi",
                        onClick = {
                            chat?.inviteCode?.let { ShareUtils.shareCode(context, it, chat.title) }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    ActionButton(
                        icon = Icons.Default.QrCode2,
                        label = "Mostra QR",
                        onClick = { showQr = true },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            item {
                SectionHeader("Membri")
                Text(
                    text = "Sono i dispositivi che hanno usato il codice. Non c'è un elenco " +
                        "centrale: se qualcuno esce, sparisce da qui la prossima volta che si collega.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            items(state.members, key = { it.nodeId }) { member ->
                MemberRow(
                    member = member,
                    isOwner = state.ownerNodeId == member.nodeId,
                    canRemove = state.isOwner && !member.isSelf,
                    onRemove = { removeTarget = member },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            item {
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onOpenChat,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                ) { Text("Torna alla chat") }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Nome del gruppo") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Nome") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.rename(newName.trim())
                    renaming = false
                }) { Text("Salva") }
            },
            dismissButton = {
                TextButton(onClick = { renaming = false }) { Text("Annulla") }
            },
        )
    }

    if (showQr) {
        chat?.inviteCode?.let { code ->
            QrDialog(code = code, onDismiss = { showQr = false })
        }
    }

    removeTarget?.let { member ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("Rimuovere ${member.displayName}?") },
            text = {
                Text(
                    "Sparirà da questo elenco. Se ha ancora il codice può rientrare, perché " +
                        "l'accesso dipende dal codice e non da un permesso.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.remove(member.nodeId)
                    removeTarget = null
                }) { Text("Rimuovi") }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) { Text("Annulla") }
            },
        )
    }
}

@Composable
private fun MemberRow(
    member: dev.shutchat.app.domain.model.ChatMember,
    isOwner: Boolean,
    canRemove: Boolean,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PresenceAvatar(
            seed = member.avatarSeed,
            name = member.displayName,
            online = member.online,
            size = 42.dp,
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (member.isSelf) "${member.displayName} (tu)" else member.displayName,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (isOwner) {
                    Spacer(Modifier.width(8.dp))
                    StatusPill(text = "creatore", color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(
                text = member.nodeId.take(12),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (member.online) {
            StatusPill(text = "collegato", color = LocalChatColors.current.online)
        } else if (canRemove) {
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.PersonRemove,
                    contentDescription = "Rimuovi",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/** QR of the invite, rendered once and cached by the dialog's remember. */
@Composable
private fun QrDialog(code: String, onDismiss: () -> Unit) {
    val invite = remember(code) { runCatching { dev.shutchat.app.domain.codec.InviteCodec.parse(code) }.getOrNull() }
    val payload = invite?.let(dev.shutchat.app.domain.codec.InviteCodec::toUri) ?: code
    val bitmap = remember(payload) { dev.shutchat.app.util.QrGenerator.render(payload, 720) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Inquadra per entrare") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (bitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Codice QR",
                        modifier = Modifier
                            .size(260.dp)
                            .clip(RoundedCornerShape(16.dp)),
                    )
                } else {
                    Text("Non è stato possibile generare il QR. Usa il codice testuale.")
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = code,
                    style = dev.shutchat.app.ui.theme.CodeStyle.copy(fontSize = 13.sp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Chiudi") }
        },
    )
}
