package dev.shutchat.app.p2p

import dev.shutchat.app.core.hexToBytes
import dev.shutchat.app.crypto.RoomCrypto
import dev.shutchat.app.p2p.protocol.Envelope

/**
 * A room this device participates in, as the transport layer needs to see it.
 *
 * [inviteSecret] is the rendezvous secret from the invite code. The room id and
 * both encryption keys are derived from it once, lazily, because every send path
 * needs them and HKDF is not free.
 */
class RoomSpec(
    val chatId: String,
    val roomIdHex: String,
    val inviteSecret: ByteArray,
    val displayName: String,
    val nodeId: String,
    /** True when the code has expired: readable locally, no longer advertised. */
    val expired: Boolean = false,
) {
    val roomIdBytes: ByteArray by lazy { roomIdHex.hexToBytes() }
    val messageKey: ByteArray by lazy { RoomCrypto.messageKey(inviteSecret) }
    val transferKey: ByteArray by lazy { RoomCrypto.transferKey(inviteSecret) }
    val beaconTag: ByteArray by lazy { RoomCrypto.beaconTag(inviteSecret) }

    override fun equals(other: Any?): Boolean =
        other is RoomSpec && other.chatId == chatId && other.roomIdHex == roomIdHex

    override fun hashCode(): Int = chatId.hashCode() * 31 + roomIdHex.hashCode()

    override fun toString(): String = "RoomSpec(chatId=$chatId, room=$roomIdHex, expired=$expired)"
}

/** A peer with a live, authenticated link. */
data class PeerInfo(
    val nodeId: String,
    val displayName: String,
    val roomIdHex: String,
    val transport: String,
    val lastSeenAt: Long,
) {
    val shortId: String get() = nodeId.take(6)
}

/** An envelope that survived decryption, plus who sent it and how. */
data class ReceivedEnvelope(
    val envelope: Envelope,
    val fromNodeId: String,
    val fromDisplayName: String,
    val transport: String,
)

data class MeshDiagnostics(
    val lanPort: Int = 0,
    val lanActive: Boolean = false,
    val nearbyActive: Boolean = false,
    /** True when the public relay is enabled and a broker session is up. */
    val relayActive: Boolean = false,
    /** Why the relay is not up, when it is not. Null means no problem to report. */
    val relayError: String? = null,
    val connectedPeers: Int = 0,
    val roomsAdvertised: Int = 0,
    val framesSent: Long = 0,
    val framesReceived: Long = 0,
    val framesRelayed: Long = 0,
    val decryptFailures: Long = 0,
    val lastError: String? = null,
)

/**
 * Deduplicates envelopes that arrive over more than one path.
 *
 * In a mesh a message can reach a device directly *and* through a relay, and the
 * same envelope can be retried after a reconnect. An LRU of recently seen keys
 * keeps the conversation showing each message exactly once.
 */
class SeenCache(private val capacity: Int = 1024) {
    private val order = ArrayDeque<String>()
    private val seen = HashSet<String>()

    /** @return true when [key] had not been seen before. */
    @Synchronized
    fun markSeen(key: String): Boolean {
        if (seen.contains(key)) return false
        seen.add(key)
        order.addLast(key)
        while (order.size > capacity) {
            seen.remove(order.removeFirst())
        }
        return true
    }

    @Synchronized
    fun contains(key: String): Boolean = seen.contains(key)

    @Synchronized
    fun clear() {
        seen.clear()
        order.clear()
    }

    @Synchronized
    fun size(): Int = seen.size
}
