package dev.shutchat.app.ui.screens.conversation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.shutchat.app.domain.expiry.Expiry
import dev.shutchat.app.domain.model.ChatStatus
import dev.shutchat.app.domain.model.Message
import dev.shutchat.app.domain.model.MessageKind
import dev.shutchat.app.ui.component.ExpiredCodeBanner
import dev.shutchat.app.ui.component.GroupInviteHint
import dev.shutchat.app.ui.component.InitialsAvatar
import dev.shutchat.app.ui.component.MessageBubble
import dev.shutchat.app.ui.component.TypingIndicator
import dev.shutchat.app.ui.containerViewModel
import dev.shutchat.app.util.FileOpener

/**
 * The conversation.
 *
 * The list is bottom-anchored and inverted in effect: new messages push the
 * viewport, and the transcript scrolls up into history. Day separators and
 * grouped bubbles are computed from the list itself rather than stored, so a
 * message that arrives out of order still lands in the right visual group.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    chatId: String,
    onBack: () -> Unit,
    onOpenGroup: () -> Unit,
) {
    val viewModel = containerViewModel { ConversationViewModel(it, chatId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var composer by remember { mutableStateOf("") }
    var actionTarget by remember { mutableStateOf<Message?>(null) }
    var confirmDelete by remember { mutableStateOf<Message?>(null) }
    var attachMenu by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val picked = uri?.let { viewModel.mediaStore.describe(it) }
        if (picked != null) viewModel.sendAttachment(picked, MessageKind.IMAGE)
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        val picked = uri?.let { viewModel.mediaStore.describe(it) }
        if (picked != null) viewModel.sendAttachment(picked, MessageKind.FILE)
    }

    LaunchedEffect(chatId) {
        composer = viewModel.draft()
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    val chat = state.chat
    val expired = chat?.status == ChatStatus.EXPIRED

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        InitialsAvatar(
                            seed = chat?.id ?: chatId,
                            name = chat?.title ?: "Chat",
                            size = 34.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = chat?.title ?: "Chat",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                            )
                            val subtitle = when {
                                state.connectedPeers > 0 -> {
                                    val count = state.members.count { !it.isSelf }
                                    if (chat?.isGroup == true) {
                                        "${state.members.size} membri · ${state.connectedPeers} collegati"
                                    } else {
                                        "Collegato"
                                    }
                                }

                                else -> "Nessun peer collegato"
                            }
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
                actions = {
                    if (chat?.isGroup == true) {
                        IconButton(onClick = onOpenGroup) {
                            Icon(Icons.Default.Groups, contentDescription = "Membri del gruppo")
                        }
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
                .imePadding()
                .navigationBarsPadding(),
        ) {
            if (expired) {
                ExpiredCodeBanner(
                    deadlineLabel = Expiry.describeDeadline(chat?.inviteExpiryDay, viewModel.clock),
                    onRotate = { viewModel.rotateCode(Expiry.Preset.ONE_WEEK) },
                )
            }
            if (chat?.isGroup == true && state.members.size <= 2) {
                GroupInviteHint(memberCount = state.members.size, onAdd = onOpenGroup)
            }

            Box(modifier = Modifier.weight(1f)) {
                if (state.messages.isEmpty()) {
                    EmptyConversationHint(
                        connected = state.connectedPeers > 0,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 12.dp,
                            vertical = 10.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        itemsIndexed(state.messages, key = { _, item -> item.id }) { index, message ->
                            val previous = state.messages.getOrNull(index - 1)
                            val next = state.messages.getOrNull(index + 1)
                            val showDay = previous == null ||
                                !sameDay(previous.createdAt, message.createdAt)
                            val showSender = chat?.isGroup == true &&
                                (previous == null || previous.senderNodeId != message.senderNodeId)

                            if (showDay) {
                                DaySeparator(timestamp = message.createdAt)
                            }
                            MessageBubble(
                                message = message,
                                showSender = showSender,
                                isFirstInGroup = previous?.senderNodeId != message.senderNodeId,
                                isLastInGroup = next?.senderNodeId != message.senderNodeId,
                                onLongPress = { actionTarget = message },
                            )
                        }
                    }
                }
            }

            TypingIndicator(
                names = state.typingNames,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp),
            )

            state.replyingTo?.let { target ->
                ComposeContextBar(
                    label = "Rispondi a ${target.senderName}",
                    preview = previewOf(target),
                    onCancel = viewModel::cancelComposeMode,
                )
            }
            state.editing?.let { target ->
                ComposeContextBar(
                    label = "Modifica messaggio",
                    preview = target.body,
                    onCancel = viewModel::cancelComposeMode,
                )
            }

            Composer(
                value = composer,
                onValueChange = {
                    composer = it
                    viewModel.onTyping()
                    viewModel.saveDraft(it)
                },
                enabled = !expired,
                onSend = {
                    viewModel.send(composer)
                    composer = ""
                    viewModel.saveDraft("")
                },
                onAttachImage = {
                    if (hasPermission(context, Manifest.permission.READ_MEDIA_IMAGES)) {
                        imagePicker.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    } else {
                        filePicker.launch("image/*")
                    }
                },
                onAttachFile = { filePicker.launch("*/*") },
                attachMenu = attachMenu,
                onAttachMenuChange = { attachMenu = it },
            )
        }
    }

    actionTarget?.let { message ->
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text("Messaggio") },
            text = {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        listOf("👍", "❤️", "😂", "😮", "🙏").forEach { emoji ->
                            Text(
                                text = emoji,
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable {
                                        viewModel.react(message, emoji)
                                        actionTarget = null
                                    }
                                    .padding(6.dp),
                            )
                        }
                    }
                    TextButton(onClick = {
                        viewModel.replyTo(message)
                        actionTarget = null
                    }) {
                        Icon(Icons.Default.Reply, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Rispondi")
                    }
                    if (message.isOutgoing && message.kind == MessageKind.TEXT) {
                        TextButton(onClick = {
                            viewModel.edit(message)
                            composer = message.body
                            actionTarget = null
                        }) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("Modifica")
                        }
                    }
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(message.body))
                        actionTarget = null
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Copia testo")
                    }
                    if (message.attachment?.localPath != null) {
                        TextButton(onClick = {
                            val attachment = message.attachment
                            FileOpener.open(context, attachment.localPath, attachment.mimeType)
                            actionTarget = null
                        }) {
                            Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("Apri allegato")
                        }
                    }
                    if (message.isOutgoing) {
                        TextButton(onClick = {
                            actionTarget = null
                            confirmDelete = message
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("Elimina per tutti")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { actionTarget = null }) { Text("Chiudi") }
            },
        )
    }

    confirmDelete?.let { message ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Eliminare il messaggio?") },
            text = {
                Text(
                    "Verrà rimosso anche sui dispositivi degli altri, se sono raggiungibili. " +
                        "Chi era offline potrebbe non ricevere l'eliminazione.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(message)
                    confirmDelete = null
                }) { Text("Elimina") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Annulla") }
            },
        )
    }

    state.error?.let { message ->
        LaunchedEffect(message) {
            snackbar.showSnackbar(message)
            viewModel.dismissError()
        }
    }
}

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onSend: () -> Unit,
    onAttachImage: () -> Unit,
    onAttachFile: () -> Unit,
    attachMenu: Boolean,
    onAttachMenuChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box {
            IconButton(
                onClick = { onAttachMenuChange(true) },
                enabled = enabled,
            ) {
                Icon(
                    Icons.Default.AttachFile,
                    contentDescription = "Allega",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(
                expanded = attachMenu,
                onDismissRequest = { onAttachMenuChange(false) },
            ) {
                DropdownMenuItem(
                    text = { Text("Foto") },
                    leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                    onClick = {
                        onAttachMenuChange(false)
                        onAttachImage()
                    },
                )
                DropdownMenuItem(
                    text = { Text("File") },
                    leadingIcon = { Icon(Icons.Default.AttachFile, contentDescription = null) },
                    onClick = {
                        onAttachMenuChange(false)
                        onAttachFile()
                    },
                )
            }
        }

        TextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            placeholder = { Text(if (enabled) "Scrivi un messaggio" else "Codice scaduto") },
            maxLines = 5,
            shape = RoundedCornerShape(22.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            keyboardActions = KeyboardActions(),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
        )

        val canSend = enabled && value.isNotBlank()
        Box(
            modifier = Modifier
                .padding(start = 4.dp, bottom = 4.dp)
                .size(46.dp)
                .clip(CircleShape)
                .background(
                    if (canSend) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                )
                .clickable(enabled = canSend, onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "Invia",
                tint = if (canSend) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun ComposeContextBar(label: String, preview: String, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        IconButton(onClick = onCancel) {
            Icon(Icons.Default.Close, contentDescription = "Annulla")
        }
    }
}

@Composable
private fun DaySeparator(timestamp: Long) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = dev.shutchat.app.core.Format.daySeparator(timestamp, System.currentTimeMillis()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * Shown in an empty conversation.
 *
 * The copy is the important part: a user who sees nothing must understand that
 * the other side has to be on the same network, or the app looks broken.
 */
@Composable
private fun EmptyConversationHint(connected: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        InitialsAvatar(seed = "shutchat", name = "SC", size = 56.dp)
        Spacer(Modifier.height(16.dp))
        Text(
            text = if (connected) "Siete collegati" else "In attesa di un peer",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (connected) {
                "Scrivete pure: i messaggi vanno da dispositivo a dispositivo, senza passare da " +
                    "nessun server."
            } else {
                "Questa stanza esiste solo sui dispositivi che hanno il codice. Per ricevere " +
                    "bisogna essere sulla stessa rete Wi-Fi oppure vicini, con il Bluetooth attivo."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

private fun previewOf(message: Message): String = when (message.kind) {
    MessageKind.IMAGE -> "📷 Foto"
    MessageKind.AUDIO -> "🎤 Messaggio vocale"
    MessageKind.FILE -> "📎 " + (message.attachment?.fileName ?: "File")
    else -> message.body.take(120)
}

private fun hasPermission(context: android.content.Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun sameDay(a: Long, b: Long): Boolean {
    val zone = java.util.TimeZone.getDefault()
    val day = 24L * 60 * 60 * 1000
    return (a + zone.getOffset(a)) / day == (b + zone.getOffset(b)) / day
}
