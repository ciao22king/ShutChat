package dev.shutchat.app.di

import android.content.Context
import dev.shutchat.app.core.Clock
import dev.shutchat.app.core.Log
import dev.shutchat.app.data.db.ShutChatDatabase
import dev.shutchat.app.data.repo.ChatRepository
import dev.shutchat.app.data.repo.InboundHandler
import dev.shutchat.app.data.repo.MessageRepository
import dev.shutchat.app.data.repo.Outbox
import dev.shutchat.app.data.repo.RetryPolicy
import dev.shutchat.app.data.settings.SettingsStore
import dev.shutchat.app.domain.usecase.ChatFactory
import dev.shutchat.app.media.MediaStore
import dev.shutchat.app.notify.Notifier
import dev.shutchat.app.p2p.AttachmentTransfer
import dev.shutchat.app.p2p.MeshEngine
import dev.shutchat.app.p2p.RoomSpec
import dev.shutchat.app.p2p.call.AndroidCallMediaFactory
import dev.shutchat.app.p2p.call.CallController
import dev.shutchat.app.p2p.lan.AndroidMulticastLock
import dev.shutchat.app.p2p.lan.LanDiscovery
import dev.shutchat.app.p2p.lan.LanServer
import dev.shutchat.app.p2p.proximity.MdnsTransport
import dev.shutchat.app.p2p.protocol.isCallSignal
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

    val lanDiscovery: LanDiscovery by lazy { LanDiscovery(AndroidMulticastLock(context)) }

    /**
     * Proximity transport. mDNS plus a TCP socket, both from the Android
     * framework: no Play Services, so this works on an AOSP build too.
     */
    val proximity: MdnsTransport by lazy { MdnsTransport(context, appScope) }

    /** Public-broker transport for long distance. Off unless the user enables it. */
    val relay: RelayTransport by lazy {
        // A stable id, set from the node id at bootstrap: the broker keeps the
        // session (and queued frames) only while the client id stays the same.
        RelayTransport(scope = appScope, clientId = "shutchat-pending")
    }

    val mesh: MeshEngine by lazy {
        MeshEngine(
            scope = appScope,
            proximity = proximity,
            lanServer = lanServer,
            lanDiscovery = lanDiscovery,
            relay = relay,
            appVersion = dev.shutchat.app.BuildConfig.VERSION_NAME,
        )
    }

    /** Identity cached at bootstrap so the call layer can read it without suspending. */
    @Volatile
    private var localNodeId: String = ""

    @Volatile
    private var localDisplayName: String = ""

    /**
     * Audio and video calls.
     *
     * Signalling rides the same sealed envelopes as text; only the media is
     * peer to peer. The controller is platform-neutral, so the desktop client
     * runs the very same one.
     */
    val calls: CallController by lazy {
        CallController(
            scope = appScope,
            selfNodeId = { localNodeId },
            selfName = { localDisplayName.ifBlank { "Tu" } },
            clock = clock,
            send = { envelope -> mesh.broadcast(envelope) },
            mediaFactory = AndroidCallMediaFactory(context),
            onIncoming = { snapshot ->
                notifier.incomingCall(
                    peerName = snapshot.peerName,
                    video = snapshot.media == dev.shutchat.app.p2p.protocol.CallMedia.VIDEO,
                )
            },
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

    /**
     * Re-sends messages that never reached anyone.
     *
     * Declared after the mesh because it depends on it, and started in
     * [bootstrap] rather than on construction so it cannot run before the
     * transports are up.
     */
    val outbox: Outbox by lazy { Outbox(database, clock, mesh, appScope) }

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
        localNodeId = nodeId
        localDisplayName = displayName
        relay.setClientId("shutchat-$nodeId")
        mesh.setIdentity(nodeId, displayName)
        mesh.forwardingEnabled = settings.relayEnabled.first()
        mesh.setPublicRelay(settings.publicRelay.first())

        refreshRooms()

        val lanEnabled = settings.lanDiscovery.first()
        val nearbyEnabled = settings.nearbyDiscovery.first()
        mesh.start(lanEnabled, nearbyEnabled)

        appScope.launch { consumeIncoming() }
        appScope.launch { reAcknowledgeDuplicates() }
        outbox.start()
        appScope.launch { consumeChunks() }
        appScope.launch { watchPeerPresence() }
        appScope.launch { sweepExpiredCodes() }
        appScope.launch { chatRepository.purgeOldMessages() }
    }

    /** Pushes the current room set into the mesh after a create/join/rotate. */
    suspend fun refreshRooms() {
        mesh.setRooms(chatFactory.activeRoomSpecs())
    }

    /**
     * Deletes a chat and everything in it.
     *
     * The order matters. The leave notice goes out while the room is still in the
     * mesh, because once [refreshRooms] runs this device no longer holds the room
     * and cannot address it. Then the rows go, then the files: files are deleted
     * last so a crash midway leaves rows pointing at files that still exist, which
     * the next delete can clean up, rather than files nothing references.
     */
    suspend fun deleteChat(chatId: String) {
        val chat = chatRepository.chatEntity(chatId)

        if (chat != null) {
            runCatching {
                broadcastEnvelope(
                    dev.shutchat.app.p2p.protocol.LeaveNotice(
                        roomId = chat.roomId,
                        chatId = chatId,
                        nodeId = settings.nodeId(),
                    ),
                )
            }.onFailure { Log.w("AppContainer", "leave notice not sent", it) }
        }

        val orphans = runCatching { chatFactory.deleteChat(chatId) }
            .onFailure { Log.e("AppContainer", "chat deletion failed", it) }
            .getOrDefault(emptyList())

        // A deleted chat's messages must not be re-sent by the outbox, and its
        // file bytes must not stay in private storage where nothing can reach them.
        runCatching { outbox.flush() }
        orphans.forEach { path ->
            runCatching { java.io.File(path).delete() }
        }

        refreshRooms()
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
        val handedTo = mesh.broadcast(envelope)
        // Handing a frame to a socket is not delivery, so this is not DELIVERED.
        // The recipient's receipt promotes it, and until then the outbox keeps
        // re-sending it.
        messageRepository.setStatus(listOf(message.id), RetryPolicy.afterHandoff(handedTo))
        database.chatDao().updateLastMessage(
            chatId = chatId,
            preview = text,
            sender = displayName,
            at = message.createdAt,
        )
    }

    suspend fun broadcastEnvelope(envelope: Envelope): Int = mesh.broadcast(envelope)

    /**
     * Starts a call in [chatId].
     *
     * The UI knows a chat id; the wire needs the room id, so the translation
     * happens here rather than in every screen that can place a call.
     */
    suspend fun startCall(
        chatId: String,
        peerNodeId: String?,
        peerName: String,
        media: dev.shutchat.app.p2p.protocol.CallMedia,
    ) {
        val chat = chatRepository.chatEntity(chatId) ?: return
        calls.startCall(chat.roomId, peerNodeId, peerName, media)
    }

    private suspend fun consumeIncoming() {
        mesh.received.collect { received ->
            // Call signalling is transient: it must not become a chat row.
            if (received.envelope.isCallSignal()) {
                calls.onEnvelope(received.envelope, received.fromNodeId)
                return@collect
            }
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
                .onFailure { Log.w("AppContainer", "envelope discarded", it) }
        }
    }

    /**
     * Re-acknowledges a message that arrived twice.
     *
     * A duplicate means the sender did not receive the first receipt, so the answer
     * is to send another one. Only messages are acknowledged: control envelopes are
     * idempotent, and acknowledging a receipt would loop.
     */
    private suspend fun reAcknowledgeDuplicates() {
        mesh.duplicates.collect { envelope ->
            if (envelope !is WireMessage) return@collect
            val chat = chatRepository.chatEntityForRoom(envelope.roomId) ?: return@collect
            runCatching {
                messageRepository.acknowledge(chat.roomId, listOf(envelope.messageId), "delivered")
            }
        }
    }

    private suspend fun consumeChunks() {
        mesh.chunkReceived.collect { chunk -> transfer.onChunk(chunk) }
    }

    private suspend fun watchPeerPresence() {
        // The outbox is flushed on the same events: a peer appearing is the thing
        // an undelivered message was waiting for, so it should leave now rather
        // than at the next timer tick.
        mesh.peerConnected.collect {
            chatRepository.updatePeerPresence(mesh.peerInfos())
            runCatching { outbox.flush() }
        }
        mesh.peerLost.collect {
            chatRepository.updatePeerPresence(mesh.peerInfos())
            runCatching { outbox.flush() }
        }
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
