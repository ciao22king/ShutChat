package dev.shutchat.app.ui.screens.join

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.shutchat.app.ui.containerViewModel
import dev.shutchat.app.ui.theme.CodeStyle
import dev.shutchat.app.ui.theme.LocalChatColors

/**
 * Joining with a code.
 *
 * Validation happens as the user types, because there is no server to answer
 * "does this room exist?" — the code either parses and is still alive or it is
 * not, and both facts are knowable locally.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinScreen(
    onBack: () -> Unit,
    onScan: () -> Unit,
    onOpenChat: (String) -> Unit,
) {
    val viewModel = containerViewModel { JoinViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var scanned by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.joinedChatId) {
        val chatId = state.joinedChatId ?: return@LaunchedEffect
        viewModel.consumeJoined()
        onOpenChat(chatId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Entra con un codice") },
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
            Text(
                text = "Incolla il codice che ti hanno dato. Sono 24 caratteri in gruppi di " +
                    "quattro: puoi scriverlo anche con gli spazi o in minuscolo.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 18.dp),
            )

            OutlinedTextField(
                value = state.input,
                onValueChange = viewModel::onInputChanged,
                label = { Text("Codice") },
                placeholder = { Text("XXXX-XXXX-XXXX-XXXX-XXXX-XXXX") },
                singleLine = true,
                textStyle = CodeStyle.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                ),
                trailingIcon = {
                    val clipboardText = clipboard.getText()?.text
                    if (!clipboardText.isNullOrBlank()) {
                        IconButton(onClick = { viewModel.onInputChanged(clipboardText) }) {
                            Icon(
                                Icons.Default.QrCodeScanner,
                                contentDescription = "Incolla dagli appunti",
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))
            ValidationLine(validation = state.validation)

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = viewModel::join,
                enabled = state.validation is JoinViewModel.Validation.Valid && !state.joining,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                if (state.joining) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Entra nella stanza")
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .clickable(onClick = onScan)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.QrCodeScanner,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(14.dp))
                Column {
                    Text("Inquadra il QR", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Più veloce che scrivere 24 caratteri",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            state.error?.let { message ->
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(LocalChatColors.current.expired.copy(alpha = 0.14f))
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = LocalChatColors.current.expired,
                    )
                    Spacer(Modifier.size(12.dp))
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun ValidationLine(validation: JoinViewModel.Validation) {
    val chatColors = LocalChatColors.current
    when (validation) {
        is JoinViewModel.Validation.Empty -> Spacer(Modifier.height(20.dp))

        is JoinViewModel.Validation.Valid -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = chatColors.online,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = buildString {
                    append(if (validation.kind == dev.shutchat.app.domain.model.InviteKind.GROUP) "Gruppo" else "Chat diretta")
                    append(" · codice attivo fino al ")
                    append(validation.deadlineLabel)
                },
                style = MaterialTheme.typography.bodySmall,
                color = chatColors.online,
            )
        }

        is JoinViewModel.Validation.Invalid -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = chatColors.expired,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = validation.message,
                style = MaterialTheme.typography.bodySmall,
                color = chatColors.expired,
            )
        }
    }
}
