package dev.shutchat.app.p2p

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A bidirectional pipe to one peer, whatever the transport underneath.
 *
 * LAN links wrap a TCP socket and reassemble length-prefixed frames out of the
 * byte stream; Nearby links wrap `Payload` callbacks, which already arrive
 * message-framed. Both surface whole frames here, so the mesh above never has to
 * know which one it is holding.
 */
interface PeerLink {
    /** Remote node id. Known as soon as the link is created, from the hello. */
    val remoteNodeId: String
    val transportLabel: String
    val isOpen: Boolean

    /** Whole frames as produced by [dev.shutchat.app.p2p.protocol.Framing]. */
    val incoming: Flow<ByteArray>

    suspend fun send(frame: ByteArray): Boolean

    fun close()
}

/** Thread safe registry of live links keyed by node id. */
class LinkRegistry {

    private val links = java.util.concurrent.ConcurrentHashMap<String, PeerLink>()

    fun put(nodeId: String, link: PeerLink): PeerLink? = links.put(nodeId, link)

    fun get(nodeId: String): PeerLink? = links[nodeId]

    fun remove(nodeId: String): PeerLink? = links.remove(nodeId)

    fun removeIfSame(nodeId: String, link: PeerLink) {
        links.remove(nodeId, link)
    }

    fun all(): List<Pair<String, PeerLink>> = links.entries.map { it.key to it.value }

    fun nodeIds(): Set<String> = links.keys.toSet()

    fun clear() {
        links.values.forEach { runCatching { it.close() } }
        links.clear()
    }

    val size: Int get() = links.size
}

/** Event bus used by transports to publish link-level activity to the mesh. */
class LinkEventBus<T>(capacity: Int = 64) {
    private val flow = MutableSharedFlow<T>(
        replay = 0,
        extraBufferCapacity = capacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: Flow<T> = flow.asSharedFlow()

    fun tryEmit(value: T): Boolean = flow.tryEmit(value)

    suspend fun emit(value: T) = flow.emit(value)
}

/**
 * Beacon broadcast by a device willing to receive traffic for a room.
 *
 * ```
 * magic "SCB1" (4) | roomId (16) | nodeId (16) | tcpPort (2) | roomTag (8) | nameLen (1) | name
 * ```
 *
 * `roomTag` is derived from the invite secret, so a device without the code
 * cannot produce a beacon that a participant will accept. Rooms therefore stay
 * invisible to passers-by even though the discovery channel itself is open.
 */
object Beacon {

    val MAGIC = byteArrayOf('S'.code.toByte(), 'C'.code.toByte(), 'B'.code.toByte(), 1)
    const val FIXED_SIZE = 4 + 16 + 16 + 2 + 8 + 1
    const val MAX_NAME_BYTES = 64

    fun encode(
        roomId: ByteArray,
        nodeId: ByteArray,
        tcpPort: Int,
        roomTag: ByteArray,
        displayName: String,
    ): ByteArray {
        require(roomId.size == 16 && nodeId.size == 16 && roomTag.size >= 8)
        val name = displayName.toByteArray().take(MAX_NAME_BYTES).toByteArray()
        val out = ByteArray(FIXED_SIZE + name.size)
        MAGIC.copyInto(out, 0)
        roomId.copyInto(out, 4)
        nodeId.copyInto(out, 20)
        out[36] = ((tcpPort ushr 8) and 0xFF).toByte()
        out[37] = (tcpPort and 0xFF).toByte()
        roomTag.copyInto(out, 38, 0, 8)
        out[46] = name.size.toByte()
        name.copyInto(out, 47)
        return out
    }

    data class Decoded(
        val roomId: ByteArray,
        val nodeIdHex: String,
        val tcpPort: Int,
        val roomTag: ByteArray,
        val displayName: String,
    )

    fun decode(bytes: ByteArray): Decoded? {
        if (bytes.size < FIXED_SIZE) return null
        for (i in MAGIC.indices) if (bytes[i] != MAGIC[i]) return null
        val nameLength = bytes[46].toInt() and 0xFF
        if (bytes.size < FIXED_SIZE + nameLength) return null
        return Decoded(
            roomId = bytes.copyOfRange(4, 20),
            nodeIdHex = bytes.copyOfRange(20, 36).joinToString("") { "%02x".format(it) },
            tcpPort = ((bytes[36].toInt() and 0xFF) shl 8) or (bytes[37].toInt() and 0xFF),
            roomTag = bytes.copyOfRange(38, 46),
            displayName = String(bytes, 47, nameLength),
        )
    }
}
