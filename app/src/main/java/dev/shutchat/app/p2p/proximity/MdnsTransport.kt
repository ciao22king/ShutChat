package dev.shutchat.app.p2p.proximity

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import dev.shutchat.app.core.Log
import dev.shutchat.app.p2p.ProximityEvent
import dev.shutchat.app.p2p.ProximityTransport
import dev.shutchat.app.p2p.protocol.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Device-to-device transport built only on the Android framework.
 *
 * Discovery is mDNS through [NsdManager] and the pipe is a plain TCP socket, so
 * this needs no Google Play Services: it runs on any AOSP build, and on a
 * de-Googled phone it is the only proximity path the app has.
 *
 * It replaces Nearby Connections, with one difference worth stating plainly:
 * Nearby pairs two phones with no network at all (Bluetooth, Wi-Fi Direct),
 * while mDNS needs both devices on the same Wi-Fi. Chat on a shared network,
 * which the LAN transport already handles, gains a second discovery channel
 * that keeps working on access points that filter multicast.
 *
 * Rooms stay invisible to non-participants: the room id travels as a TXT
 * attribute and only peers that already hold the same invite code act on it.
 */
class MdnsTransport(
    private val context: Context,
    private val scope: CoroutineScope,
) : ProximityTransport {

    override val incoming = MutableSharedFlow<ProximityEvent.Incoming>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val endpoints = MutableSharedFlow<ProximityEvent.Endpoint>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val nsd: NsdManager? =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager

    /** Endpoint id → open socket. */
    private val links = ConcurrentHashMap<String, Socket>()

    /** Endpoint id → room it was discovered for. */
    private val endpointRooms = ConcurrentHashMap<String, String>()

    /** Endpoint id → ShutChat node id, learned from the hello. */
    private val endpointNodes = ConcurrentHashMap<String, String>()

    /** Room id → registration currently held with the system resolver. */
    private val registrations = ConcurrentHashMap<String, NsdServiceInfo>()

    /** Host → advertised port, so an inbound socket reuses the discovered id. */
    private val portsByHost = ConcurrentHashMap<String, Int>()

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var discovering = false

    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private val resolving = AtomicBoolean(false)

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {
            Log.d(TAG, "registered ${info.serviceName}")
        }

        override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "registration failed for ${info.serviceName}: $errorCode")
        }

        override fun onServiceUnregistered(info: NsdServiceInfo) = Unit

        override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
            Log.d(TAG, "unregistration failed: $errorCode")
        }
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            Log.d(TAG, "discovery started")
        }

        override fun onServiceFound(info: NsdServiceInfo) {
            // Resolving is what reveals the address, and the resolver accepts
            // one request at a time: queue them and drain one by one.
            synchronized(resolveQueue) { resolveQueue.addLast(info) }
            drainResolveQueue()
        }

        override fun onServiceLost(info: NsdServiceInfo) {
            val host = info.host?.hostAddress ?: return
            val id = endpointId(host, portsByHost[host] ?: info.port)
            endpoints.tryEmit(ProximityEvent.Endpoint.Lost(id))
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.d(TAG, "discovery stopped")
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "startDiscovery failed: $errorCode")
            discovering = false
            runCatching { nsd?.stopServiceDiscovery(this) }
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.d(TAG, "stopDiscovery failed: $errorCode")
        }
    }

    override fun advertise(roomIds: Collection<String>, name: String) {
        val manager = nsd ?: return run { Log.w(TAG, "no NsdManager on this device") }
        val port = listenPort()
        if (port == 0) return

        // Rooms that closed lose their registration, new ones gain one.
        registrations.keys.filterNot { it in roomIds }.forEach { roomId ->
            registrations.remove(roomId)?.let { info ->
                runCatching { manager.unregisterService(registrationListener) }
                Log.d(TAG, "unregistered ${info.serviceName}")
            }
        }
        roomIds.forEach { roomId ->
            if (registrations.containsKey(roomId)) return@forEach
            val info = NsdServiceInfo().apply {
                serviceName = "shutchat-${roomId.take(8)}"
                serviceType = SERVICE_TYPE
                this.port = port
                setAttribute(ATTR_ROOM, roomId)
                setAttribute(ATTR_NAME, name.ifBlank { "ShutChat" })
            }
            registrations[roomId] = info
            runCatching {
                manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
            }.onFailure {
                registrations.remove(roomId)
                Log.w(TAG, "registerService failed: ${it.message}")
            }
        }
    }

    override fun discover(roomIds: Collection<String>) {
        val manager = nsd ?: return
        if (discovering || roomIds.isEmpty()) return
        discovering = true
        runCatching {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }.onFailure {
            discovering = false
            Log.w(TAG, "discoverServices failed: ${it.message}")
        }
    }

    override fun connect(endpointId: String) {
        val target = split(endpointId) ?: return
        if (links[endpointId]?.isConnected == true) return
        scope.launch(Dispatchers.IO) {
            try {
                val socket = Socket().apply {
                    tcpNoDelay = true
                    keepAlive = true
                    connect(InetSocketAddress(target.first, target.second), CONNECT_TIMEOUT_MS)
                }
                attach(endpointId, socket)
            } catch (e: IOException) {
                Log.d(TAG, "connect to $endpointId failed: ${e.message}")
            }
        }
    }

    override fun send(endpointId: String, frame: ByteArray): Boolean {
        val socket = links[endpointId] ?: return false
        if (frame.size > Protocol.MAX_FRAME_BYTES) return false
        return try {
            synchronized(socket) {
                val out = DataOutputStream(socket.getOutputStream())
                out.writeInt(frame.size)
                out.write(frame)
                out.flush()
            }
            true
        } catch (e: IOException) {
            Log.d(TAG, "send to $endpointId failed: ${e.message}")
            drop(endpointId)
            false
        }
    }

    override fun bindNode(endpointId: String, nodeId: String) {
        endpointNodes[endpointId] = nodeId
    }

    override fun nodeIdFor(endpointId: String): String? = endpointNodes[endpointId]

    override fun isConnected(endpointId: String): Boolean = links[endpointId]?.isConnected == true

    override fun connectedEndpoints(): Set<String> = links.filterValues { it.isConnected }.keys

    override fun stop() {
        runCatching { nsd?.stopServiceDiscovery(discoveryListener) }
        discovering = false
        registrations.keys.toList().forEach { roomId ->
            registrations.remove(roomId)
            runCatching { nsd?.unregisterService(registrationListener) }
        }
        links.keys.toList().forEach { drop(it) }
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        endpointRooms.clear()
        endpointNodes.clear()
        portsByHost.clear()
    }

    /** Starts the listener once and returns its port. */
    private fun listenPort(): Int {
        val existing = serverSocket
        if (existing != null) return existing.localPort
        val socket = try {
            ServerSocket(0).apply { reuseAddress = true }
        } catch (e: IOException) {
            Log.w(TAG, "no listening socket: ${e.message}")
            return 0
        }
        serverSocket = socket
        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    if (isActive) Log.d(TAG, "accept interrupted: ${e.message}")
                    break
                }
                client.tcpNoDelay = true
                client.keepAlive = true
                val host = client.inetAddress?.hostAddress ?: continue
                attach(endpointId(host, portsByHost[host] ?: client.port), client)
            }
        }
        return socket.localPort
    }

    private fun attach(endpointId: String, socket: Socket) {
        val previous = links.put(endpointId, socket)
        // Both sides can dial at once; the newer socket wins and the older one
        // is closed so the mesh sees a single pipe per endpoint.
        if (previous != null && previous !== socket) runCatching { previous.close() }
        incoming.tryEmit(ProximityEvent.Incoming.Connected(endpointId))
        scope.launch(Dispatchers.IO) { readLoop(endpointId, socket) }
    }

    private fun readLoop(endpointId: String, socket: Socket) {
        try {
            val input = DataInputStream(socket.getInputStream())
            while (!socket.isClosed) {
                val length = input.readInt()
                if (length <= 0 || length > Protocol.MAX_FRAME_BYTES) {
                    Log.w(TAG, "bad frame length $length from $endpointId")
                    break
                }
                val frame = ByteArray(length)
                input.readFully(frame)
                incoming.tryEmit(ProximityEvent.Incoming.Frame(endpointId, frame))
            }
        } catch (e: IOException) {
            Log.d(TAG, "link to $endpointId closed: ${e.message}")
        } finally {
            drop(endpointId, notify = true)
        }
    }

    private fun drop(endpointId: String, notify: Boolean = false) {
        val socket = links.remove(endpointId)
        runCatching { socket?.close() }
        if (notify) {
            endpointRooms.remove(endpointId)
            incoming.tryEmit(ProximityEvent.Incoming.Disconnected(endpointId))
        }
    }

    private fun drainResolveQueue() {
        val manager = nsd ?: return
        if (!resolving.compareAndSet(false, true)) return
        val next = synchronized(resolveQueue) { resolveQueue.removeFirstOrNull() }
        if (next == null) {
            resolving.set(false)
            return
        }
        runCatching {
            manager.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.d(TAG, "resolve failed: $errorCode")
                    resolving.set(false)
                    drainResolveQueue()
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    onResolved(info)
                    resolving.set(false)
                    drainResolveQueue()
                }
            })
        }.onFailure {
            resolving.set(false)
            Log.d(TAG, "resolveService rejected: ${it.message}")
        }
    }

    private fun onResolved(info: NsdServiceInfo) {
        val roomId = info.attributes[ATTR_ROOM]?.toString(Charsets.UTF_8) ?: return
        // Only rooms this device holds are interesting; anything else on the
        // network stays invisible.
        if (!registrations.containsKey(roomId)) return
        val host = info.host?.hostAddress ?: return
        if (host.isBlank()) return
        portsByHost[host] = info.port
        val id = endpointId(host, info.port)
        endpointRooms[id] = roomId
        endpoints.tryEmit(ProximityEvent.Endpoint.Found(id, roomId))
    }

    companion object {
        private const val TAG = "MdnsTransport"

        /** One service type for the whole app: services are told apart by TXT. */
        const val SERVICE_TYPE = "_shutchat._tcp"

        const val ATTR_ROOM = "room"
        const val ATTR_NAME = "name"

        private const val CONNECT_TIMEOUT_MS = 4000

        fun endpointId(host: String, port: Int): String = "mdns:$host:$port"

        fun split(endpointId: String): Pair<String, Int>? {
            val body = endpointId.removePrefix("mdns:")
            val separator = body.lastIndexOf(':')
            if (separator <= 0) return null
            val port = body.substring(separator + 1).toIntOrNull() ?: return null
            return body.substring(0, separator) to port
        }
    }
}
