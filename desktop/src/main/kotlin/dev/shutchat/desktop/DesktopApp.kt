package dev.shutchat.desktop

import dev.shutchat.app.core.Clock
import dev.shutchat.app.core.Ids
import dev.shutchat.app.core.Log
import dev.shutchat.app.domain.codec.InviteCodec
import dev.shutchat.app.domain.expiry.Expiry
import dev.shutchat.app.domain.model.Chat
import dev.shutchat.app.domain.model.ChatKind
import dev.shutchat.app.domain.model.ChatStatus
import dev.shutchat.app.domain.model.InviteKind
import dev.shutchat.app.domain.model.Message
import dev.shutchat.app.domain.model.MessageKind
import dev.shutchat.app.domain.model.MessageStatus
import dev.shutchat.app.p2p.MeshEngine
import dev.shutchat.app.p2p.ReceivedEnvelope
import dev.shutchat.app.p2p.RoomSpec
import dev.shutchat.app.p2p.call.CallController
import dev.shutchat.app.p2p.lan.LanDiscovery
import dev.shutchat.app.p2p.lan.LanServer
import dev.shutchat.app.p2p.protocol.CallMedia
import dev.shutchat.app.p2p.protocol.Receipt
import dev.shutchat.app.p2p.protocol.WireMessage
import dev.shutchat.app.p2p.protocol.isCallSignal
import dev.shutchat.app.p2p.relay.RelayTransport
import dev.shutchat.desktop.call.DesktopCallMediaFactory
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
 * Mirrors `AppContainer` on Android, minus everything Android-specific: no
 * database, no notifications, no proximity radio. The mesh, the relay, the
 * crypto and the call controller are the same classes the phone runs, because
 * they live in `:shared`.
 */
class DesktopApp(
    private val settings: DesktopSettings = DesktopSettings(),
    private val store: DesktopStore = DesktopStore(),
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

    private val initial = store.load()

    val chats = MutableStateFlow(initial.chats)
    val messages = MutableStateFlow(initial.messages)
    val connectedPeers = MutableStateFlow(0)
    val relayActive = MutableStateFlow(false)
    val relayLabel = MutableStateFlow("")
    val lanActive = MutableStateFlow(false)

    val publicRelay = MutableStateFlow(settings.publicRelay)
    val lanEnabled = MutableStateFlow(settings.lanDiscovery)

    private val persistMutex = Mutex()
    private var started = false

    fun start() {
        if (started) return
        started = true
        Log.i(TAG, "desktop starting, node $nodeId")

        mesh.setIdentity(nodeId, displayName.value)
        mesh.forwardingEnabled = true
        mesh.setPublicRelay(publicRelay.value)
        refreshRooms()
        mesh.start(lanEnabled.value, enableNearby = false)

        scope.launch {
            mesh.received.collect { received -> handleIncoming(received) }
        }
        scope.launch {
            mesh.peerConnected.collect { connectedPeers.value = mesh.connectedNodeIds().size }
        }
        scope.launch {
            mesh.peerLost.collect { connectedPeers.value = mesh.connectedNodeIds().size }
        }
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
    }

    fun stop() {
        runCatching { mesh.stop() }
        runCatching { calls.shutdown() }
        scope.cancel()
    }

    // ------------------------------------------------------------------ chats

    fun createChat(kind: InviteKind, expiryDay: Int?): Chat {
        val code = InviteCodec.create(kind, expiryDay)
        val now = clock.nowMillis()
        val chat = Chat(
            id = code.roomId,
            kind = if (kind == InviteKind.GROUP) ChatKind.GROUP else ChatKind.DIRECT,
            title = if (kind == InviteKind.GROUP) "Gruppo" else "Chat",
            status = ChatStatus.ACTIVE,
            inviteCode = code.text,
            inviteExpiryDay = code.payload.expiryDay,
            createdAt = now,
            lastActivityAt = now,
        )
        chats.value = chats.value + chat
        refreshRooms()
        persist()
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
        refreshRooms()
        persist()
        return chat
    }

    fun deleteChat(chatId: String) {
        chats.value = chats.value.filterNot { it.id == chatId }
        messages.value = messages.value - chatId
        refreshRooms()
        persist()
    }

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
        // The mesh only reads this at start; a restart of the transports is the
        // honest way to apply it without duplicating start/stop logic.
        runCatching { mesh.stop() }
        refreshRooms()
        mesh.start(enabled, enableNearby = false)
    }

    // --------------------------------------------------------------- messages

    fun send(chatId: String, text: String) {
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
        )
        appendMessage(message)
        scope.launch {
            val handed = mesh.broadcast(message.toWire(chatId))
            setStatus(message.id, if (handed > 0) MessageStatus.SENDING else MessageStatus.QUEUED)
        }
    }

    /** Re-sends anything the mesh could not hand to a peer yet. */
    private suspend fun resendPending() {
        val cutoff = clock.nowMillis() - MAX_RETRY_AGE_MILLIS
        messages.value.forEach { (chatId, list) ->
            list.filter { it.outgoing && it.status.isPending && it.createdAt > cutoff }
                .forEach { message ->
                    val handed = mesh.broadcast(message.toWire(chatId))
                    if (handed > 0) setStatus(message.id, MessageStatus.SENDING)
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
    )

    private suspend fun handleIncoming(received: ReceivedEnvelope) {
        val envelope = received.envelope
        if (envelope.isCallSignal()) {
            calls.onEnvelope(envelope, received.fromNodeId)
            return
        }
        when (envelope) {
            is WireMessage -> {
                val chatId = envelope.roomId
                if (chats.value.none { it.id == chatId }) return
                val existing = messages.value[chatId].orEmpty().any { it.id == envelope.messageId }
                if (!existing) {
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
                        ),
                    )
                }
                // Acknowledge every delivery, including a duplicate: a lost
                // receipt is why the sender keeps retrying.
                runCatching {
                    mesh.broadcast(
                        Receipt(
                            roomId = chatId,
                            messageIds = listOf(envelope.messageId),
                            state = "delivered",
                            nodeId = nodeId,
                        ),
                    )
                }
            }

            is Receipt -> {
                envelope.messageIds.forEach { setStatus(it, if (envelope.state == "read") MessageStatus.READ else MessageStatus.DELIVERED) }
            }

            else -> Unit
        }
    }

    private fun appendMessage(message: Message) {
        val current = messages.value
        val list = current[message.chatId].orEmpty()
        if (list.any { it.id == message.id }) return
        messages.value = current + (message.chatId to (list + message))
        touchChat(message.chatId, message.body, message.senderName, message.createdAt)
        persist()
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

    private fun touchChat(chatId: String, preview: String, sender: String, at: Long) {
        chats.value = chats.value.map { chat ->
            if (chat.id == chatId) {
                chat.copy(
                    lastMessagePreview = preview.take(120),
                    lastMessageSender = sender,
                    lastMessageAt = at,
                    lastActivityAt = at,
                )
            } else {
                chat
            }
        }
    }

    // ------------------------------------------------------------------ calls

    fun call(chatId: String, video: Boolean, peerNodeId: String? = null, peerName: String = "") {
        calls.startCall(
            roomId = chatId,
            peerNodeId = peerNodeId,
            peerName = peerName.ifBlank { chats.value.firstOrNull { it.id == chatId }?.title.orEmpty() },
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
        val snapshot = DesktopState(chats.value, messages.value)
        scope.launch { persistMutex.withLock { store.save(snapshot) } }
    }

    companion object {
        private const val TAG = "DesktopApp"
        private const val APP_VERSION = "1.3.5"
        private const val RETRY_INTERVAL_MILLIS = 20_000L
        private const val MAX_RETRY_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000
    }
}
