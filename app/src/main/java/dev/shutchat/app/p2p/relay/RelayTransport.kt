package dev.shutchat.app.p2p.relay

import dev.shutchat.app.core.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Long-distance transport over a public MQTT broker.
 *
 * This is what makes ShutChat work when the two devices are not on the same
 * network. It is the one part of the app that depends on infrastructure nobody
 * here controls, so three things are true of it by design:
 *
 * 1. It is **on by default and can be switched off**. Without it the app only
 *    works between devices on the same network, which is not what a messenger is
 *    for; the settings screen states plainly what the broker is used for.
 * 2. It carries **only sealed frames**. The payload is the same AES-256-GCM blob
 *    that travels over Wi-Fi Direct, and the topic is derived from the invite
 *    secret, so the broker sees neither the text nor who is talking.
 * 3. It **fails soft**. If the broker is unreachable the app keeps working on
 *    LAN and Nearby, and the settings screen says the relay is down rather than
 *    pretending messages were sent.
 *
 * At-most-once is acceptable at this layer: the mesh stores anything it could not
 * hand to a peer, and the outbox re-sends it until the recipient acknowledges it.
 */
class RelayTransport(
    private val scope: CoroutineScope,
    private val host: String = MqttClient.DEFAULT_HOST,
    private val port: Int = MqttClient.DEFAULT_PORT,
    private val clientId: String,
) {

    /** Whole frames as they arrive from the broker. */
    val frames = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private var client: MqttClient? = null
    private var loop: kotlinx.coroutines.Job? = null

    /** Topic per room id, recomputed whenever the room set changes. */
    @Volatile
    private var topicsByRoom: Map<String, String> = emptyMap()

    @Volatile
    private var running = false

    /** True while a broker session is up and subscribed. */
    @Volatile
    var connected: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    fun start() {
        if (running) return
        running = true
        loop = scope.launch {
            var attempt = 0
            while (isActive && running) {
                val topics = topicsByRoom.values.toList()
                if (topics.isEmpty()) {
                    // Nothing to subscribe to yet; wait rather than connect blind.
                    delay(RETRY_MIN_MILLIS)
                    continue
                }
                val session = MqttClient(host, port, clientId)
                val ok = session.connect(scope, topics)
                if (ok) {
                    client = session
                    connected = true
                    lastError = null
                    attempt = 0
                    // Forward frames until the session ends. Awaiting the close
                    // signal rather than collecting [frames]: a shared flow never
                    // completes, so collecting it would block here forever and the
                    // reconnect below would never run.
                    val pump = scope.launch {
                        session.frames.collect { frame -> frames.tryEmit(frame) }
                    }
                    session.awaitClosed()
                    pump.cancel()
                    connected = false
                    if (running) lastError = "connessione al relay interrotta"
                } else {
                    lastError = session.lastError ?: "relay non raggiungibile"
                }
                session.close()
                client = null
                attempt++
                delay(backoff(attempt))
            }
        }
    }

    fun stop() {
        running = false
        connected = false
        loop?.cancel()
        loop = null
        client?.close()
        client = null
    }

    /**
     * Replaces the subscribed topic set.
     *
     * A change means the client has to reconnect, because MQTT has no way to
     * unsubscribe a topic it is no longer interested in without extra packets —
     * and reconnecting is simpler than tracking subscription diffs.
     */
    fun setTopics(topics: Map<String, String>) {
        if (topics == topicsByRoom) return
        topicsByRoom = topics
        if (running) {
            client?.close()
            connected = false
        }
    }

    /**
     * Publishes one frame to the topic of its room.
     *
     * @return true when it reached the broker. False is not an error the caller
     *         has to handle: the message is already marked QUEUED upstream.
     */
    suspend fun send(roomIdHex: String, frame: ByteArray): Boolean {
        val topic = topicsByRoom[roomIdHex] ?: return false
        val session = client ?: return false
        if (!connected) return false
        return session.publish(topic, frame)
    }

    /** Exponential backoff capped at [RETRY_MAX_MILLIS], so a dead broker is cheap. */
    private fun backoff(attempt: Int): Long {
        var delayMillis = RETRY_MIN_MILLIS
        repeat((attempt - 1).coerceAtMost(5)) { delayMillis *= 2 }
        return delayMillis.coerceAtMost(RETRY_MAX_MILLIS)
    }

    companion object {
        private const val TAG = "RelayTransport"
        private const val RETRY_MIN_MILLIS = 2_000L
        private const val RETRY_MAX_MILLIS = 60_000L
    }
}
