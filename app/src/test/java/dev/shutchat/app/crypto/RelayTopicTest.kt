package dev.shutchat.app.crypto

import dev.shutchat.app.core.hexToBytes
import dev.shutchat.app.core.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The relay topic is what keeps a public broker from becoming a directory of
 * ShutChat conversations, so its two properties are pinned here: it is stable for
 * everyone holding the code, and unrelated to the room id that travels in the
 * clear.
 */
class RelayTopicTest {

    private val secret = ByteArray(10) { (it + 1).toByte() }

    @Test
    fun `the same secret gives the same topic on every device`() {
        assertEquals(RoomCrypto.relayTopic(secret), RoomCrypto.relayTopic(secret.copyOf()))
    }

    @Test
    fun `different secrets give different topics`() {
        val other = ByteArray(10) { (it + 2).toByte() }
        assertNotEquals(RoomCrypto.relayTopic(secret), RoomCrypto.relayTopic(other))
    }

    @Test
    fun `the topic does not reveal the room id`() {
        // The room id is broadcast in the clear on every frame. If the topic were
        // derived from it, anyone watching the broker could correlate the two.
        val roomIdHex = RoomCrypto.roomId(secret).toHex()
        val topic = RoomCrypto.relayTopic(secret)
        assertFalse(topic.contains(roomIdHex))
    }

    @Test
    fun `the topic is namespaced and fixed length`() {
        val topic = RoomCrypto.relayTopic(secret)
        assertTrue(topic.startsWith("shutchat/v1/"))
        // 16 bytes of HKDF output as hex.
        assertEquals("shutchat/v1/".length + 32, topic.length)
    }

    @Test
    fun `the topic differs from every other derived value`() {
        // Domain separation: the relay label must not collide with the room id,
        // the beacon tag or a key, or one value would leak another.
        val topic = RoomCrypto.relayTopic(secret)
        assertFalse(topic.endsWith(RoomCrypto.roomId(secret).toHex()))
        assertFalse(topic.endsWith(RoomCrypto.beaconTag(secret).toHex()))
        assertFalse(topic.endsWith(RoomCrypto.messageKey(secret).toHex()))
    }

    @Test
    fun `a topic derived from a real invite secret is a valid mqtt topic`() {
        // MQTT topics are UTF-8 with no wildcards; this asserts the shape we
        // actually publish to, since a wildcard or a null byte would be rejected
        // by the broker.
        val topic = RoomCrypto.relayTopic("0123456789abcdef0123456789abcdef".hexToBytes())
        assertTrue(topic.all { it.isLetterOrDigit() || it == '/' || it == '-' || it == '_' })
        assertFalse(topic.contains('#'))
        assertFalse(topic.contains('+'))
    }
}
