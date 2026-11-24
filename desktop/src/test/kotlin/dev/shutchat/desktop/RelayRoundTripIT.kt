package dev.shutchat.desktop

import dev.shutchat.app.crypto.RoomCrypto
import dev.shutchat.app.p2p.relay.RelayTransport
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * End-to-end over a real public broker.
 *
 * Opt-in: it needs internet and a public MQTT broker, so it is skipped unless
 * `-Dshutchat.it=1` is passed. It is the only way to prove that the QoS 1
 * handshake, the topic derivation and the frame plumbing agree with an actual
 * broker rather than with our own mock of one.
 */
class RelayRoundTripIT {

    @Test
    fun `a sealed frame crosses a public broker between two devices`() {
        assumeTrue("set -Dshutchat.it=1 to run the broker integration test", System.getProperty("shutchat.it") == "1")
        runBlocking { runRoundTrip() }
    }

    private suspend fun runRoundTrip() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val secret = ByteArray(15) { (it * 7 + 3).toByte() }
        val roomId = "room-" + UUID.randomUUID()
        val topic = RoomCrypto.relayTopic(secret)
        val payload = ByteArray(64) { it.toByte() }

        val sender = RelayTransport(scope, clientId = "shutchat-it-a-" + UUID.randomUUID())
        val receiver = RelayTransport(scope, clientId = "shutchat-it-b-" + UUID.randomUUID())
        try {
            sender.setTopics(mapOf(roomId to topic))
            receiver.setTopics(mapOf(roomId to topic))
            sender.start()
            receiver.start()

            withTimeout(60_000) {
                while (!sender.connected || !receiver.connected) delay(500)
            }
            // Give the subscriptions a moment to settle on the broker side.
            delay(1_000)

            val received = scope.async { receiver.frames.first() }
            var sent = false
            withTimeout(30_000) {
                while (!sent) {
                    sent = sender.send(roomId, payload)
                    if (!sent) delay(500)
                }
            }
            val frame = withTimeout(30_000) { received.await() }
            assertArrayEquals(payload, frame)
        } finally {
            sender.stop()
            receiver.stop()
            scope.cancel()
        }
    }
}
