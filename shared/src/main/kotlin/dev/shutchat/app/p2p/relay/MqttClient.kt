package dev.shutchat.app.p2p.relay

import dev.shutchat.app.core.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A single MQTT session to a public broker, over TLS.
 *
 * Why a broker at all: two phones behind mobile carriers cannot discover each
 * other, because neither has a reachable address. Something has to be reachable,
 * and a broker is the smallest such thing. It relays opaque bytes and can read
 * nothing else — the frames are already sealed with the room key before they get
 * here, and the topic is derived from the invite secret.
 *
 * The client is deliberately thin: it subscribes to the topics it is given,
 * publishes frames to them, and reports what arrives. Reconnection is handled by
 * [RelayTransport], not here, so this class stays a socket plus a codec.
 */
class MqttClient(
    private val host: String,
    private val port: Int,
    private val clientId: String,
    private val keepAliveSeconds: Int = 30,
    /**
     * False keeps the broker-side session alive between connections.
     *
     * That is what makes a message sent to a sleeping phone arrive: the broker
     * holds the QoS 1 traffic for this client id and delivers it on reconnect.
     * It requires a client id that is stable across restarts, which is why the
     * caller passes one derived from the node id rather than a random UUID.
     */
    private val cleanSession: Boolean = false,
) {

    private val incoming = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Raw framed payloads, as received from every subscribed topic. */
    val frames: Flow<ByteArray> = incoming.asSharedFlow()

    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var readerJob: Job? = null
    private var keepAliveJob: Job? = null

    @Volatile
    private var subscribed = false

    @Volatile
    var lastError: String? = null
        private set

    /** True when the broker restored a session, i.e. it may have queued frames. */
    @Volatile
    var sessionPresent: Boolean = false
        private set

    /** Completed by the read loop with the broker's return code. */
    private var connAck: CompletableDeferred<Int>? = null

    private val packetIds = AtomicInteger(1)
    private val pendingAcks = ConcurrentHashMap<Int, CompletableDeferred<Boolean>>()

    @Volatile
    var connected: Boolean = false
        private set

    /**
     * Completes when the session ends, whether it was closed on purpose or the
     * socket died.
     *
     * [frames] is a shared flow and therefore never completes on its own, so a
     * caller waiting on it to finish would wait forever and never reconnect.
     * This is the signal to wait on instead.
     */
    private val closed = kotlinx.coroutines.CompletableDeferred<Unit>()

    /** Suspends until the session is over. */
    suspend fun awaitClosed() = closed.await()

    /**
     * Opens the socket, performs the MQTT handshake, then subscribes.
     *
     * @return true when the broker accepted the connection and the subscription.
     */
    suspend fun connect(scope: CoroutineScope, topics: List<String>): Boolean {
        return try {
            val plain = Socket()
            plain.tcpNoDelay = true
            plain.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS)

            // Upgrade to TLS before a single MQTT byte is written: credentials and
            // payloads must never travel in the clear.
            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(plain, host, port, true) as SSLSocket
            tls.startHandshake()

            socket = tls
            output = tls.getOutputStream()

            val ack = CompletableDeferred<Int>()
            connAck = ack
            readerJob = scope.launch(Dispatchers.IO) { readLoop(tls.getInputStream()) }
            keepAliveJob = scope.launch(Dispatchers.IO) { keepAliveLoop() }

            write(MqttCodec.connect(clientId, keepAliveSeconds, cleanSession))

            // Connected means the broker said so, not that the socket took the
            // bytes: a rejected session must not look like a live one.
            val returnCode = withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS.toLong()) { ack.await() }
            if (returnCode == null) {
                lastError = "the broker did not answer"
                close()
                return false
            }
            if (returnCode != 0) {
                lastError = "broker refused the connection (code $returnCode)"
                close()
                return false
            }
            connected = true
            lastError = null

            // Subscribing immediately is correct here: the broker queues our
            // SUBSCRIBE until after CONNACK, and a session that dies before it
            // gets one is torn down and retried by RelayTransport.
            // QoS 1 on the subscription is what lets the broker queue frames
            // for this device while it is offline.
            topics.forEachIndexed { index, topic ->
                write(MqttCodec.subscribe(packetId = index + 1, topic = topic, qos = 1))
            }
            subscribed = true
            true
        } catch (e: Exception) {
            lastError = e.message ?: "connection failed"
            Log.d(TAG, "connection to $host:$port failed: ${e.message}")
            close()
            false
        }
    }

    /**
     * Publishes one frame at QoS 1.
     *
     * The broker answers with a PUBACK; only then is the frame its
     * responsibility. Waiting for it (with one retry) turns "the socket took the
     * bytes" into "the broker holds the message", which is the difference
     * between a message that is lost when the network blinks and one that is not.
     */
    suspend fun publish(topic: String, payload: ByteArray): Boolean {
        if (!connected) return false
        repeat(PUBLISH_ATTEMPTS) { attempt ->
            val packetId = nextPacketId()
            val ack = CompletableDeferred<Boolean>()
            pendingAcks[packetId] = ack
            try {
                write(MqttCodec.publish(topic, payload, qos = 1, packetId = packetId))
            } catch (e: Exception) {
                pendingAcks.remove(packetId)
                lastError = e.message
                close()
                return false
            }
            val acknowledged = withTimeoutOrNull(PUBACK_TIMEOUT_MILLIS) { ack.await() } == true
            pendingAcks.remove(packetId)
            if (acknowledged) return true
            Log.d(TAG, "no PUBACK for packet $packetId (attempt ${attempt + 1})")
        }
        return false
    }

    private val writeLock = Any()

    private fun nextPacketId(): Int {
        // 16 bit ids, never zero: wraps around without colliding with the
        // subscription ids, which start at 1 and are few.
        while (true) {
            val candidate = packetIds.updateAndGet { current ->
                if (current >= 0xFFFF) 0x0100 else current + 1
            }
            if (!pendingAcks.containsKey(candidate)) return candidate
        }
    }

    fun close() {
        connected = false
        closed.complete(Unit)
        readerJob?.cancel()
        keepAliveJob?.cancel()
        readerJob = null
        keepAliveJob = null
        runCatching { output?.let { write(MqttCodec.disconnect()) } }
        runCatching { socket?.close() }
        socket = null
        output = null
    }

    @Synchronized
    private fun write(bytes: ByteArray) {
        val stream = output ?: throw IllegalStateException("socket closed")
        // One packet at a time: publish is called from several coroutines at
        // once when a file goes out in chunks, and interleaved bytes on the
        // socket are a malformed packet the broker drops without a word.
        synchronized(writeLock) {
            stream.write(bytes)
            stream.flush()
        }
    }

    private suspend fun readLoop(input: InputStream) {
        val buffer = ByteArray(16 * 1024)
        var pending = ByteArray(0)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                pending += buffer.copyOfRange(0, read)
                // A single read can carry several packets or half of one, so the
                // tail is kept for the next round.
                while (true) {
                    when (val result = MqttCodec.read(pending)) {
                        is MqttCodec.ReadResult.NeedMoreData -> break
                        is MqttCodec.ReadResult.Packet -> {
                            pending = pending.copyOfRange(result.consumed, pending.size)
                            onPacket(result.packet)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (connected) Log.d(TAG, "lettura interrotta: ${e.message}")
        } finally {
            connected = false
            // Wake whoever is waiting to reconnect: the socket is gone.
            closed.complete(Unit)
        }
    }

    private fun onPacket(packet: MqttCodec.Packet) {
        when (packet) {
            is MqttCodec.Packet.Publish -> {
                // Acknowledge before handing it up: at-least-once means the broker
                // may resend, and the mesh already deduplicates by envelope id.
                if (packet.qos > 0 && packet.packetId > 0) {
                    runCatching { write(MqttCodec.puback(packet.packetId)) }
                }
                // The topic is not needed above: every subscription belongs to a
                // room, and the frame already carries its own room id in the clear.
                if (packet.payload.isNotEmpty()) incoming.tryEmit(packet.payload)
            }

            is MqttCodec.Packet.PubAck -> {
                pendingAcks.remove(packet.packetId)?.complete(true)
            }

            is MqttCodec.Packet.ConnAck -> {
                sessionPresent = packet.sessionPresent
                connAck?.complete(packet.returnCode)
                if (packet.returnCode != 0) {
                    lastError = "broker refused the connection (code ${packet.returnCode})"
                    connected = false
                }
            }

            is MqttCodec.Packet.SubAck -> {
                if (packet.granted.any { it == 0x80 }) {
                    lastError = "iscrizione rifiutata dal broker"
                }
            }

            else -> Unit
        }
    }

    private suspend fun keepAliveLoop() {
        while (true) {
            delay((keepAliveSeconds * 1000L) / 2)
            if (!connected) break
            runCatching { write(MqttCodec.pingRequest()) }
        }
    }

    companion object {
        private const val TAG = "MqttClient"
        private const val CONNECT_TIMEOUT_MILLIS = 15_000
        private const val PUBACK_TIMEOUT_MILLIS = 6_000L
        private const val PUBLISH_ATTEMPTS = 2

        /**
         * Default broker.
         *
         * A public, open broker is used on purpose: it requires no account, so it
         * cannot link a room to a user, and it is reachable from anywhere. The
         * trade-off is that it is best-effort, which is why the UI states plainly
         * what it does and why the outbox re-sends until acknowledged.
         */
        const val DEFAULT_HOST = "broker.hivemq.com"
        const val DEFAULT_PORT = 8883
    }
}
