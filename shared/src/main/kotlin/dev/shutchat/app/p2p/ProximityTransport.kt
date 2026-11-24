package dev.shutchat.app.p2p

import kotlinx.coroutines.flow.Flow

/**
 * Events a proximity transport reports to the mesh.
 *
 * Bluetooth and Wi-Fi Direct have no notion of an address or a port: the
 * platform hands out opaque endpoint ids, so the mesh learns who is behind one
 * from the hello frame rather than from the transport.
 */
sealed interface ProximityEvent {

    sealed interface Incoming : ProximityEvent {
        data class Frame(val endpointId: String, val bytes: ByteArray) : Incoming
        data class Connected(val endpointId: String) : Incoming
        data class Disconnected(val endpointId: String) : Incoming
    }

    sealed interface Endpoint : ProximityEvent {
        data class Found(val endpointId: String, val roomIdHex: String) : Endpoint
        data class Lost(val endpointId: String) : Endpoint
    }
}

/**
 * Transport that reaches devices that are physically close but share no network.
 *
 * The mesh only ever needs these operations, so the Android implementation
 * (Nearby Connections) stays swappable and the desktop client can run without
 * one at all.
 */
interface ProximityTransport {

    val incoming: Flow<ProximityEvent.Incoming>
    val endpoints: Flow<ProximityEvent.Endpoint>

    fun advertise(roomIds: Collection<String>, name: String)

    fun discover(roomIds: Collection<String>)

    fun connect(endpointId: String)

    fun send(endpointId: String, frame: ByteArray): Boolean

    fun bindNode(endpointId: String, nodeId: String)

    fun nodeIdFor(endpointId: String): String?

    fun isConnected(endpointId: String): Boolean

    fun connectedEndpoints(): Set<String>

    fun stop()
}
