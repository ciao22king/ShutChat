package dev.shutchat.app.domain.model

/** The local device's own identity. Only [nodeId] is persistent. */
data class LocalProfile(
    val nodeId: String,
    val displayName: String,
    val avatarSeed: String,
    val status: String = "Disponibile",
    /** Room codes this device currently advertises itself for. */
    val activeRoomIds: Set<String> = emptySet(),
)

/** A peer discovered on a transport, before or after the handshake. */
data class PeerPresence(
    val nodeId: String,
    val displayName: String,
    val avatarSeed: String,
    val transport: String,
    val roomIds: Set<String>,
    val lastSeenAt: Long,
    val online: Boolean,
    val safetyNumber: String? = null,
)

/** How a device is reachable. */
enum class TransportKind(val label: String) {
    NEARBY("Bluetooth / Wi-Fi Direct"),
    LAN("Rete locale"),
}
