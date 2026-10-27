package dev.shutchat.app.p2p.nearby

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import dev.shutchat.app.core.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Wrapper over Google's Nearby Connections.
 *
 * This is what lets ShutChat work with no network at all: the API chooses
 * Bluetooth Classic, BLE or Wi-Fi Direct by itself and needs no router. Because
 * the choice is opaque, the UI labels this transport generically as
 * "Bluetooth / Wi-Fi Direct".
 *
 * The service id is derived from the room id, so two devices only discover each
 * other when they hold the same invite code — discovery cannot be used to
 * enumerate ShutChat users, and does not need location permission on Android 12+.
 */
class NearbyTransport(private val context: Context) {

    private val client: ConnectionsClient = Nearby.getConnectionsClient(context)

    val incoming = MutableSharedFlow<Incoming>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val endpoints = MutableSharedFlow<EndpointEvent>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    sealed interface Incoming {
        data class Frame(val endpointId: String, val bytes: ByteArray) : Incoming
        data class Connected(val endpointId: String) : Incoming
        data class Disconnected(val endpointId: String) : Incoming
    }

    sealed interface EndpointEvent {
        data class Found(val endpointId: String, val roomIdHex: String) : EndpointEvent
        data class Lost(val endpointId: String) : EndpointEvent
    }

    /** Nearby endpoint id → room it was discovered for. */
    private val endpointRooms = ConcurrentHashMap<String, String>()

    /** Nearby endpoint id → ShutChat node id, learned from the hello. */
    private val endpointNodes = ConcurrentHashMap<String, String>()

    private val connected = ConcurrentHashMap.newKeySet<String>()

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) {
                // ShutChat chunks files itself so transfers can resume; a stray
                // stream/file payload is ignored rather than half-handled.
                Log.d(TAG, "payload non BYTES ignorato")
                return
            }
            payload.asBytes()?.let { incoming.tryEmit(Incoming.Frame(endpointId, it)) }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Only accept endpoints we discovered for one of our rooms.
            if (endpointRooms[endpointId] == null) {
                Log.w(TAG, "connessione da endpoint non richiesto")
                client.rejectConnection(endpointId)
                return
            }
            client.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { Log.w(TAG, "acceptConnection fallita", it) }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            if (resolution.status.isSuccess) {
                connected.add(endpointId)
                incoming.tryEmit(Incoming.Connected(endpointId))
            } else {
                Log.d(TAG, "connessione rifiutata: ${resolution.status.statusMessage}")
                endpointRooms.remove(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            connected.remove(endpointId)
            endpointRooms.remove(endpointId)
            endpointNodes.remove(endpointId)
            incoming.tryEmit(Incoming.Disconnected(endpointId))
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val roomId = roomIdForServiceId(info.serviceId)
            if (roomId != null) endpointRooms[endpointId] = roomId
            endpoints.tryEmit(EndpointEvent.Found(endpointId, roomId ?: ""))
        }

        override fun onEndpointLost(endpointId: String) {
            endpoints.tryEmit(EndpointEvent.Lost(endpointId))
        }
    }

    /** One advertisement per room currently open on this device. */
    fun advertise(roomIds: Collection<String>, name: String) {
        roomIds.forEach { roomId ->
            client.startAdvertising(
                serviceIdFor(roomId),
                name,
                connectionCallback,
                AdvertisingOptions.Builder().setStrategy(STRATEGY).build(),
            ).addOnFailureListener { Log.w(TAG, "startAdvertising fallita per $roomId", it) }
        }
    }

    fun discover(roomIds: Collection<String>) {
        roomIds.forEach { roomId ->
            client.startDiscovery(
                serviceIdFor(roomId),
                discoveryCallback,
                DiscoveryOptions.Builder().setStrategy(STRATEGY).build(),
            ).addOnFailureListener { Log.w(TAG, "startDiscovery fallita per $roomId", it) }
        }
    }

    fun connect(endpointId: String) {
        val serviceId = endpointRooms[endpointId] ?: return
        client.requestConnection(serviceId, endpointId, connectionCallback)
            .addOnFailureListener { Log.w(TAG, "requestConnection fallita", it) }
    }

    fun send(endpointId: String, frame: ByteArray): Boolean = try {
        client.sendPayload(endpointId, Payload.fromBytes(frame))
        true
    } catch (e: Exception) {
        Log.d(TAG, "sendPayload fallito: ${e.message}")
        false
    }

    fun bindNode(endpointId: String, nodeId: String) {
        endpointNodes[endpointId] = nodeId
    }

    fun nodeIdFor(endpointId: String): String? = endpointNodes[endpointId]

    fun isConnected(endpointId: String): Boolean = connected.contains(endpointId)

    fun connectedEndpoints(): Set<String> = connected.toSet()

    fun stop() {
        runCatching { client.stopAdvertising() }
        runCatching { client.stopDiscovery() }
        runCatching { client.stopAllEndpoints() }
        endpointRooms.clear()
        endpointNodes.clear()
        connected.clear()
    }

    companion object {
        private const val TAG = "NearbyTransport"

        // P2P_STAR keeps every member directly connected, which is what a group
        // chat needs: there is no elected hub to lose.
        private val STRATEGY = Strategy.P2P_STAR

        /** Nearby service ids may only contain [0-9a-zA-Z_-]. */
        fun serviceIdFor(roomIdHex: String): String = "shutchat-r-$roomIdHex"

        /** Inverse of [serviceIdFor]; null when the id belongs to another app. */
        fun roomIdForServiceId(serviceId: String): String? =
            serviceId.takeIf { it.startsWith("shutchat-r-") }?.removePrefix("shutchat-r-")
    }
}
