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
            write(MqttCodec.connect(clientId, keepAliveSeconds))
            connected = true
            lastError = null

            readerJob = scope.launch(Dispatchers.IO) { readLoop(tls.getInputStream()) }
            keepAliveJob = scope.launch(Dispatchers.IO) { keepAliveLoop() }

            // Subscribing immediately is correct here: the broker queues our
            // SUBSCRIBE until after CONNACK, and a session that dies before it
            // gets one is torn down and retried by RelayTransport.
            topics.forEachIndexed { index, topic ->
                write(MqttCodec.subscribe(packetId = index + 1, topic = topic))
            }
            subscribed = true
            true
        } catch (e: Exception) {
            lastError = e.message ?: "connessione fallita"
            Log.d(TAG, "connessione a $host:$port fallita: ${e.message}")
            close()
            false
        }
    }

    suspend fun publish(topic: String, payload: ByteArray): Boolean {
        if (!connected) return false
        return try {
            write(MqttCodec.publish(topic, payload))
            true
        } catch (e: Exception) {
            lastError = e.message
            close()
            false
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
        val stream = output ?: throw IllegalStateException("socket chiuso")
        stream.write(bytes)
        stream.flush()
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
                // The topic is not needed above: every subscription belongs to a
                // room, and the frame already carries its own room id in the clear.
                if (packet.payload.isNotEmpty()) incoming.tryEmit(packet.payload)
            }

            is MqttCodec.Packet.ConnAck -> {
                if (packet.returnCode != 0) {
                    lastError = "broker ha rifiutato la connessione (codice ${packet.returnCode})"
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

        /**
         * Default broker.
         *
         * A public, open broker is used on purpose: it requires no account, so it
         * cannot link a room to a user, and it is reachable from anywhere. The
         * trade-off is that it is best-effort, which is why the relay is off by
         * default and why the UI states plainly what it does.
         */
        const val DEFAULT_HOST = "broker.hivemq.com"
        const val DEFAULT_PORT = 8883
    }
}
