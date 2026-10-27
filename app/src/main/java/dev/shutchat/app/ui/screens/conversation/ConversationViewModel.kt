package dev.shutchat.app.ui.screens.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.shutchat.app.core.Clock
import dev.shutchat.app.core.Ids
import dev.shutchat.app.data.repo.ChatRepository
import dev.shutchat.app.data.repo.MessageRepository
import dev.shutchat.app.data.repo.RetryPolicy
import dev.shutchat.app.di.AppContainer
import dev.shutchat.app.domain.model.Chat
import dev.shutchat.app.domain.model.ChatMember
import dev.shutchat.app.domain.model.ChatStatus
import dev.shutchat.app.domain.model.Message
import dev.shutchat.app.domain.model.MessageKind
import dev.shutchat.app.domain.model.MessageStatus
import dev.shutchat.app.media.MediaStore
import dev.shutchat.app.p2p.protocol.Receipt
import dev.shutchat.app.p2p.protocol.Typing
import dev.shutchat.app.p2p.protocol.WireAttachment
import dev.shutchat.app.p2p.protocol.WireMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private data class Content(
    val chat: Chat?,
    val messages: List<Message>,
    val members: List<ChatMember>,
)

private data class ComposeState(
    val typingNames: List<String>,
    val replyingTo: Message?,
    val editing: Message?,
)

/** Everything the conversation screen renders. */
data class ConversationUiState(
    val chat: Chat? = null,
    val messages: List<Message> = emptyList(),
    val members: List<ChatMember> = emptyList(),
    val typingNames: List<String> = emptyList(),
    val connectedPeers: Int = 0,
    val draft: String = "",
    val replyingTo: Message? = null,
    val editing: Message? = null,
    val error: String? = null,
)

class ConversationViewModel(
    private val container: AppContainer,
    private val chatId: String,
) : ViewModel() {

    private val messages: MessageRepository = container.messageRepository
    private val chats: ChatRepository = container.chatRepository

    private val typingNames = MutableStateFlow<List<String>>(emptyList())
    private val replyingTo = MutableStateFlow<Message?>(null)
    private val editing = MutableStateFlow<Message?>(null)
    private val error = MutableStateFlow<String?>(null)
    private val peers = MutableStateFlow(0)
    private val now = MutableStateFlow(container.clock.nowMillis())

    private var typingJob: Job? = null
    private var lastTypingSent = 0L

    /** Transcript and roster. Combined first so the wider combine stays readable. */
    private val content: kotlinx.coroutines.flow.Flow<Content> = combine(
        chats.observeChat(chatId),
        messages.observeMessages(chatId),
        chats.observeMembers(chatId),
    ) { chat, messages, members -> Content(chat, messages, members) }

    /** Compose state: what the user is replying to or editing. */
    private val composeState: kotlinx.coroutines.flow.Flow<ComposeState> = combine(
        typingNames,
        replyingTo,
        editing,
    ) { typing, replying, editing -> ComposeState(typing, replying, editing) }

    val uiState: StateFlow<ConversationUiState> = combine(
        content,
        composeState,
        error,
        peers,
    ) { content, compose, error, peers ->
        ConversationUiState(
            chat = content.chat,
            messages = content.messages,
            members = content.members,
            typingNames = compose.typingNames,
            replyingTo = compose.replyingTo,
            editing = compose.editing,
            connectedPeers = peers,
            error = error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationUiState())

    val clock: Clock get() = container.clock

    /** Exposed so the screen can describe picked files without holding the container. */
    val mediaStore: MediaStore get() = container.mediaStore

    init {
        viewModelScope.launch {
            chats.markRead(chatId)
            runCatching { messages.acknowledgeRead(chatId) }
        }
        viewModelScope.launch {
            while (true) {
                now.value = container.clock.nowMillis()
                delay(20_000)
            }
        }
        // Incoming typing and receipts for this chat.
        viewModelScope.launch {
            container.mesh.received.collect { received ->
                when (val envelope = received.envelope) {
                    is Typing -> if (envelope.chatId == chatId) handleTyping(envelope)
                    is Receipt -> if (envelope.roomId == roomIdOf()) {
                        // Receipts are applied by the inbound handler already; the
                        // flow above refreshes the list automatically.
                    }

                    else -> Unit
                }
            }
        }
        viewModelScope.launch {
            container.mesh.peerConnected.collect { peers.value = container.mesh.diagnostics.connectedPeers }
        }
    }

    /** Sends a text message, or applies an edit when one is in progress. */
    fun send(body: String) {
        val text = body.trim()
        if (text.isEmpty()) return
        val editingMessage = editing.value
        if (editingMessage != null) {
            viewModelScope.launch {
                messages.edit(editingMessage.id, text)
                editing.value = null
            }
            return
        }
        val reply = replyingTo.value
        viewModelScope.launch {
            sendMessage(
                kind = MessageKind.TEXT,
                body = text,
                replyTo = reply,
                attachment = null,
            )
            replyingTo.value = null
        }
    }

    /** Called on every keystroke; rate limited so the mesh is not flooded. */
    fun onTyping() {
        val timestamp = container.clock.nowMillis()
        if (timestamp - lastTypingSent < TYPING_THROTTLE_MILLIS) return
        lastTypingSent = timestamp
        typingJob?.cancel()
        typingJob = viewModelScope.launch {
            val room = roomIdOf() ?: return@launch
            val nodeId = container.settings.nodeId()
            val displayName = container.settings.currentDisplayName()
            container.broadcastEnvelope(
                Typing(
                    roomId = room,
                    chatId = chatId,
                    nodeId = nodeId,
                    displayName = displayName,
                    typing = true,
                ),
            )
            delay(TYPING_RESET_MILLIS)
            container.broadcastEnvelope(
                Typing(
                    roomId = room,
                    chatId = chatId,
                    nodeId = nodeId,
                    displayName = displayName,
                    typing = false,
                ),
            )
        }
    }

    fun replyTo(message: Message) {
        replyingTo.value = message
        editing.value = null
    }

    fun edit(message: Message) {
        if (!message.isOutgoing) return
        editing.value = message
        replyingTo.value = null
    }

    fun cancelComposeMode() {
        replyingTo.value = null
        editing.value = null
    }

    fun react(message: Message, emoji: String) {
        viewModelScope.launch {
            val nodeId = container.settings.nodeId()
            val mine = message.reactions.firstOrNull { it.nodeId == nodeId }
            val removed = mine?.emoji == emoji
            messages.react(message.id, emoji, removed)
        }
    }

    fun delete(message: Message) {
        viewModelScope.launch { messages.delete(message.id) }
    }

    fun saveDraft(draft: String) {
        viewModelScope.launch { chats.saveDraft(chatId, draft) }
    }

    suspend fun draft(): String = chats.draft(chatId)

    fun dismissError() {
        error.value = null
    }

    /** Regenerates an expired code so the room becomes reachable again. */
    fun rotateCode(preset: dev.shutchat.app.domain.expiry.Expiry.Preset) {
        viewModelScope.launch {
            val expiryDay = dev.shutchat.app.domain.expiry.Expiry.presetExpiryDay(preset, container.clock)
            container.chatFactory.rotateCode(chatId, expiryDay)
            container.refreshRooms()
        }
    }

    /**
     * Stages and sends an attachment.
     *
     * The message row is written first so the bubble appears immediately with a
     * progress bar; the transfer then fills in the file and the progress.
     */
    fun sendAttachment(picked: MediaStore.PickedFile, kind: MessageKind) {
        viewModelScope.launch {
            val room = roomIdOf() ?: return@launch
            val messageId = Ids.newId()
            val attachmentId = Ids.newId()
            val staged = container.mediaStore.stageForSending(attachmentId, picked)
            if (staged == null) {
                error.value = "Impossibile leggere il file scelto"
                return@launch
            }
            val outgoing = Message(
                id = messageId,
                chatId = chatId,
                senderNodeId = container.settings.nodeId(),
                senderName = container.settings.currentDisplayName(),
                kind = kind,
                body = "",
                createdAt = container.clock.nowMillis(),
                status = MessageStatus.SENDING,
                attachment = dev.shutchat.app.domain.model.Attachment(
                    id = attachmentId,
                    messageId = messageId,
                    fileName = picked.fileName,
                    mimeType = picked.mimeType,
                    sizeBytes = picked.sizeBytes,
                    localPath = staged.absolutePath,
                    progress = 1f,
                ),
                outgoing = true,
            )
            messages.storeOutgoing(outgoing)
            messages.storeAttachment(outgoing.attachment!!)

            val envelope = WireMessage(
                roomId = room,
                messageId = messageId,
                chatId = chatId,
                senderNodeId = outgoing.senderNodeId,
                senderName = outgoing.senderName,
                kind = kind.name,
                body = "",
                createdAt = outgoing.createdAt,
                attachment = WireAttachment(
                    id = attachmentId,
                    fileName = picked.fileName,
                    mimeType = picked.mimeType,
                    sizeBytes = picked.sizeBytes,
                ),
            )
            val handedTo = container.broadcastEnvelope(envelope)
            messages.setStatus(listOf(messageId), RetryPolicy.afterHandoff(handedTo))
            if (handedTo == 0) {
                error.value = "Nessun peer raggiungibile: il file resta in attesa"
                return@launch
            }
            val sent = container.transfer.send(
                roomIdHex = room,
                transferId = Ids.randomBytes(16),
                attachmentId = attachmentId,
                messageId = messageId,
                file = staged,
                fileName = picked.fileName,
                mimeType = picked.mimeType,
            )
            if (!sent) {
                messages.setStatus(listOf(messageId), MessageStatus.QUEUED)
                error.value = "Trasferimento interrotto: riprenderà quando un peer torna online"
            }
        }
    }

    private suspend fun sendMessage(
        kind: MessageKind,
        body: String,
        replyTo: Message?,
        attachment: dev.shutchat.app.domain.model.Attachment?,
    ) {
        val room = roomIdOf() ?: return
        val nodeId = container.settings.nodeId()
        val displayName = container.settings.currentDisplayName()
        val message = Message(
            id = Ids.newId(),
            chatId = chatId,
            senderNodeId = nodeId,
            senderName = displayName,
            kind = kind,
            body = body,
            createdAt = container.clock.nowMillis(),
            status = MessageStatus.SENDING,
            replyToId = replyTo?.id,
            replyToPreview = replyTo?.let { previewOf(it) },
            attachment = attachment,
            outgoing = true,
        )
        messages.storeOutgoing(message)
        attachment?.let { messages.storeAttachment(it) }

        val envelope = WireMessage(
            roomId = room,
            messageId = message.id,
            chatId = chatId,
            senderNodeId = nodeId,
            senderName = displayName,
            kind = kind.name,
            body = body,
            createdAt = message.createdAt,
            replyToId = message.replyToId,
            replyToPreview = message.replyToPreview,
        )
        val handedTo = container.broadcastEnvelope(envelope)
        // Not DELIVERED: the frame was only handed to a transport. The recipient's
        // receipt promotes it, and the outbox re-sends it until that arrives.
        messages.setStatus(listOf(message.id), RetryPolicy.afterHandoff(handedTo))
        if (handedTo == 0) {
            error.value = "Nessun peer raggiungibile ora: il messaggio parte appena qualcuno si collega"
        }
        chats.touch(chatId, message.createdAt)
    }

    private suspend fun handleTyping(envelope: Typing) {
        if (envelope.nodeId == container.settings.nodeId()) return
        val current = typingNames.value.toMutableList()
        if (envelope.typing) {
            if (!current.contains(envelope.displayName)) current.add(envelope.displayName)
        } else {
            current.remove(envelope.displayName)
        }
        typingNames.value = current
    }

    private suspend fun roomIdOf(): String? = chats.chatEntity(chatId)?.roomId

    private fun previewOf(message: Message): String = when (message.kind) {
        MessageKind.IMAGE -> "📷 Foto"
        MessageKind.AUDIO -> "🎤 Messaggio vocale"
        MessageKind.FILE -> "📎 " + (message.attachment?.fileName ?: "File")
        else -> message.body.take(120)
    }

    private companion object {
        const val TYPING_THROTTLE_MILLIS = 2_000L
        const val TYPING_RESET_MILLIS = 4_000L
    }
}
