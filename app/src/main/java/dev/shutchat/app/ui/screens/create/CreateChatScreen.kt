package dev.shutchat.app.ui.screens.create

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.shutchat.app.domain.expiry.Expiry
import dev.shutchat.app.domain.model.InviteKind
import dev.shutchat.app.ui.component.InviteCodeCard
import dev.shutchat.app.ui.containerViewModel
import dev.shutchat.app.util.ShareUtils

/**
 * Creating a room.
 *
 * Three states in one screen rather than three destinations: the user never
 * needs to go back to the middle step, and keeping them in one place means the
 * code is not lost when the activity is recreated.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateChatScreen(
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
) {
    val viewModel = containerViewModel { CreateChatViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var title by remember { mutableStateOf("") }
    var toast by remember { mutableStateOf<String?>(null) }

    val reveal = state.step as? CreateChatViewModel.Step.Reveal
    LaunchedEffect(reveal?.chatId) {
        val chatId = reveal?.chatId ?: return@LaunchedEffect
        title = viewModel.title(chatId)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (state.step) {
                            is CreateChatViewModel.Step.ChooseKind -> "Nuova chat"
                            is CreateChatViewModel.Step.ChooseExpiry -> "Scadenza del codice"
                            is CreateChatViewModel.Step.Reveal -> "Il tuo codice"
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (reveal == null) viewModel.back() else onBack() }) {
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
            when (val step = state.step) {
                is CreateChatViewModel.Step.ChooseKind -> ChooseKindStep(
                    onDirect = { viewModel.chooseKind(InviteKind.DIRECT) },
                    onGroup = { viewModel.chooseKind(InviteKind.GROUP) },
                )

                is CreateChatViewModel.Step.ChooseExpiry -> ChooseExpiryStep(
                    kind = state.kind,
                    selected = state.preset,
                    groupName = state.groupName,
                    busy = state.busy,
                    onPreset = viewModel::setPreset,
                    onGroupName = viewModel::setGroupName,
                    onConfirm = viewModel::confirm,
                )

                is CreateChatViewModel.Step.Reveal -> RevealStep(
                    code = step.code,
                    expiryDay = step.expiryDay,
                    clock = viewModel.clock,
                    chatTitle = title,
                    memberCount = 1,
                    busy = state.busy,
                    onCopy = {
                        clipboard.setText(AnnotatedString(step.code))
                    },
                    onShare = {
                        ShareUtils.shareCode(context, step.code, title)
                    },
                    onRotate = viewModel::rotate,
                    onOpen = { onOpenChat(step.chatId) },
                    onSnack = { message -> toast = message },
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    state.error?.let { message ->
        LaunchedEffect(message) {
            snackbar.showSnackbar(message)
            viewModel.dismissError()
        }
    }

    toast?.let { message ->
        LaunchedEffect(message) {
            snackbar.showSnackbar(message)
            toast = null
        }
    }
}

@Composable
private fun ChooseKindStep(onDirect: () -> Unit, onGroup: () -> Unit) {
    Column {
        Text(
            text = "Con chi vuoi parlare?",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = "Il tipo viene scritto dentro il codice, quindi chi lo usa entra nella forma " +
                "giusta di conversazione senza dover scegliere nulla.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 22.dp),
        )
        KindCard(
            icon = Icons.Default.Person,
            title = "Chat diretta",
            subtitle = "Due dispositivi, una stanza. Il codice è la chiave.",
            onClick = onDirect,
        )
        Spacer(Modifier.height(12.dp))
        KindCard(
            icon = Icons.Default.Groups,
            title = "Gruppo",
            subtitle = "Più persone con lo stesso codice. Puoi aggiungerne altre quando vuoi.",
            onClick = onGroup,
        )
    }
}

@Composable
private fun KindCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.size(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChooseExpiryStep(
    kind: InviteKind,
    selected: Expiry.Preset,
    groupName: String,
    busy: Boolean,
    onPreset: (Expiry.Preset) -> Unit,
    onGroupName: (String) -> Unit,
    onConfirm: () -> Unit,
) {
    Column {
        Text(
            text = "Quanto deve durare il codice?",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = "La data è dentro il codice, quindi vale per tutti allo stesso modo. " +
                "Quando il codice si spegne, la stanza non è più raggiungibile da nessuno.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
        )

        if (kind == InviteKind.GROUP) {
            OutlinedTextField(
                value = groupName,
                onValueChange = onGroupName,
                label = { Text("Nome del gruppo (facoltativo)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
        }

        Expiry.Preset.entries.forEach { preset ->
            PresetRow(
                preset = preset,
                selected = preset == selected,
                onClick = { onPreset(preset) },
            )
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onConfirm,
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Genera il codice")
            }
        }
    }
}

@Composable
private fun PresetRow(preset: Expiry.Preset, selected: Boolean, onClick: () -> Unit) {
    val label = when (preset) {
        Expiry.Preset.ONE_DAY -> "24 ore"
        Expiry.Preset.THREE_DAYS -> "3 giorni"
        Expiry.Preset.ONE_WEEK -> "7 giorni"
        Expiry.Preset.ONE_MONTH -> "30 giorni"
        Expiry.Preset.THREE_MONTHS -> "90 giorni"
        Expiry.Preset.NEVER -> "Infinito"
    }
    val hint = when (preset) {
        Expiry.Preset.NEVER -> "Il codice non si spegne mai"
        else -> "Poi il codice smette di funzionare"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (preset == Expiry.Preset.NEVER) Icons.Default.AllInclusive else Icons.Default.Check,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.size(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RevealStep(
    code: String,
    expiryDay: Int?,
    clock: dev.shutchat.app.core.Clock,
    chatTitle: String,
    memberCount: Int,
    busy: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onRotate: () -> Unit,
    onOpen: () -> Unit,
    onSnack: (String) -> Unit,
) {
    Column {
        Text(
            text = chatTitle,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = "Passa questo codice a chi vuoi far entrare. È l'unica cosa che serve: " +
                "non ci sono account, non c'è un server che lo conserva.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
        )

        InviteCodeCard(
            code = code,
            expiryDay = expiryDay,
            clock = clock,
            title = "Codice della stanza",
            onCopy = {
                onCopy()
                onSnack("Codice copiato")
            },
            onShare = onShare,
        )

        Spacer(Modifier.height(16.dp))

        if (expiryDay == null) {
            InfoLine(
                icon = Icons.Default.AllInclusive,
                text = "Codice infinito: resta valido finché qualcuno non lo rigenera.",
            )
        } else {
            InfoLine(
                icon = Icons.Default.Refresh,
                text = "Alla scadenza potrai rigenerare un codice e la conversazione resta.",
            )
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onOpen,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text("Apri la chat")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = onRotate,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (busy) "Rigenero…" else "Rigenera un nuovo codice")
        }
    }
}

@Composable
private fun InfoLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
