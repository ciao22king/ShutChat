package dev.shutchat.app.di

import android.content.Context
import dev.shutchat.app.core.Clock
import dev.shutchat.app.core.Log
import dev.shutchat.app.data.db.ShutChatDatabase
import dev.shutchat.app.data.repo.ChatRepository
import dev.shutchat.app.data.repo.InboundHandler
import dev.shutchat.app.data.repo.MessageRepository
import dev.shutchat.app.data.settings.SettingsStore
import dev.shutchat.app.domain.usecase.ChatFactory
import dev.shutchat.app.media.MediaStore
import dev.shutchat.app.notify.Notifier
import dev.shutchat.app.p2p.AttachmentTransfer
import dev.shutchat.app.p2p.MeshEngine
import dev.shutchat.app.p2p.RoomSpec
import dev.shutchat.app.p2p.lan.LanDiscovery
import dev.shutchat.app.p2p.lan.LanServer
import dev.shutchat.app.p2p.nearby.NearbyTransport
import dev.shutchat.app.p2p.relay.RelayTransport
import dev.shutchat.app.p2p.protocol.Envelope
import dev.shutchat.app.p2p.protocol.WireMessage
import dev.shutchat.app.update.HttpReleaseSource
import dev.shutchat.app.update.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Hand-written dependency container.
 *
 * A DI framework would be more machinery than this app has classes. Everything is
 * constructed once, lazily, and the app has exactly one of each.
 */
class AppContainer(private val context: Context) {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val clock = Clock.System

    val database: ShutChatDatabase by lazy { ShutChatDatabase.build(context) }

    val settings: SettingsStore by lazy { SettingsStore(context) }

    val notifier: Notifier by lazy { Notifier(context) }

    val lanServer: LanServer by lazy { LanServer() }

    val lanDiscovery: LanDiscovery by lazy { LanDiscovery() }

    val nearby: NearbyTransport by lazy { NearbyTransport(context) }

    /** Public-broker transport for long distance. Off unless the user enables it. */
    val relay: RelayTransport by lazy {
        RelayTransport(scope = appScope, clientId = "shutchat-" + java.util.UUID.randomUUID())
    }

    val mesh: MeshEngine by lazy {
        MeshEngine(
            scope = appScope,
            nearby = nearby,
            lanServer = lanServer,
            lanDiscovery = lanDiscovery,
            appContext = context,
            relay = relay,
        )
    }

    val chatRepository: ChatRepository by lazy {
        ChatRepository(database, settings, clock, appScope)
    }

    val messageRepository: MessageRepository by lazy {
        MessageRepository(database, settings, clock, mesh, appScope)
    }

    val chatFactory: ChatFactory by lazy { ChatFactory(database, settings, clock) }

    val inboundHandler: InboundHandler by lazy {
        InboundHandler(database, chatRepository, messageRepository, settings, clock)
    }

    val mediaStore: MediaStore by lazy { MediaStore(context) }

    /** Talks only to the public release feed. No other outbound traffic exists. */
    val updateChecker: UpdateChecker by lazy { UpdateChecker(HttpReleaseSource) }

    val transfer: AttachmentTransfer by lazy { AttachmentTransfer(appScope, mesh, mediaStore) }

    /** UI-visible message when a code expires, so a screen can react. */
    val expiredCodes = MutableSharedFlow<List<String>>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private var bootstrapped = false

    /**
     * Wires the mesh to the database and starts the transports.
     *
     * Called once, from the Application. Safe to call again: the work is guarded
     * so a configuration change or a service restart cannot double-start the mesh.
     */
    suspend fun bootstrap() {
        if (bootstrapped) return
        bootstrapped = true

        val nodeId = settings.nodeId()
        val displayName = settings.currentDisplayName()
        mesh.setIdentity(nodeId, displayName)
        mesh.relayEnabled = settings.relayEnabled.first()
        mesh.setPublicRelay(settings.publicRelay.first())

        refreshRooms()

        val lanEnabled = settings.lanDiscovery.first()
        val nearbyEnabled = settings.nearbyDiscovery.first()
        mesh.start(lanEnabled, nearbyEnabled)

        appScope.launch { consumeIncoming() }
        appScope.launch { consumeChunks() }
        appScope.launch { watchPeerPresence() }
        appScope.launch { sweepExpiredCodes() }
        appScope.launch { chatRepository.purgeOldMessages() }
    }

    /** Pushes the current room set into the mesh after a create/join/rotate. */
    suspend fun refreshRooms() {
        mesh.setRooms(chatFactory.activeRoomSpecs())
    }

    suspend fun sendText(chatId: String, text: String) {
        val chat = chatRepository.chatEntity(chatId) ?: return
        val nodeId = settings.nodeId()
        val displayName = settings.currentDisplayName().ifBlank { "Tu" }
        val message = dev.shutchat.app.domain.model.Message(
            id = dev.shutchat.app.core.Ids.newId(),
            chatId = chatId,
            senderNodeId = nodeId,
            senderName = displayName,
            kind = dev.shutchat.app.domain.model.MessageKind.TEXT,
            body = text,
            createdAt = clock.nowMillis(),
            status = dev.shutchat.app.domain.model.MessageStatus.SENDING,
            outgoing = true,
        )
        messageRepository.storeOutgoing(message)
        val envelope = WireMessage(
            roomId = chat.roomId,
            messageId = message.id,
            chatId = chatId,
            senderNodeId = nodeId,
            senderName = displayName,
            kind = message.kind.name,
            body = text,
            createdAt = message.createdAt,
        )
        val delivered = mesh.broadcast(envelope)
        messageRepository.setStatus(
            listOf(message.id),
            if (delivered > 0) {
                dev.shutchat.app.domain.model.MessageStatus.DELIVERED
            } else {
                dev.shutchat.app.domain.model.MessageStatus.QUEUED
            },
        )
        database.chatDao().updateLastMessage(
            chatId = chatId,
            preview = text,
            sender = displayName,
            at = message.createdAt,
        )
    }

    suspend fun broadcastEnvelope(envelope: Envelope): Int = mesh.broadcast(envelope)

    private suspend fun consumeIncoming() {
        mesh.received.collect { received ->
            // Attachment control envelopes are transport concerns, not chat rows.
            when (received.envelope) {
                is dev.shutchat.app.p2p.protocol.TransferStart,
                is dev.shutchat.app.p2p.protocol.TransferComplete,
                is dev.shutchat.app.p2p.protocol.TransferCancel,
                -> {
                    transfer.onEnvelope(received.envelope)
                    return@collect
                }

                else -> Unit
            }
            runCatching { inboundHandler.handle(received) }
                .onFailure { Log.w("AppContainer", "envelope scartato", it) }
        }
    }

    private suspend fun consumeChunks() {
        mesh.chunkReceived.collect { chunk -> transfer.onChunk(chunk) }
    }

    private suspend fun watchPeerPresence() {
        mesh.peerConnected.collect { chatRepository.updatePeerPresence(mesh.peerInfos()) }
        mesh.peerLost.collect { chatRepository.updatePeerPresence(mesh.peerInfos()) }
    }

    /**
     * Watches for codes crossing their deadline.
     *
     * Checked once a minute rather than scheduled at midnight: a phone that was
     * off overnight would otherwise never fire the expiry, and the user would
     * keep thinking a dead room is reachable.
     */
    private suspend fun sweepExpiredCodes() {
        while (true) {
            val expired = runCatching { chatFactory.expireOutdatedChats() }.getOrDefault(emptyList())
            if (expired.isNotEmpty()) {
                refreshRooms()
                expiredCodes.tryEmit(expired)
            }
            kotlinx.coroutines.delay(60_000)
        }
    }
}
