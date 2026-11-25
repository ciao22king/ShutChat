package dev.shutchat.desktop

import dev.shutchat.app.core.Clock
import dev.shutchat.app.core.Ids
import dev.shutchat.app.core.Log
import dev.shutchat.app.domain.codec.InviteCodec
import dev.shutchat.app.domain.expiry.Expiry
import dev.shutchat.app.domain.model.Attachment
import dev.shutchat.app.domain.model.Chat
import dev.shutchat.app.domain.model.ChatKind
import dev.shutchat.app.domain.model.ChatMember
import dev.shutchat.app.domain.model.ChatStatus
import dev.shutchat.app.domain.model.InviteKind
import dev.shutchat.app.domain.model.Message
import dev.shutchat.app.domain.model.MessageKind
import dev.shutchat.app.domain.model.MessageStatus
import dev.shutchat.app.domain.model.Reaction
import dev.shutchat.app.p2p.MeshEngine
import dev.shutchat.app.p2p.PeerInfo
import dev.shutchat.app.p2p.ReceivedEnvelope
import dev.shutchat.app.p2p.RoomSpec
import dev.shutchat.app.p2p.call.CallController
import dev.shutchat.app.p2p.lan.LanDiscovery
import dev.shutchat.app.p2p.lan.LanServer
import dev.shutchat.app.p2p.protocol.CallMedia
import dev.shutchat.app.p2p.protocol.DeleteMessage
import dev.shutchat.app.p2p.protocol.EditMessage
import dev.shutchat.app.p2p.protocol.JoinNotice
import dev.shutchat.app.p2p.protocol.LeaveNotice
import dev.shutchat.app.p2p.protocol.MemberRoster
import dev.shutchat.app.p2p.protocol.ReactionUpdate
import dev.shutchat.app.p2p.protocol.Receipt
import dev.shutchat.app.p2p.protocol.SyncRequest
import dev.shutchat.app.p2p.protocol.SyncResult
import dev.shutchat.app.p2p.protocol.Typing
import dev.shutchat.app.p2p.protocol.WireAttachment
import dev.shutchat.app.p2p.protocol.WireMessage
import dev.shutchat.app.p2p.protocol.isCallSignal
import dev.shutchat.app.p2p.relay.RelayTransport
import dev.shutchat.app.update.HttpReleaseSource
import dev.shutchat.app.update.UpdateChecker
import dev.shutchat.app.update.UpdateResult
import dev.shutchat.desktop.call.DesktopCallMedia
import dev.shutchat.desktop.call.DesktopCallMediaFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The desktop client's composition root.
 *
 * Mirrors `AppContainer` on Android. Everything the two clients share (protocol,
 * crypto, mesh, transports, call state machine, transfer framing) comes from
 * `:shared`; what lives here is only what a JVM needs instead of Android: a JSON
 * store instead of Room, files instead of the MediaStore, `javax.sound` instead
 * of MediaRecorder, the system tray instead of NotificationManager.
 */
class DesktopApp(
    private val settings: DesktopSettings = DesktopSettings(),
    private val store: DesktopStore = DesktopStore(),
    private val files: DesktopFiles = DesktopFiles(),
) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val clock: Clock = Clock.System

    val nodeId: String = settings.nodeId
    val displayName = MutableStateFlow(settings.displayName.ifBlank { "Desktop" })

    val relay = RelayTransport(scope, clientId = "shutchat-$nodeId")
    val lanServer = LanServer()
    val lanDiscovery = LanDiscovery()

    val mesh = MeshEngine(
        scope = scope,
        proximity = null,
        lanServer = lanServer,
        lanDiscovery = lanDiscovery,
        relay = relay,
        appVersion = APP_VERSION,
    )

    val calls = CallController(
        scope = scope,
        selfNodeId = { nodeId },
        selfName = { displayName.value },
        clock = clock,
        send = { envelope -> mesh.broadcast(envelope) },
        mediaFactory = DesktopCallMediaFactory(),
    )

    val transfer = DesktopTransfer(scope, mesh, files)
    val recorder = DesktopVoiceRecorder(files)
    val player = DesktopVoicePlayer()
    val notifier = DesktopNotifier()

    private val initial = store.load()

    val chats = MutableStateFlow(initial.chats)
    val messages = MutableStateFlow(initial.messages)
    val members = MutableStateFlow(initial.members)
    val drafts = MutableStateFlow(initial.drafts)
    val typing = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val transferProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val peerList = MutableStateFlow<List<PeerInfo>>(emptyList())

    val connectedPeers = MutableStateFlow(0)
    val relayActive = MutableStateFlow(false)
    val relayLabel = MutableStateFlow("")
    val lanActive = MutableStateFlow(false)

    val publicRelay = MutableStateFlow(settings.publicRelay)
    val lanEnabled = MutableStateFlow(settings.lanDiscovery)
    val theme = MutableStateFlow(settings.theme)
    val readReceipts = MutableStateFlow(settings.readReceipts)
    val typingIndicator = MutableStateFlow(settings.typingIndicator)
    val notifications = MutableStateFlow(settings.notifications)
    val forwarding = MutableStateFlow(settings.forwarding)
    val retentionDays = MutableStateFlow(settings.retentionDays)

    /** Chat on screen: no unread count, no notification, receipts are sent. */
    var openChatId: String? = null
        set(value) {
            field = value
            value?.let(::markRead)
        }

    private val typingDeadlines = mutableMapOf<Pair<String, String>, Long>()
    private val typingNames = mutableMapOf<String, String>()
    private val persistMutex = Mutex()
    private var started = false

    fun start() {
        if (started) return
        started = true
        Log.i(TAG, "desktop starting, node $nodeId")

        mesh.setIdentity(nodeId, displayName.value)
        mesh.forwardingEnabled = forwarding.value
        mesh.setPublicRelay(publicRelay.value)
        refreshRooms()
        mesh.start(lanEnabled.value, enableNearby = false)
        purgeOldMessages()

        scope.launch { mesh.received.collect { handleIncoming(it) } }
        scope.launch { mesh.chunkReceived.collect { transfer.onChunk(it) } }
        scope.launch {
            mesh.peerConnected.collect {
                connectedPeers.value = mesh.connectedNodeIds().size
                peerList.value = mesh.peerInfos()
                touchMember(it, online = true)
            }
        }
        scope.launch {
            mesh.peerLost.collect { node ->
                connectedPeers.value = mesh.connectedNodeIds().size
                peerList.value = mesh.peerInfos()
                markMemberOffline(node)
            }
        }
        scope.launch { transfer.progress.collect { applyTransferProgress(it) } }
        scope.launch {
            while (isActive) {
                relayActive.value = relay.connected
                relayLabel.value = relay.activeEndpoint?.label.orEmpty()
                lanActive.value = lanServer.port != 0
                delay(2_000)
            }
        }
        scope.launch {
            while (isActive) {
                delay(RETRY_INTERVAL_MILLIS)
                resendPending()
            }
        }
        scope.launch {
            while (isActive) {
                delay(1_000)
                pruneTyping()
            }
        }
    }

    fun stop() {
        runCatching { mesh.stop() }
        runCatching { calls.shutdown() }
        recorder.cancel()
        player.stop()
        notifier.dispose()
        persist()
        scope.cancel()
    }

    // ------------------------------------------------------------------ chats

    fun createChat(kind: InviteKind, expiryDay: Int?, title: String? = null): Chat {
        val code = InviteCodec.create(kind, expiryDay)
        val now = clock.nowMillis()
        val group = kind == InviteKind.GROUP
        val chat = Chat(
            id = code.roomId,
            kind = if (group) ChatKind.GROUP else ChatKind.DIRECT,
            title = title?.trim()?.takeIf { it.isNotEmpty() } ?: if (group) "Group" else "Chat",
            status = ChatStatus.ACTIVE,
            inviteCode = code.text,
            inviteExpiryDay = code.payload.expiryDay,
            createdAt = now,
            lastActivityAt = now,
        )
        chats.value = chats.value + chat
        addSelfMember(chat)
        refreshRooms()
        persist()
        announceJoin(chat)
        return chat
    }

    /** @return the joined chat, or null when the code is not valid. */
    fun joinChat(codeText: String): Chat? {
        val code = runCatching { InviteCodec.parse(codeText) }.getOrNull() ?: return null
        chats.value.firstOrNull { it.id == code.roomId }?.let { return it }
        val now = clock.nowMillis()
        val chat = Chat(
            id = code.roomId,
            kind = if (code.kind == InviteKind.GROUP) ChatKind.GROUP else ChatKind.DIRECT,
            title = "Chat " + code.roomId.take(4).uppercase(),
            status = if (Expiry.isExpired(code.payload.expiryDay, clock)) ChatStatus.EXPIRED else ChatStatus.ACTIVE,
            inviteCode = code.text,
            inviteExpiryDay = code.payload.expiryDay,
            createdAt = now,
            lastActivityAt = now,
        )
        chats.value = chats.value + chat
        addSelfMember(chat)
        refreshRooms()
        persist()
        announceJoin(chat)
        scope.launch { runCatching { mesh.broadcast(SyncRequest(chat.id, chat.id, nodeId, 0L)) } }
        return chat
    }

    fun deleteChat(chatId: String) {
        chats.value.firstOrNull { it.id == chatId }?.let { chat ->
            scope.launch { runCatching { mesh.broadcast(LeaveNotice(chat.id, chat.id, nodeId)) } }
        }
        chats.value = chats.value.filterNot { it.id == chatId }
        messages.value = messages.value - chatId
        members.value = members.value - chatId
        drafts.value = drafts.value - chatId
        if (openChatId == chatId) openChatId = null
        refreshRooms()
        persist()
    }

    fun renameChat(chatId: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        chats.value = chats.value.map { if (it.id == chatId) it.copy(title = trimmed) else it }
        persist()
    }

    fun setPinned(chatId: String, pinned: Boolean) = updateChat(chatId) { it.copy(pinned = pinned) }

    fun setMuted(chatId: String, muted: Boolean) = updateChat(chatId) { it.copy(muted = muted) }

    fun setArchived(chatId: String, archived: Boolean) = updateChat(chatId) {
        it.copy(status = if (archived) ChatStatus.ARCHIVED else ChatStatus.ACTIVE)
    }

    fun setDraft(chatId: String, text: String) {
        drafts.value = if (text.isBlank()) drafts.value - chatId else drafts.value + (chatId to text)
    }

    /** Regenerates the invite code; the old room stops opening anything. */
    fun rotateCode(chatId: String, preset: Expiry.Preset) {
        val chat = chats.value.firstOrNull { it.id == chatId } ?: return
        val kind = if (chat.kind == ChatKind.GROUP) InviteKind.GROUP else InviteKind.DIRECT
        val code = InviteCodec.create(kind, Expiry.presetExpiryDay(preset, clock))
        val replacement = chat.copy(
            id = code.roomId,
            inviteCode = code.text,
            inviteExpiryDay = code.payload.expiryDay,
            status = ChatStatus.ACTIVE,
            lastActivityAt = clock.nowMillis(),
        )
        chats.value = chats.value.map { if (it.id == chatId) replacement else it }
        messages.value = messages.value - chatId
        members.value = members.value - chatId
        addSelfMember(replacement)
        refreshRooms()
        persist()
    }

    fun clearAllMessages() {
        messages.value = emptyMap()
        chats.value = chats.value.map {
            it.copy(unreadCount = 0, lastMessagePreview = null, lastMessageSender = null)
        }
        persist()
    }

    private fun updateChat(chatId: String, change: (Chat) -> Chat) {
        chats.value = chats.value.map { if (it.id == chatId) change(it) else it }
        persist()
    }

    // ------------------------------------------------------------------ groups

    fun membersOf(chatId: String): List<ChatMember> = members.value[chatId].orEmpty()

    /** Title shown for a chat: the group name, or the peer's name in a direct chat. */
    fun titleOf(chat: Chat): String {
        if (chat.kind == ChatKind.GROUP) return chat.title
        val other = membersOf(chat.id).firstOrNull { !it.isSelf }
        return other?.displayName?.takeIf { it.isNotBlank() } ?: chat.title
    }

    fun removeMember(chatId: String, node: String) {
        members.value = members.value.mapValues { (id, list) ->
            if (id == chatId) list.filterNot { it.nodeId == node } else list
        }
        persist()
    }

    fun leaveGroup(chatId: String) {
        scope.launch { runCatching { mesh.broadcast(LeaveNotice(chatId, chatId, nodeId)) } }
        deleteChat(chatId)
    }

    // ------------------------------------------------------------------ settings

    fun setDisplayName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        displayName.value = trimmed
        settings.update(displayName = trimmed)
        mesh.setIdentity(nodeId, trimmed)
        refreshRooms()
    }

    fun setPublicRelay(enabled: Boolean) {
        publicRelay.value = enabled
        settings.update(publicRelay = enabled)
        mesh.setPublicRelay(enabled)
    }

    fun setLanEnabled(enabled: Boolean) {
        lanEnabled.value = enabled
        settings.update(lanDiscovery = enabled)
        runCatching { mesh.stop() }
        refreshRooms()
        mesh.start(enabled, enableNearby = false)
    }

    fun setForwarding(enabled: Boolean) {
        forwarding.value = enabled
        settings.update(forwarding = enabled)
        mesh.forwardingEnabled = enabled
    }

    fun setTheme(value: String) {
        theme.value = value
        settings.update(theme = value)
    }

    fun setReadReceipts(enabled: Boolean) {
        readReceipts.value = enabled
        settings.update(readReceipts = enabled)
    }

    fun setTypingIndicator(enabled: Boolean) {
        typingIndicator.value = enabled
        settings.update(typingIndicator = enabled)
    }

    fun setNotifications(enabled: Boolean) {
        notifications.value = enabled
        settings.update(notifications = enabled)
    }

    fun setRetentionDays(days: Int) {
        retentionDays.value = days
        settings.update(retentionDays = days)
        purgeOldMessages()
    }

    suspend fun checkUpdates(): UpdateResult = UpdateChecker(HttpReleaseSource).check(APP_VERSION)

    // --------------------------------------------------------------- messages

    fun send(chatId: String, text: String, replyTo: Message? = null) {
        val body = text.trim()
        if (body.isEmpty()) return
        val chat = chats.value.firstOrNull { it.id == chatId } ?: return
        if (chat.status == ChatStatus.EXPIRED) return

        val message = Message(
            id = Ids.newId(),
            chatId = chatId,
            senderNodeId = nodeId,
            senderName = displayName.value,
            kind = MessageKind.TEXT,
            body = body,
            createdAt = clock.nowMillis(),
            status = MessageStatus.SENDING,
            outgoing = true,
            replyToId = replyTo?.id,
            replyToPreview = replyTo?.let(::previewOf),
        )
        appendMessage(message)
        setDraft(chatId, "")
        scope.launch {
            val handed = mesh.broadcast(message.toWire(chatId))
            setStatus(message.id, if (handed > 0) MessageStatus.SENDING else MessageStatus.QUEUED)
        }
    }

    fun editMessage(message: Message, newBody: String) {
        val body = newBody.trim()
        if (body.isEmpty() || !message.outgoing || message.deleted) return
        val editedAt = clock.nowMillis()
        updateMessage(message.id) { it.copy(body = body, editedAt = editedAt) }
        scope.launch {
            runCatching { mesh.broadcast(EditMessage(message.chatId, message.id, nodeId, body, editedAt)) }
        }
    }

    fun deleteMessage(message: Message) {
        if (!message.outgoing) return
        updateMessage(message.id) { it.copy(deleted = true, body = "") }
        scope.launch {
            runCatching { mesh.broadcast(DeleteMessage(message.chatId, message.id, nodeId)) }
        }
    }

    fun toggleReaction(message: Message, emoji: String) {
        val mine = message.reactions.any { it.nodeId == nodeId && it.emoji == emoji }
        updateMessage(message.id) { current ->
            val reactions = if (mine) {
                current.reactions.filterNot { it.nodeId == nodeId && it.emoji == emoji }
            } else {
                current.reactions.filterNot { it.nodeId == nodeId } + Reaction(current.id, nodeId, emoji)
            }
            current.copy(reactions = reactions)
        }
        scope.launch {
            runCatching {
                mesh.broadcast(ReactionUpdate(message.chatId, message.id, nodeId, emoji, removed = mine))
            }
        }
    }

    fun onTyping(chatId: String) {
        if (!typingIndicator.value) return
        scope.launch {
            runCatching {
                mesh.broadcast(Typing(chatId, chatId, nodeId, displayName.value, typing = true))
            }
        }
    }

    fun markRead(chatId: String) {
        if (chats.value.none { it.id == chatId }) return
        val unread = messages.value[chatId].orEmpty().filter { !it.outgoing && it.status != MessageStatus.READ }
        updateChat(chatId) { it.copy(unreadCount = 0) }
        if (unread.isEmpty()) return
        updateMessages(chatId) { list ->
            list.map { if (!it.outgoing) it.copy(status = MessageStatus.READ) else it }
        }
        if (!readReceipts.value) return
        scope.launch {
            runCatching {
                mesh.broadcast(Receipt(chatId, unread.map { it.id }, state = "read", nodeId = nodeId))
            }
        }
    }

    fun sendAttachment(chatId: String, source: File, kind: MessageKind) {
        val chat = chats.value.firstOrNull { it.id == chatId } ?: return
        if (chat.status == ChatStatus.EXPIRED || !source.isFile) return
        val copy = runCatching {
            val destination = files.outgoing(source.name)
            source.copyTo(destination, overwrite = true)
            destination
        }.getOrNull() ?: return

        val messageId = Ids.newId()
        val attachment = Attachment(
            id = Ids.newId(),
            messageId = messageId,
            fileName = source.name,
            mimeType = mimeOf(source),
            sizeBytes = copy.length(),
            localPath = copy.absolutePath,
            progress = 0f,
        )
        val message = Message(
            id = messageId,
            chatId = chatId,
            senderNodeId = nodeId,
            senderName = displayName.value,
            kind = kind,
            body = source.name,
            createdAt = clock.nowMillis(),
            status = MessageStatus.SENDING,
            outgoing = true,
            attachment = attachment,
        )
        appendMessage(message)
        deliverAttachment(message)
    }

    fun sendVoiceNote(chatId: String, recording: DesktopRecording) {
        val chat = chats.value.firstOrNull { it.id == chatId } ?: return
        if (chat.status == ChatStatus.EXPIRED) return
        val messageId = Ids.newId()
        val attachment = Attachment(
            id = Ids.newId(),
            messageId = messageId,
            fileName = recording.file.name,
            mimeType = "audio/wav",
            sizeBytes = recording.file.length(),
            localPath = recording.file.absolutePath,
            progress = 0f,
            durationMillis = recording.durationMillis,
        )
        val message = Message(
            id = messageId,
            chatId = chatId,
            senderNodeId = nodeId,
            senderName = displayName.value,
            kind = MessageKind.AUDIO,
            body = "Voice message",
            createdAt = clock.nowMillis(),
            status = MessageStatus.SENDING,
            outgoing = true,
            attachment = attachment,
        )
        appendMessage(message)
        deliverAttachment(message)
    }

    private fun deliverAttachment(message: Message) {
        val attachment = message.attachment ?: return
        val file = attachment.localPath?.let(::File) ?: return
        scope.launch {
            val ok = runCatching {
                transfer.send(
                    roomIdHex = message.chatId,
                    transferId = Ids.randomBytes(16),
                    attachmentId = attachment.id,
                    messageId = message.id,
                    file = file,
                    fileName = attachment.fileName,
                    mimeType = attachment.mimeType,
                ) { fraction -> setAttachmentProgress(attachment.id, fraction) }
            }.getOrDefault(false)
            if (ok) {
                setAttachmentProgress(attachment.id, 1f)
                setStatus(message.id, MessageStatus.SENDING)
            } else {
                setStatus(message.id, MessageStatus.QUEUED)
            }
        }
    }

    /** Re-sends anything the mesh could not hand to a peer yet. */
    private suspend fun resendPending() {
        val cutoff = clock.nowMillis() - MAX_RETRY_AGE_MILLIS
        messages.value.forEach { (chatId, list) ->
            list.filter { it.outgoing && it.status.isPending && it.createdAt > cutoff }
                .forEach { message ->
                    val attachment = message.attachment
                    if (attachment != null && attachment.progress < 1f) {
                        deliverAttachment(message)
                    } else {
                        val handed = mesh.broadcast(message.toWire(chatId))
                        if (handed > 0) setStatus(message.id, MessageStatus.SENDING)
                    }
                }
        }
    }

    private val MessageStatus.isPending: Boolean
        get() = this == MessageStatus.QUEUED || this == MessageStatus.SENDING || this == MessageStatus.PENDING

    private fun Message.toWire(chatId: String) = WireMessage(
        roomId = chatId,
        messageId = id,
        chatId = chatId,
        senderNodeId = senderNodeId,
        senderName = senderName,
        kind = kind.name,
        body = body,
        createdAt = createdAt,
        replyToId = replyToId,
        replyToPreview = replyToPreview,
        attachment = attachment?.let {
            WireAttachment(
                id = it.id,
                fileName = it.fileName,
                mimeType = it.mimeType,
                sizeBytes = it.sizeBytes,
                durationMillis = it.durationMillis,
                width = it.width,
                height = it.height,
            )
        },
    )

    private suspend fun handleIncoming(received: ReceivedEnvelope) {
        val envelope = received.envelope
        if (envelope.isCallSignal()) {
            calls.onEnvelope(envelope, received.fromNodeId)
            return
        }
        when (envelope) {
            is WireMessage -> onMessage(envelope)
            is Receipt -> envelope.messageIds.forEach {
                setStatus(it, if (envelope.state == "read") MessageStatus.READ else MessageStatus.DELIVERED)
            }

            is Typing -> onTypingEnvelope(envelope)
            is ReactionUpdate -> onReaction(envelope)
            is EditMessage -> updateMessage(envelope.messageId) {
                it.copy(body = envelope.newBody, editedAt = envelope.editedAt)
            }

            is DeleteMessage -> updateMessage(envelope.messageId) { it.copy(deleted = true, body = "") }
            is MemberRoster -> mergeRoster(envelope)
            is JoinNotice -> onJoin(envelope)
            is LeaveNotice -> onLeave(envelope)
            is SyncRequest -> onSyncRequest(envelope)
            is SyncResult -> envelope.messages.forEach { onMessage(it) }
            else -> transfer.onEnvelope(envelope)
        }
    }

    private fun onMessage(envelope: WireMessage) {
        val chatId = envelope.roomId
        if (chats.value.none { it.id == chatId }) return
        val existing = messages.value[chatId].orEmpty().any { it.id == envelope.messageId }
        if (!existing) {
            val localPath = envelope.attachment?.let { transfer.pathFor(it.id) }
            val attachment = envelope.attachment?.let { wire ->
                Attachment(
                    id = wire.id,
                    messageId = envelope.messageId,
                    fileName = wire.fileName,
                    mimeType = wire.mimeType,
                    sizeBytes = wire.sizeBytes,
                    localPath = localPath,
                    progress = if (localPath != null) 1f else 0f,
                    durationMillis = wire.durationMillis,
                    width = wire.width,
                    height = wire.height,
                )
            }
            appendMessage(
                Message(
                    id = envelope.messageId,
                    chatId = chatId,
                    senderNodeId = envelope.senderNodeId,
                    senderName = envelope.senderName,
                    kind = MessageKind.fromName(envelope.kind),
                    body = envelope.body,
                    createdAt = envelope.createdAt,
                    status = MessageStatus.DELIVERED,
                    outgoing = false,
                    hops = envelope.hops,
                    replyToId = envelope.replyToId,
                    replyToPreview = envelope.replyToPreview,
                    attachment = attachment,
                ),
                notify = true,
            )
            touchMemberByName(envelope.senderNodeId, envelope.senderName)
        }
        // Acknowledge every delivery, including a duplicate: a lost receipt is
        // why the sender keeps retrying.
        scope.launch {
            runCatching {
                mesh.broadcast(Receipt(chatId, listOf(envelope.messageId), state = "delivered", nodeId = nodeId))
            }
        }
    }

    private fun onReaction(envelope: ReactionUpdate) {
        updateMessage(envelope.messageId) { message ->
            val others = message.reactions.filterNot { it.nodeId == envelope.nodeId }
            message.copy(
                reactions = if (envelope.removed) {
                    others
                } else {
                    others + Reaction(message.id, envelope.nodeId, envelope.emoji)
                },
            )
        }
    }

    private fun onTypingEnvelope(envelope: Typing) {
        if (envelope.nodeId == nodeId) return
        val key = envelope.chatId to envelope.nodeId
        typingNames[envelope.nodeId] = envelope.displayName
        if (envelope.typing) {
            typingDeadlines[key] = clock.nowMillis() + TYPING_TTL_MILLIS
        } else {
            typingDeadlines.remove(key)
        }
        publishTyping(envelope.chatId)
    }

    private fun publishTyping(chatId: String) {
        val now = clock.nowMillis()
        val names = typingDeadlines.entries
            .filter { (key, deadline) -> key.first == chatId && deadline > now }
            .mapNotNull { (key, _) -> typingNames[key.second] }
            .distinct()
        typing.value = if (names.isEmpty()) typing.value - chatId else typing.value + (chatId to names)
    }

    private fun pruneTyping() {
        val now = clock.nowMillis()
        val expired = typingDeadlines.filterValues { it <= now }.keys
        if (expired.isEmpty()) return
        expired.forEach(typingDeadlines::remove)
        expired.map { it.first }.distinct().forEach(::publishTyping)
    }

    private fun onJoin(envelope: JoinNotice) {
        touchMemberByName(envelope.nodeId, envelope.displayName)
        systemMessage(envelope.chatId, "${envelope.displayName} joined")
    }

    private fun onLeave(envelope: LeaveNotice) {
        val name = membersOf(envelope.chatId).firstOrNull { it.nodeId == envelope.nodeId }?.displayName
        removeMember(envelope.chatId, envelope.nodeId)
        systemMessage(envelope.chatId, "${name ?: "Someone"} left")
    }

    private fun mergeRoster(envelope: MemberRoster) {
        val merged = membersOf(envelope.chatId).toMutableList()
        envelope.members.forEach { wire ->
            if (merged.none { it.nodeId == wire.nodeId }) {
                merged += ChatMember(
                    chatId = envelope.chatId,
                    nodeId = wire.nodeId,
                    displayName = wire.displayName,
                    avatarSeed = wire.nodeId,
                    isSelf = wire.nodeId == nodeId,
                    isOwner = wire.isOwner,
                    joinedAt = wire.joinedAt,
                    lastSeenAt = clock.nowMillis(),
                    online = mesh.connectedNodeIds().contains(wire.nodeId),
                )
            }
        }
        members.value = members.value + (envelope.chatId to merged)
        persist()
    }

    private fun onSyncRequest(envelope: SyncRequest) {
        val pending = messages.value[envelope.chatId].orEmpty()
            .filter { it.createdAt > envelope.sinceMillis && !it.outgoing }
            .takeLast(MAX_SYNC_MESSAGES)
            .map { it.toWire(envelope.chatId) }
        if (pending.isEmpty()) return
        scope.launch {
            runCatching { mesh.broadcast(SyncResult(envelope.chatId, envelope.chatId, pending)) }
        }
    }

    private fun announceJoin(chat: Chat) {
        scope.launch {
            runCatching { mesh.broadcast(JoinNotice(chat.id, chat.id, nodeId, displayName.value, nodeId)) }
        }
    }

    // ---------------------------------------------------------------- internal

    private fun appendMessage(message: Message, notify: Boolean = false) {
        val current = messages.value
        val list = current[message.chatId].orEmpty()
        if (list.any { it.id == message.id }) return
        messages.value = current + (message.chatId to (list + message))
        val isOpen = openChatId == message.chatId
        val chat = chats.value.firstOrNull { it.id == message.chatId }
        touchChat(
            message.chatId,
            previewOf(message),
            message.senderName,
            message.createdAt,
            unread = if (!message.outgoing && !isOpen && chat?.muted != true) 1 else 0,
        )
        if (notify && !message.outgoing && !isOpen && chat?.muted != true && notifications.value) {
            notifier.notify(chat?.let(::titleOf) ?: message.senderName, previewOf(message))
        }
        persist()
    }

    private fun previewOf(message: Message): String = when (message.kind) {
        MessageKind.IMAGE -> "Photo"
        MessageKind.AUDIO -> "Voice message"
        MessageKind.FILE -> message.attachment?.fileName ?: "File"
        MessageKind.SYSTEM -> message.body
        MessageKind.TEXT -> message.body
    }.take(120)

    private fun systemMessage(chatId: String, text: String) {
        if (chats.value.none { it.id == chatId }) return
        appendMessage(
            Message(
                id = Ids.newId(),
                chatId = chatId,
                senderNodeId = "system",
                senderName = "ShutChat",
                kind = MessageKind.SYSTEM,
                body = text,
                createdAt = clock.nowMillis(),
                status = MessageStatus.DELIVERED,
                outgoing = false,
            ),
        )
    }

    private fun setStatus(messageId: String, status: MessageStatus) {
        var changed = false
        messages.value = messages.value.mapValues { (_, list) ->
            list.map { message ->
                if (message.id == messageId && message.status != status) {
                    changed = true
                    message.copy(status = status)
                } else {
                    message
                }
            }
        }
        if (changed) persist()
    }

    private fun updateMessage(messageId: String, change: (Message) -> Message) {
        var changed = false
        messages.value = messages.value.mapValues { (_, list) ->
            list.map { message ->
                if (message.id == messageId) {
                    changed = true
                    change(message)
                } else {
                    message
                }
            }
        }
        if (changed) persist()
    }

    private fun updateMessages(chatId: String, change: (List<Message>) -> List<Message>) {
        messages.value = messages.value + (chatId to change(messages.value[chatId].orEmpty()))
        persist()
    }

    private fun setAttachmentProgress(attachmentId: String, fraction: Float) {
        transferProgress.value = transferProgress.value + (attachmentId to fraction)
        messages.value = messages.value.mapValues { (_, list) ->
            list.map { message ->
                val attachment = message.attachment
                if (attachment?.id == attachmentId && attachment.progress != fraction) {
                    message.copy(attachment = attachment.copy(progress = fraction))
                } else {
                    message
                }
            }
        }
    }

    private fun applyTransferProgress(progress: DesktopTransfer.Progress) {
        setAttachmentProgress(progress.attachmentId, progress.fraction)
        if (!progress.complete) return
        val path = transfer.pathFor(progress.attachmentId) ?: return
        updateMessage(progress.messageId) { message ->
            message.attachment?.let { message.copy(attachment = it.copy(localPath = path, progress = 1f)) } ?: message
        }
    }

    private fun touchChat(chatId: String, preview: String, sender: String, at: Long, unread: Int) {
        chats.value = chats.value.map { chat ->
            if (chat.id == chatId) {
                chat.copy(
                    lastMessagePreview = preview,
                    lastMessageSender = sender,
                    lastMessageAt = at,
                    lastActivityAt = at,
                    unreadCount = chat.unreadCount + unread,
                )
            } else {
                chat
            }
        }
    }

    private fun addSelfMember(chat: Chat) {
        val member = ChatMember(
            chatId = chat.id,
            nodeId = nodeId,
            displayName = displayName.value,
            avatarSeed = nodeId,
            isSelf = true,
            isOwner = true,
            joinedAt = clock.nowMillis(),
            lastSeenAt = clock.nowMillis(),
            online = true,
        )
        members.value = members.value + (chat.id to (membersOf(chat.id).filterNot { it.nodeId == nodeId } + member))
    }

    private fun touchMember(peer: PeerInfo, online: Boolean) {
        val chatId = peer.roomIdHex
        if (chats.value.none { it.id == chatId }) return
        val list = membersOf(chatId).toMutableList()
        val index = list.indexOfFirst { it.nodeId == peer.nodeId }
        val member = ChatMember(
            chatId = chatId,
            nodeId = peer.nodeId,
            displayName = peer.displayName.ifBlank { peer.nodeId.take(6) },
            avatarSeed = peer.nodeId,
            isSelf = peer.nodeId == nodeId,
            isOwner = false,
            joinedAt = if (index >= 0) list[index].joinedAt else clock.nowMillis(),
            lastSeenAt = clock.nowMillis(),
            online = online,
        )
        if (index >= 0) list[index] = member else list += member
        members.value = members.value + (chatId to list)
        persist()
    }

    private fun touchMemberByName(node: String, name: String) {
        members.value = members.value.mapValues { (_, list) ->
            list.map { if (it.nodeId == node) it.copy(displayName = name.ifBlank { it.displayName }) else it }
        }
    }

    private fun markMemberOffline(node: String) {
        members.value = members.value.mapValues { (_, list) ->
            list.map { if (it.nodeId == node) it.copy(online = false) else it }
        }
    }

    private fun purgeOldMessages() {
        val days = retentionDays.value
        if (days <= 0) return
        val cutoff = clock.nowMillis() - days.toLong() * 24 * 60 * 60 * 1000
        messages.value = messages.value.mapValues { (_, list) -> list.filter { it.createdAt >= cutoff } }
        persist()
    }

    // ------------------------------------------------------------------ calls

    /** False when the machine has no usable sound device and the call is silent. */
    val callAudioAvailable: Boolean
        get() = (calls.currentMedia as? DesktopCallMedia)?.audioAvailable ?: true

    fun call(chatId: String, video: Boolean) {
        val chat = chats.value.firstOrNull { it.id == chatId }
        calls.startCall(
            roomId = chatId,
            peerNodeId = null,
            peerName = chat?.let(::titleOf).orEmpty(),
            kind = if (video) CallMedia.VIDEO else CallMedia.AUDIO,
        )
    }

    // ------------------------------------------------------------------ rooms

    private fun refreshRooms() {
        val specs = chats.value.mapNotNull { chat ->
            val code = chat.inviteCode ?: return@mapNotNull null
            val parsed = runCatching { InviteCodec.parse(code) }.getOrNull() ?: return@mapNotNull null
            RoomSpec(
                chatId = chat.id,
                roomIdHex = parsed.roomId,
                inviteSecret = InviteCodec.secretOf(parsed),
                displayName = displayName.value,
                nodeId = nodeId,
                expired = Expiry.isExpired(parsed.payload.expiryDay, clock),
            )
        }
        mesh.setRooms(specs)
    }

    private fun persist() {
        val snapshot = DesktopState(chats.value, messages.value, members.value, drafts.value)
        scope.launch { persistMutex.withLock { store.save(snapshot) } }
    }

    private fun mimeOf(file: File): String = when (file.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        "m4a", "aac" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "pdf" -> "application/pdf"
        "txt", "md" -> "text/plain"
        else -> "application/octet-stream"
    }

    companion object {
        private const val TAG = "DesktopApp"
        const val APP_VERSION = "1.4.0"
        private const val RETRY_INTERVAL_MILLIS = 20_000L
        private const val MAX_RETRY_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000
        private const val TYPING_TTL_MILLIS = 6_000L
        private const val MAX_SYNC_MESSAGES = 200
    }
}
