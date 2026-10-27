package dev.shutchat.app.p2p

import android.content.Context
import dev.shutchat.app.core.Log
import dev.shutchat.app.core.hexToBytes
import dev.shutchat.app.core.toHex
import dev.shutchat.app.crypto.RoomCrypto
import dev.shutchat.app.p2p.lan.LanDiscovery
import dev.shutchat.app.p2p.lan.LanLink
import dev.shutchat.app.p2p.lan.LanServer
import dev.shutchat.app.p2p.nearby.NearbyTransport
import dev.shutchat.app.p2p.protocol.Envelope
import dev.shutchat.app.p2p.protocol.EnvelopeCodec
import dev.shutchat.app.p2p.protocol.Framing
import dev.shutchat.app.p2p.protocol.Hello
import dev.shutchat.app.p2p.protocol.HelloAck
import dev.shutchat.app.p2p.protocol.Ping
import dev.shutchat.app.p2p.protocol.Protocol
import dev.shutchat.app.p2p.protocol.WireMessage
import dev.shutchat.app.p2p.relay.RelayTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The mesh: keeps links up, completes handshakes, routes envelopes.
 *
 * Things worth knowing before editing:
 *
 *  * **Two transports, one abstraction.** LAN links (TCP over the local network)
 *    and Nearby links (Bluetooth / Wi-Fi Direct, no router needed) both end up as
 *    [PeerLink]s. Nothing above this class knows which one it is using.
 *  * **Room key encryption.** A link is not usable until the peer proves it holds
 *    the invite code. Envelopes are sealed with a key derived from the invite
 *    secret, which is what makes serverless groups work: any member can seal a
 *    message every other member can open.
 *  * **Relaying.** A device holding a room may forward a frame whose room id it
 *    recognises without being able to read it, because the room id travels in the
 *    clear in the frame header. This extends a group beyond radio range.
 *    Relaying is opt-out from settings.
 *  * **No accounts, no directory.** Room ids come from the invite secret, so a
 *    room is only reachable by someone who was handed the code.
 */
class MeshEngine(
    private val scope: CoroutineScope,
    private val nearby: NearbyTransport,
    private val lanServer: LanServer,
    private val lanDiscovery: LanDiscovery,
    private val appContext: Context,
    private val relay: RelayTransport,
) {

    /** Decrypted envelopes, ready to be turned into rows by the repository. */
    val received = MutableSharedFlow<ReceivedEnvelope>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val peerConnected = MutableSharedFlow<PeerInfo>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val peerLost = MutableSharedFlow<String>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Envelopes that arrived again after being seen once.
     *
     * The outbox re-sends anything it has no receipt for, so a message can
     * legitimately arrive more than once. The row is stored only once, but the
     * sender still needs an acknowledgement each time: otherwise a receipt lost on
     * the way back would make the sender retry for the whole retry window.
     */
    val duplicates = MutableSharedFlow<Envelope>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Attachment chunks routed up from the transports. */
    val chunkReceived = MutableSharedFlow<InboundChunk>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    data class InboundChunk(
        val roomIdHex: String,
        val transferId: String,
        val index: Int,
        val total: Int,
        val body: ByteArray,
        val fromNodeId: String,
    )

    /**
     * Whether this device forwards frames it cannot read for rooms it holds.
     *
     * Kept separate from [publicRelayEnabled] on purpose: forwarding is what
     * extends a group beyond radio range, and it must not switch off as a side
     * effect of changing how this device reaches the internet.
     */
    @Volatile
    var forwardingEnabled: Boolean = true

    /** Whether this device reaches peers at a distance through the public broker. */
    @Volatile
    var publicRelayEnabled: Boolean = false

    /** True when the public relay is switched on and a broker session is up. */
    @Volatile
    var relayActive: Boolean = false
        private set

    @Volatile
    var diagnostics: MeshDiagnostics = MeshDiagnostics()
        private set

    private val links = LinkRegistry()
    private val peers = ConcurrentHashMap<String, PeerInfo>()
    private val rooms = ConcurrentHashMap<String, RoomSpec>()

    /** Nearby endpoints per room, so a broadcast can reach them too. */
    private val nearbyEndpointsByRoom = ConcurrentHashMap<String, MutableSet<String>>()

    private val seen = SeenCache(capacity = 2048)
    private val relaySeen = SeenCache(capacity = 4096)
    private val sendMutex = Mutex()

    private val framesSent = AtomicLong()
    private val framesReceived = AtomicLong()
    private val framesRelayed = AtomicLong()
    private val decryptFailures = AtomicLong()

    private var localNodeId: String = ""
    private var localDisplayName: String = ""
    private var lanPort = 0
    private var started = false
    private var lanEnabled = false
    private var nearbyEnabled = false

    /** Must be called before [start]. */
    fun setIdentity(nodeId: String, displayName: String) {
        this.localNodeId = nodeId
        this.localDisplayName = displayName
    }

    /**
     * Replaces the set of rooms this device advertises and accepts traffic for.
     * Expired rooms are kept out of discovery but remain readable locally.
     */
    fun setRooms(specs: List<RoomSpec>) {
        val previous = rooms.keys.toSet()
        rooms.clear()
        specs.forEach { rooms[it.roomIdHex] = it }

        if (!started) return
        val advertised = specs.filterNot { it.expired }.map { it.roomIdHex }
        val added = advertised.filter { it !in previous }
        if (nearbyEnabled && added.isNotEmpty()) {
            nearby.advertise(added, localDisplayName)
            nearby.discover(added)
        }
        // Drop endpoints belonging to rooms we no longer hold.
        nearbyEndpointsByRoom.keys.retainAll(rooms.keys)
        if (publicRelayEnabled) publishRelayTopics()
        refreshDiagnostics()
    }

    /** Turns the public relay on or off and starts or stops the broker session. */
    fun setPublicRelay(value: Boolean) {
        publicRelayEnabled = value
        if (!started) return
        if (value) {
            publishRelayTopics()
            relay.start()
        } else {
            relay.stop()
            relayActive = false
        }
        refreshDiagnostics()
    }

    /**
     * Recomputes the broker topics from the live room set.
     *
     * Topics come from the invite secret, never from the room id: the room id is
     * broadcast in the clear on every frame, so using it here would let anyone
     * watching the broker recognise a room they have seen elsewhere.
     */
    private fun publishRelayTopics() {
        val topics = rooms.values
            .filterNot { it.expired }
            .associate { it.roomIdHex to RoomCrypto.relayTopic(it.inviteSecret) }
        relay.setTopics(topics)
    }

    fun start(enableLan: Boolean, enableNearby: Boolean) {
        if (started) return
        started = true
        lanEnabled = enableLan
        nearbyEnabled = enableNearby

        scope.launch(Dispatchers.IO) {
            lanServer.accepted.collect { socket -> acceptInboundSocket(socket) }
        }
        scope.launch(Dispatchers.IO) {
            lanDiscovery.discoveries.collect { discovery -> onLanDiscovery(discovery) }
        }
        scope.launch(Dispatchers.IO) {
            nearby.incoming.collect { event -> onNearbyEvent(event) }
        }
        scope.launch(Dispatchers.IO) {
            nearby.endpoints.collect { event -> onNearbyEndpoint(event) }
        }
        scope.launch(Dispatchers.IO) {
            relay.frames.collect { frame -> handleRelayFrame(frame) }
        }

        if (lanEnabled) {
            lanPort = lanServer.start(scope)
            lanDiscovery.start(scope, appContext) { buildBeacons() }
        }
        if (nearbyEnabled) {
            val ids = rooms.values.filterNot { it.expired }.map { it.roomIdHex }
            if (ids.isNotEmpty()) {
                nearby.advertise(ids, localDisplayName)
                nearby.discover(ids)
            }
        }
        if (publicRelayEnabled) {
            publishRelayTopics()
            relay.start()
        }

        scope.launch {
            while (isActive) {
                delay(PING_INTERVAL_MILLIS)
                runCatching { sendPings() }
                // The relay connects and drops on its own thread, so its state is
                // folded into diagnostics here rather than only on link events:
                // otherwise settings would keep showing the relay as off while it
                // is connected, or as connected after the broker went away.
                runCatching { refreshDiagnostics() }
            }
        }
        refreshDiagnostics()
    }

    fun stop() {
        started = false
        lanDiscovery.stop()
        lanServer.stop()
        nearby.stop()
        relay.stop()
        relayActive = false
        links.clear()
        peers.clear()
        nearbyEndpointsByRoom.clear()
        refreshDiagnostics()
    }

    // ----------------------------------------------------------------- outbound

    /**
     * Sends [envelope] to every peer sharing its room.
     *
     * @return number of peers it was handed to. Zero means the caller should
     *         store the message as QUEUED and retry when a peer appears.
     */
    suspend fun broadcast(envelope: Envelope): Int {
        val room = rooms[envelope.roomId] ?: return 0
        // Mark our own envelope as seen before it leaves. A broker echoes a
        // publish back to the publisher, and envelopes that name no author (a
        // roster, a sync result) would otherwise be processed as incoming.
        seen.markSeen(envelope.dedupeKey())
        val plaintext = EnvelopeCodec.encode(envelope)
        val sealed = RoomCrypto.encrypt(room.messageKey, plaintext, room.roomIdBytes)
        val frame = Framing.frameControl(room.roomIdBytes, sealed)
        return dispatch(frame, envelope.roomId, excludeNodeId = null)
    }

    /** Sends an already framed payload, used by relays and attachment chunks. */
    suspend fun sendFrame(roomIdHex: String, frame: ByteArray, excludeNodeId: String? = null): Int =
        dispatch(frame, roomIdHex, excludeNodeId)

    private suspend fun dispatch(frame: ByteArray, roomIdHex: String, excludeNodeId: String?): Int {
        val room = rooms[roomIdHex] ?: return 0
        var delivered = 0
        sendMutex.withLock {
            links.all().forEach { (nodeId, link) ->
                if (nodeId == excludeNodeId) return@forEach
                val peer = peers[nodeId] ?: return@forEach
                if (peer.roomIdHex != roomIdHex) return@forEach
                scope.launch {
                    if (link.send(frame)) framesSent.incrementAndGet()
                }
                delivered++
            }
            nearbyEndpointsByRoom[roomIdHex]?.forEach { endpoint ->
                if (nearby.nodeIdFor(endpoint) == excludeNodeId) return@forEach
                if (!nearby.isConnected(endpoint)) return@forEach
                scope.launch {
                    if (nearby.send(endpoint, frame)) framesSent.incrementAndGet()
                }
                delivered++
            }
            // The relay reaches peers that share no network with us. It is counted
            // only when the broker session is actually up, so a message is not
            // reported as delivered to a broker that is not listening. The live
            // state is read rather than the cached [relayActive], which is only
            // refreshed on events and would still read false just after connect.
            if (publicRelayEnabled && relay.connected && excludeNodeId == null) {
                scope.launch {
                    if (relay.send(roomIdHex, frame)) framesSent.incrementAndGet()
                }
                delivered++
            }
        }
        refreshDiagnostics()
        return delivered
    }

    // ------------------------------------------------------------------ inbound

    private fun acceptInboundSocket(socket: Socket) {
        // The first frame on a fresh socket must be the hello, which tells us the
        // peer's node id; only then can we name the link.
        scope.launch(Dispatchers.IO) {
            try {
                val reader = dev.shutchat.app.p2p.lan.FrameReader(
                    java.io.DataInputStream(socket.getInputStream()),
                )
                val first = reader.readFrame()
                if (first == null) {
                    socket.close()
                    return@launch
                }
                val hello = parseHello(first)
                if (hello == null) {
                    Log.d(TAG, "primo frame non è un hello")
                    socket.close()
                    return@launch
                }
                val room = rooms[hello.roomId]
                if (room == null || hello.protocolVersion != Protocol.VERSION) {
                    Log.d(TAG, "hello per una stanza sconosciuta")
                    socket.close()
                    return@launch
                }
                val link = LanLink(socket, hello.nodeId)
                registerPeer(hello, link, hello.roomId)
                link.start(scope)
                scope.launch(Dispatchers.IO) {
                    link.incoming.collect { frame -> handleFrame(link, frame) }
                }
                sendHelloAck(link, room)
            } catch (e: Exception) {
                Log.d(TAG, "handshake in ingresso fallito: ${e.message}")
                runCatching { socket.close() }
            }
        }
    }

    private suspend fun onLanDiscovery(discovery: LanDiscovery.Discovery) {
        if (discovery.nodeIdHex == localNodeId) return
        val room = rooms[discovery.roomIdHex] ?: return
        if (room.expired) return
        if (links.get(discovery.nodeIdHex) != null) return

        // Only the lexicographically smaller node id dials, so two devices that
        // discover each other at the same moment do not open two sockets.
        if (localNodeId > discovery.nodeIdHex) return

        val link = LanLink.connect(discovery.address, discovery.tcpPort, discovery.nodeIdHex) ?: return
        registerPeer(
            Hello(
                roomId = discovery.roomIdHex,
                nodeId = discovery.nodeIdHex,
                displayName = discovery.displayName,
                avatarSeed = discovery.nodeIdHex,
                knownRoomIds = emptyList(),
                protocolVersion = Protocol.VERSION,
                appVersion = APP_VERSION,
            ),
            link,
            discovery.roomIdHex,
        )
        link.start(scope)
        sendHello(link, room)
        scope.launch(Dispatchers.IO) {
            link.incoming.collect { frame -> handleFrame(link, frame) }
        }
    }

    private suspend fun onNearbyEvent(event: NearbyTransport.Incoming) {
        when (event) {
            is NearbyTransport.Incoming.Frame -> {
                val knownNode = nearby.nodeIdFor(event.endpointId)
                if (knownNode == null) {
                    handleNearbyHello(event.endpointId, event.bytes)
                } else {
                    links.get(knownNode)?.let { handleFrame(it, event.bytes) }
                }
            }

            is NearbyTransport.Incoming.Connected -> {
                Log.d(TAG, "endpoint connesso: ${event.endpointId}")
            }

            is NearbyTransport.Incoming.Disconnected -> {
                val node = nearby.nodeIdFor(event.endpointId) ?: return
                nearbyEndpointsByRoom.values.forEach { it.remove(event.endpointId) }
                unregisterPeer(node)
            }
        }
    }

    private fun onNearbyEndpoint(event: NearbyTransport.EndpointEvent) {
        when (event) {
            is NearbyTransport.EndpointEvent.Found -> {
                if (event.roomIdHex.isEmpty() || !rooms.containsKey(event.roomIdHex)) return
                if (nearby.isConnected(event.endpointId)) return
                nearby.connect(event.endpointId)
            }

            is NearbyTransport.EndpointEvent.Lost -> Log.d(TAG, "endpoint perso")
        }
    }

    private suspend fun handleNearbyHello(endpointId: String, frame: ByteArray) {
        val hello = parseHello(frame) ?: return
        val room = rooms[hello.roomId] ?: return
        if (hello.protocolVersion != Protocol.VERSION || hello.nodeId == localNodeId) return

        nearby.bindNode(endpointId, hello.nodeId)
        val link = NearbyPeerLink(endpointId, hello.nodeId, nearby)
        registerPeer(hello, link, hello.roomId)
        nearbyEndpointsByRoom.getOrPut(hello.roomId) { ConcurrentHashMap.newKeySet() }.add(endpointId)
        sendHelloAck(link, room)
    }

    /** Routes one frame from an established link. */
    private suspend fun handleFrame(link: PeerLink, frame: ByteArray) {
        val parsed = runCatching { Framing.parse(frame) }.getOrNull() ?: return
        framesReceived.incrementAndGet()
        val roomIdHex = parsed.roomId.toHex()
        val room = rooms[roomIdHex] ?: return
        processFrame(parsed, roomIdHex, room, link.remoteNodeId, link.transportLabel, frame)
        refreshDiagnostics()
    }

    /**
     * Routes one frame that arrived from the public relay.
     *
     * There is no link here: the broker is a shared pipe, not a peer, so the
     * sender is unknown until the envelope is decrypted. The frame is treated
     * exactly like one from a link once that is known, which is what lets the two
     * transports interoperate — a message sent over Wi-Fi Direct can be answered
     * over the relay and the conversation above never notices.
     */
    private suspend fun handleRelayFrame(frame: ByteArray) {
        val parsed = runCatching { Framing.parse(frame) }.getOrNull() ?: return
        framesReceived.incrementAndGet()
        val roomIdHex = parsed.roomId.toHex()
        val room = rooms[roomIdHex] ?: return
        processFrame(parsed, roomIdHex, room, fromNodeId = null, transport = "Relay", frame = frame)
        refreshDiagnostics()
    }

    /**
     * Shared body of both inbound paths.
     *
     * [fromNodeId] is null for relay frames, where the sender is only learned from
     * the decrypted envelope.
     */
    private suspend fun processFrame(
        parsed: Framing.Frame,
        roomIdHex: String,
        room: RoomSpec,
        fromNodeId: String?,
        transport: String,
        frame: ByteArray,
    ) {
        when (parsed) {
            is Framing.Frame.Chunk -> {
                val body = runCatching { RoomCrypto.decrypt(room.transferKey, parsed.body, parsed.roomId) }
                    .getOrElse {
                        decryptFailures.incrementAndGet()
                        return
                    }
                chunkReceived.tryEmit(
                    InboundChunk(
                        roomIdHex = roomIdHex,
                        transferId = parsed.transferId,
                        index = parsed.index,
                        total = parsed.total,
                        body = body,
                        fromNodeId = fromNodeId ?: UNKNOWN_SENDER,
                    ),
                )
            }

            is Framing.Frame.Control -> {
                val plaintext = runCatching { RoomCrypto.decrypt(room.messageKey, parsed.body, parsed.roomId) }
                    .getOrElse {
                        decryptFailures.incrementAndGet()
                        // Either tampering or a frame sealed for a room we hold but
                        // whose code has since changed. Relaying is pointless here
                        // because we cannot tell what it is; drop it.
                        return
                    }
                val envelope = runCatching { EnvelopeCodec.decode(plaintext) }.getOrNull() ?: return

                // A frame that came back through the relay carries our own node id
                // when we sent it. The broker echoes to every subscriber, so this
                // is the normal case, not an edge one.
                val sender = fromNodeId ?: envelope.senderNodeId()
                if (sender == localNodeId) return

                val dedupeKey = envelope.dedupeKey()
                val firstTime = seen.markSeen(dedupeKey)
                if (firstTime) {
                    val peer = peers[sender]
                    received.tryEmit(
                        ReceivedEnvelope(
                            envelope = envelope,
                            fromNodeId = sender,
                            fromDisplayName = peer?.displayName ?: sender.take(6),
                            transport = transport,
                        ),
                    )
                } else {
                    // Already handled: do not store it twice, but let the caller
                    // acknowledge it again so the sender can stop retrying.
                    duplicates.tryEmit(envelope)
                }
                maybeRelay(frame, envelope, roomIdHex, excludeNodeId = sender)
            }
        }
    }

    /**
     * Forwards an envelope once per device.
     *
     * Relaying happens even for an envelope we have already seen: another branch
     * of the mesh may still be missing it. The [relaySeen] cache is separate from
     * [seen] precisely so that deduplication for the UI and deduplication for the
     * network do not fight each other.
     */
    private suspend fun maybeRelay(
        frame: ByteArray,
        envelope: Envelope,
        roomIdHex: String,
        excludeNodeId: String?,
    ) {
        if (!forwardingEnabled) return
        if (!rooms.containsKey(roomIdHex)) return

        val hops = when (envelope) {
            is WireMessage -> envelope.hops
            else -> 0
        }
        if (hops >= Protocol.MAX_HOPS) return

        val key = "relay:${envelope.dedupeKey()}"
        if (!relaySeen.markSeen(key)) return
        framesRelayed.incrementAndGet()
        dispatch(frame, roomIdHex, excludeNodeId)
    }

    // ------------------------------------------------------------------ helpers

    private fun parseHello(frame: ByteArray): Hello? = runCatching {
        val parsed = Framing.parse(frame)
        if (parsed !is Framing.Frame.Control) return null
        EnvelopeCodec.decode(parsed.body) as? Hello
    }.getOrNull()

    private suspend fun sendHello(link: PeerLink, room: RoomSpec) {
        val hello = Hello(
            roomId = room.roomIdHex,
            nodeId = localNodeId,
            displayName = localDisplayName,
            avatarSeed = localNodeId,
            knownRoomIds = rooms.keys.toList(),
            protocolVersion = Protocol.VERSION,
            appVersion = APP_VERSION,
        )
        // The hello is the first frame and carries only public routing material,
        // so it goes out unsealed; everything after it is encrypted.
        link.send(Framing.frameControl(room.roomIdBytes, EnvelopeCodec.encode(hello)))
    }

    private suspend fun sendHelloAck(link: PeerLink, room: RoomSpec) {
        val ack = HelloAck(
            roomId = room.roomIdHex,
            nodeId = localNodeId,
            displayName = localDisplayName,
            avatarSeed = localNodeId,
            knownRoomIds = rooms.keys.toList(),
            protocolVersion = Protocol.VERSION,
            appVersion = APP_VERSION,
        )
        val sealed = RoomCrypto.encrypt(room.messageKey, EnvelopeCodec.encode(ack), room.roomIdBytes)
        link.send(Framing.frameControl(room.roomIdBytes, sealed))
    }

    private fun registerPeer(hello: Hello, link: PeerLink, roomIdHex: String) {
        val existing = links.get(hello.nodeId)
        if (existing != null && existing.isOpen) {
            link.close()
            return
        }
        links.put(hello.nodeId, link)
        val info = PeerInfo(
            nodeId = hello.nodeId,
            displayName = hello.displayName,
            roomIdHex = roomIdHex,
            transport = link.transportLabel,
            lastSeenAt = System.currentTimeMillis(),
        )
        peers[hello.nodeId] = info
        peerConnected.tryEmit(info)
        Log.i(TAG, "peer ${hello.displayName} su ${link.transportLabel}")
    }

    private fun unregisterPeer(nodeId: String) {
        links.remove(nodeId)
        peers.remove(nodeId)
        peerLost.tryEmit(nodeId)
        refreshDiagnostics()
    }

    private suspend fun buildBeacons(): List<ByteArray> {
        val nodeBytes = localNodeId.hexToBytes()
        return rooms.values.filterNot { it.expired }.mapNotNull { room ->
            runCatching {
                Beacon.encode(
                    roomId = room.roomIdBytes,
                    nodeId = nodeBytes,
                    tcpPort = lanPort,
                    roomTag = room.beaconTag,
                    displayName = localDisplayName,
                )
            }.getOrNull()
        }
    }

    private suspend fun sendPings() {
        val now = System.currentTimeMillis()
        rooms.values.filterNot { it.expired }.forEach { room ->
            broadcast(Ping(roomId = room.roomIdHex, nodeId = localNodeId, sentAt = now))
        }
    }

    private fun refreshDiagnostics() {
        relayActive = publicRelayEnabled && relay.connected
        diagnostics = MeshDiagnostics(
            lanPort = lanPort,
            lanActive = lanEnabled && lanServer.port != 0,
            nearbyActive = nearbyEnabled,
            relayActive = relayActive,
            relayError = if (publicRelayEnabled) relay.lastError else null,
            connectedPeers = links.size,
            roomsAdvertised = rooms.values.count { !it.expired },
            framesSent = framesSent.get(),
            framesReceived = framesReceived.get(),
            framesRelayed = framesRelayed.get(),
            decryptFailures = decryptFailures.get(),
        )
    }

    fun connectedNodeIds(): Set<String> = links.nodeIds()

    /** Whether anything is reachable at all, so the outbox can skip a dead queue. */
    fun hasReachablePeer(): Boolean =
        links.size > 0 || nearbyEndpointsByRoom.values.any { it.isNotEmpty() } ||
            (publicRelayEnabled && relay.connected)

    /** Whether one specific room has anywhere to send right now. */
    fun hasReachablePeerIn(roomIdHex: String): Boolean {
        if (publicRelayEnabled && relay.connected && rooms.containsKey(roomIdHex)) return true
        if (links.all().any { (nodeId, _) -> peers[nodeId]?.roomIdHex == roomIdHex }) return true
        return nearbyEndpointsByRoom[roomIdHex]?.isNotEmpty() == true
    }

    fun peerInfo(nodeId: String): PeerInfo? = peers[nodeId]

    fun peerInfos(): List<PeerInfo> = peers.values.toList()

    fun safetyNumberFor(roomIdHex: String): String? =
        rooms[roomIdHex]?.let { RoomCrypto.safetyNumber(it.inviteSecret) }

    /** Room-level transfer key, or null when the room is unknown. */
    fun transferKey(roomIdHex: String): ByteArray? = rooms[roomIdHex]?.transferKey

    companion object {
        private const val TAG = "MeshEngine"
        private const val PING_INTERVAL_MILLIS = 20_000L
        /** Reported to peers in the handshake; follows the app version. */
        val APP_VERSION = dev.shutchat.app.BuildConfig.VERSION_NAME

        /** Placeholder sender for frames that name no author, such as a roster. */
        const val UNKNOWN_SENDER = "sconosciuto"
    }
}

/** [PeerLink] backed by a Nearby endpoint. */
private class NearbyPeerLink(
    private val endpointId: String,
    override val remoteNodeId: String,
    private val nearby: NearbyTransport,
) : PeerLink {

    override val transportLabel: String = "Bluetooth / Wi-Fi Direct"
    override val isOpen: Boolean get() = nearby.isConnected(endpointId)

    // Nearby delivers frames straight to the mesh via `incoming`, so a link that
    // is only used for sending does not need to re-expose them.
    override val incoming: kotlinx.coroutines.flow.Flow<ByteArray> =
        kotlinx.coroutines.flow.emptyFlow()

    override suspend fun send(frame: ByteArray): Boolean = nearby.send(endpointId, frame)

    override fun close() = Unit
}

/**
 * Identity used to collapse duplicate deliveries.
 *
 * Messages are keyed by their own id; control envelopes by type plus the fields
 * that make them distinct, which is enough because they are idempotent by design.
 */
internal fun Envelope.dedupeKey(): String = when (this) {
    is dev.shutchat.app.p2p.protocol.WireMessage -> "m:$messageId"
    is dev.shutchat.app.p2p.protocol.Receipt -> "r:$nodeId:${messageIds.joinToString(",")}"
    is dev.shutchat.app.p2p.protocol.Typing -> "t:$nodeId:$chatId:$typing"
    is dev.shutchat.app.p2p.protocol.ReactionUpdate -> "x:$messageId:$nodeId:$emoji:$removed"
    is dev.shutchat.app.p2p.protocol.EditMessage -> "e:$messageId:$editedAt"
    is dev.shutchat.app.p2p.protocol.DeleteMessage -> "d:$messageId"
    is dev.shutchat.app.p2p.protocol.Ping -> "p:$nodeId:$sentAt"
    is dev.shutchat.app.p2p.protocol.Pong -> "o:$nodeId:$sentAt"
    is dev.shutchat.app.p2p.protocol.JoinNotice -> "j:$nodeId"
    is dev.shutchat.app.p2p.protocol.LeaveNotice -> "l:$nodeId"
    is dev.shutchat.app.p2p.protocol.MemberRoster -> "ro:$chatId:${members.size}"
    is dev.shutchat.app.p2p.protocol.SyncRequest -> "s:$nodeId:$sinceMillis"
    is dev.shutchat.app.p2p.protocol.SyncResult -> "sr:$chatId:${messages.size}"
    is dev.shutchat.app.p2p.protocol.Hello -> "h:$nodeId"
    is dev.shutchat.app.p2p.protocol.HelloAck -> "ha:$nodeId"
    is dev.shutchat.app.p2p.protocol.TransferStart -> "ts:$transferId"
    is dev.shutchat.app.p2p.protocol.TransferComplete -> "tc:$transferId"
    is dev.shutchat.app.p2p.protocol.TransferCancel -> "tx:$transferId"
}

/**
 * The node that sent this envelope, when the envelope names one.
 *
 * Used to drop our own frames when they come back from the relay: a broker
 * echoes a publish to every subscriber including the publisher, so without this
 * every message would be received twice.
 *
 * @return the sender, or an empty string for envelopes that are about a room
 *         rather than a person — a roster or a sync result has no single author.
 */
internal fun Envelope.senderNodeId(): String = when (this) {
    is dev.shutchat.app.p2p.protocol.WireMessage -> senderNodeId
    is dev.shutchat.app.p2p.protocol.Hello -> nodeId
    is dev.shutchat.app.p2p.protocol.HelloAck -> nodeId
    is dev.shutchat.app.p2p.protocol.Receipt -> nodeId
    is dev.shutchat.app.p2p.protocol.Typing -> nodeId
    is dev.shutchat.app.p2p.protocol.ReactionUpdate -> nodeId
    is dev.shutchat.app.p2p.protocol.EditMessage -> nodeId
    is dev.shutchat.app.p2p.protocol.DeleteMessage -> nodeId
    is dev.shutchat.app.p2p.protocol.JoinNotice -> nodeId
    is dev.shutchat.app.p2p.protocol.LeaveNotice -> nodeId
    is dev.shutchat.app.p2p.protocol.SyncRequest -> nodeId
    is dev.shutchat.app.p2p.protocol.Ping -> nodeId
    is dev.shutchat.app.p2p.protocol.Pong -> nodeId
    else -> ""
}
