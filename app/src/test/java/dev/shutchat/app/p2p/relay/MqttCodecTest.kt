package dev.shutchat.app.p2p.relay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MQTT codec is the part of the relay that can be wrong in a way that only
 * shows up against a real broker, which is exactly the situation this environment
 * cannot reproduce. These tests pin the wire format against the byte sequences in
 * the MQTT 3.1.1 specification, so the framing is verified even though the
 * connection is not.
 */
class MqttCodecTest {

    // ------------------------------------------------------------ remaining length

    @Test
    fun `remaining length is a base 128 varint`() {
        assertArrayEquals(byteArrayOf(0), MqttCodec.remainingLength(0))
        assertArrayEquals(byteArrayOf(127), MqttCodec.remainingLength(127))
        // 128 is the first value that needs a continuation byte.
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x01), MqttCodec.remainingLength(128))
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0x7F), MqttCodec.remainingLength(16_383))
        assertArrayEquals(
            byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x01),
            MqttCodec.remainingLength(16_384),
        )
    }

    // ------------------------------------------------------------------ connect

    @Test
    fun `connect carries the protocol name and level`() {
        val packet = MqttCodec.connect("shutchat-test", 30)
        assertEquals(MqttCodec.TYPE_CONNECT, (packet[0].toInt() and 0xF0) ushr 4)
        // Protocol name is the literal "MQTT" with a two byte length prefix.
        assertEquals(0, packet[2].toInt())
        assertEquals(4, packet[3].toInt())
        assertEquals("MQTT", String(packet, 4, 4))
        assertEquals(4, packet[8].toInt()) // protocol level 4 == MQTT 3.1.1
    }

    // ---------------------------------------------------------------- subscribe

    @Test
    fun `subscribe sets the reserved flag bits`() {
        val packet = MqttCodec.subscribe(packetId = 7, topic = "shutchat/v1/abc")
        assertEquals(MqttCodec.TYPE_SUBSCRIBE, (packet[0].toInt() and 0xF0) ushr 4)
        // The low nibble of a SUBSCRIBE fixed header is fixed at 0b0010.
        assertEquals(0x02, packet[0].toInt() and 0x0F)
    }

    // ------------------------------------------------------------------ publish

    @Test
    fun `a published frame round trips through the decoder`() {
        val frame = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val topic = "shutchat/v1/deadbeef"
        val packet = MqttCodec.publish(topic, frame)

        val result = MqttCodec.read(packet)
        assertTrue(result is MqttCodec.ReadResult.Packet)
        val decoded = (result as MqttCodec.ReadResult.Packet).packet
        assertTrue(decoded is MqttCodec.Packet.Publish)
        decoded as MqttCodec.Packet.Publish
        assertEquals(topic, decoded.topic)
        assertArrayEquals(frame, decoded.payload)
        assertEquals(packet.size, result.consumed)
    }

    @Test
    fun `qos 0 publish has no packet id`() {
        val packet = MqttCodec.publish("t", byteArrayOf(9))
        assertEquals(0, packet[0].toInt() and 0x06)
    }

    // ------------------------------------------------------------------ decoding

    @Test
    fun `connack is decoded with its return code`() {
        val packet = byteArrayOf(
            (MqttCodec.TYPE_CONNACK shl 4).toByte(),
            2,
            0,
            0,
        )
        val result = MqttCodec.read(packet) as MqttCodec.ReadResult.Packet
        val connAck = result.packet as MqttCodec.Packet.ConnAck
        assertEquals(0, connAck.returnCode)
        assertEquals(false, connAck.sessionPresent)
    }

    @Test
    fun `a refused connack keeps its nonzero return code`() {
        val packet = byteArrayOf((MqttCodec.TYPE_CONNACK shl 4).toByte(), 2, 0, 5)
        val result = MqttCodec.read(packet) as MqttCodec.ReadResult.Packet
        assertEquals(5, (result.packet as MqttCodec.Packet.ConnAck).returnCode)
    }

    @Test
    fun `suback is decoded with its granted codes`() {
        val packet = byteArrayOf((MqttCodec.TYPE_SUBACK shl 4).toByte(), 3, 0, 1, 0)
        val result = MqttCodec.read(packet) as MqttCodec.ReadResult.Packet
        val subAck = result.packet as MqttCodec.Packet.SubAck
        assertEquals(1, subAck.packetId)
        assertEquals(listOf(0), subAck.granted)
    }

    @Test
    fun `pingresp is recognised`() {
        val result = MqttCodec.read(byteArrayOf((MqttCodec.TYPE_PINGRESP shl 4).toByte(), 0))
        assertTrue((result as MqttCodec.ReadResult.Packet).packet is MqttCodec.Packet.PingResp)
    }

    @Test
    fun `an unknown packet type is reported rather than crashing`() {
        // Type 15 is reserved; a broker should not send it, but the reader must
        // survive it instead of dropping the whole connection.
        val result = MqttCodec.read(byteArrayOf((15 shl 4).toByte(), 0))
        val packet = (result as MqttCodec.ReadResult.Packet).packet
        assertTrue(packet is MqttCodec.Packet.Unknown)
    }

    // ------------------------------------------------------- stream reassembly

    @Test
    fun `a partial packet asks for more data`() {
        val packet = MqttCodec.publish("topic", byteArrayOf(1, 2, 3, 4, 5, 6))
        // Everything but the last byte.
        val partial = packet.copyOfRange(0, packet.size - 1)
        assertTrue(MqttCodec.read(partial) is MqttCodec.ReadResult.NeedMoreData)
    }

    @Test
    fun `a header split across reads is handled`() {
        // Only the fixed header byte: even the length is not known yet.
        assertTrue(MqttCodec.read(byteArrayOf((MqttCodec.TYPE_PUBLISH shl 4).toByte())) is MqttCodec.ReadResult.NeedMoreData)
    }

    @Test
    fun `two packets in one buffer are consumed one at a time`() {
        val first = MqttCodec.publish("a", byteArrayOf(1))
        val second = MqttCodec.publish("bb", byteArrayOf(2, 3))
        val combined = first + second

        val r1 = MqttCodec.read(combined) as MqttCodec.ReadResult.Packet
        assertEquals("a", (r1.packet as MqttCodec.Packet.Publish).topic)

        val rest = combined.copyOfRange(r1.consumed, combined.size)
        val r2 = MqttCodec.read(rest) as MqttCodec.ReadResult.Packet
        assertEquals("bb", (r2.packet as MqttCodec.Packet.Publish).topic)
        assertArrayEquals(byteArrayOf(2, 3), (r2.packet as MqttCodec.Packet.Publish).payload)
    }

    @Test
    fun `an empty payload publish is still a publish`() {
        val packet = MqttCodec.publish("t", ByteArray(0))
        val result = MqttCodec.read(packet) as MqttCodec.ReadResult.Packet
        val publish = result.packet as MqttCodec.Packet.Publish
        assertEquals("t", publish.topic)
        assertEquals(0, publish.payload.size)
    }

    @Test
    fun `a large frame uses a multi byte length and survives`() {
        val payload = ByteArray(70_000) { (it % 251).toByte() }
        val packet = MqttCodec.publish("shutchat/v1/big", payload)
        val result = MqttCodec.read(packet) as MqttCodec.ReadResult.Packet
        val publish = result.packet as MqttCodec.Packet.Publish
        assertArrayEquals(payload, publish.payload)
    }
}
