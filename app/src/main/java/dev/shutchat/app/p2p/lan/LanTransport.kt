package dev.shutchat.app.p2p.lan

import android.content.Context
import android.net.wifi.WifiManager
import dev.shutchat.app.core.Log
import dev.shutchat.app.p2p.PeerLink
import dev.shutchat.app.p2p.protocol.Framing
import dev.shutchat.app.p2p.protocol.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TCP link to one LAN peer.
 *
 * Frames are length-prefixed, so the reader has to reassemble them out of the
 * byte stream: a single read can return half a frame or three and a half frames.
 */
class LanLink(
    private val socket: Socket,
    override val remoteNodeId: String,
    override val transportLabel: String = "Rete locale",
) : PeerLink {

    private val events = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val closed = AtomicBoolean(false)
    private val reader = FrameReader(DataInputStream(socket.getInputStream()))
    private var pump: Job? = null

    override val incoming: Flow<ByteArray> = events.asSharedFlow()

    override val isOpen: Boolean get() = !closed.get() && !socket.isClosed

    fun start(scope: CoroutineScope) {
        pump = scope.launch(Dispatchers.IO) {
            try {
                while (isActive && isOpen) {
                    val frame = reader.readFrame() ?: break
                    if (!events.tryEmit(frame)) {
                        Log.d(TAG, "buffer pieno, frame scartato")
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "link terminato: ${e.message}")
            } finally {
                close()
            }
        }
    }

    override suspend fun send(frame: ByteArray): Boolean {
        if (!isOpen) return false
        return try {
            val out = socket.getOutputStream()
            synchronized(out) {
                out.write(frame)
                out.flush()
            }
            true
        } catch (e: IOException) {
            Log.d(TAG, "invio fallito: ${e.message}")
            close()
            false
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pump?.cancel()
        runCatching { socket.close() }
    }

    companion object {
        private const val TAG = "LanLink"

        suspend fun connect(host: String, port: Int, nodeId: String, timeoutMillis: Int = 4000): LanLink? =
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                try {
                    val socket = Socket().apply {
                        tcpNoDelay = true
                        soTimeout = 0
                        connect(InetSocketAddress(host, port), timeoutMillis)
                    }
                    LanLink(socket, nodeId)
                } catch (e: IOException) {
                    Log.d(TAG, "connessione a $host:$port fallita: ${e.message}")
                    null
                }
            }
    }
}

/**
 * Reassembles length-prefixed frames from a TCP byte stream.
 *
 * The header is five bytes, so the reader asks for exactly what it needs in two
 * steps instead of buffering blindly.
 */
class FrameReader(private val input: DataInputStream) {

    /** @return the next whole frame, or null at end of stream. */
    fun readFrame(): ByteArray? {
        val header = ByteArray(Framing.LENGTH_HEADER_SIZE)
        if (!readFully(header)) return null
        val length = Framing.readInt(header, 1)
        require(length in 0..Protocol.MAX_FRAME_BYTES) { "frame dichiarato troppo grande: $length" }
        if (length == 0) return header
        val payload = ByteArray(length)
        if (!readFully(payload)) return null
        return header + payload
    }

    private fun readFully(target: ByteArray): Boolean {
        var offset = 0
        while (offset < target.size) {
            val read = input.read(target, offset, target.size - offset)
            if (read < 0) return false
            offset += read
        }
        return true
    }
}

/** Accepts inbound LAN connections on a random port advertised in the beacon. */
class LanServer {

    val accepted = MutableSharedFlow<Socket>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    var port: Int = 0
        private set

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    fun start(scope: CoroutineScope): Int {
        if (serverSocket != null) return port
        val socket = ServerSocket(0).apply { reuseAddress = true }
        serverSocket = socket
        port = socket.localPort
        Log.i(TAG, "in ascolto sulla porta $port")

        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val client = socket.accept().apply {
                        tcpNoDelay = true
                        keepAlive = true
                    }
                    if (!accepted.tryEmit(client)) {
                        runCatching { client.close() }
                    }
                } catch (e: IOException) {
                    if (isActive) Log.d(TAG, "accept interrotto: ${e.message}")
                }
            }
        }
        return port
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        port = 0
    }

    private companion object {
        const val TAG = "LanServer"
    }
}

/**
 * Multicast discovery for LAN peers.
 *
 * Beacons go to a well known multicast group. Only devices holding an invite
 * code can produce a beacon that passes the room tag check, so the group carries
 * nothing a nearby listener could use to enumerate ShutChat users.
 */
class LanDiscovery(
    private val multicastGroup: String = "239.255.77.66",
    private val port: Int = 47766,
    private val beaconIntervalMillis: Long = 3000,
) {

    data class Discovery(
        val address: String,
        val tcpPort: Int,
        val roomIdHex: String,
        val nodeIdHex: String,
        val displayName: String,
        val roomTag: ByteArray,
    )

    val discoveries = MutableSharedFlow<Discovery>(extraBufferCapacity = 32)

    private var socket: MulticastSocket? = null
    private var listenJob: Job? = null
    private var broadcastJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    /** @param beacons invoked every [beaconIntervalMillis] to build outbound beacons. */
    fun start(scope: CoroutineScope, context: Context, beacons: suspend () -> List<ByteArray>) {
        if (socket != null) return

        // Android filters multicast traffic unless a lock is held; without it
        // discovery silently never receives anything on most devices.
        multicastLock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock("shutchat-lan")
            ?.apply {
                setReferenceCounted(false)
                runCatching { acquire() }
            }

        val multicast = try {
            MulticastSocket(port).apply {
                reuseAddress = true
                soTimeout = 1000
                runCatching { joinGroup(InetAddress.getByName(multicastGroup)) }
                    .onFailure { Log.w(TAG, "joinGroup non riuscito: ${it.message}") }
            }
        } catch (e: IOException) {
            Log.w(TAG, "socket multicast non disponibile: ${e.message}")
            return
        }
        socket = multicast

        listenJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(1024)
            while (isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    multicast.receive(packet)
                    val bytes = packet.data.copyOfRange(0, packet.length)
                    val decoded = dev.shutchat.app.p2p.Beacon.decode(bytes) ?: continue
                    discoveries.tryEmit(
                        Discovery(
                            address = packet.address.hostAddress ?: continue,
                            tcpPort = decoded.tcpPort,
                            roomIdHex = decoded.roomId.joinToString("") { "%02x".format(it) },
                            nodeIdHex = decoded.nodeIdHex,
                            displayName = decoded.displayName,
                            roomTag = decoded.roomTag,
                        ),
                    )
                } catch (e: java.net.SocketTimeoutException) {
                    // Expected: the timeout exists so the loop can notice cancellation.
                } catch (e: IOException) {
                    if (isActive) Log.d(TAG, "receive fallita: ${e.message}")
                }
            }
        }

        broadcastJob = scope.launch(Dispatchers.IO) {
            val group = runCatching { InetAddress.getByName(multicastGroup) }.getOrNull() ?: return@launch
            while (isActive) {
                runCatching {
                    beacons().forEach { payload ->
                        multicast.send(DatagramPacket(payload, payload.size, group, port))
                    }
                }.onFailure { Log.d(TAG, "beacon non inviato: ${it.message}") }
                delay(beaconIntervalMillis)
            }
        }
    }

    fun stop() {
        listenJob?.cancel()
        broadcastJob?.cancel()
        listenJob = null
        broadcastJob = null
        runCatching { socket?.close() }
        socket = null
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    private companion object {
        const val TAG = "LanDiscovery"
    }
}
