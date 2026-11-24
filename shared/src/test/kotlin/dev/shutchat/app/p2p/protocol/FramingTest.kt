package dev.shutchat.app.p2p.protocol

import dev.shutchat.app.core.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Framing tests.
 *
 * The room id travelling in the clear is the design decision everything else
 * rests on: it is what lets a device relay a frame it cannot decrypt. So these
 * tests check both that a frame round trips and that a relay can read the room id
 * off a frame whose body it cannot read.
 */
class FramingTest {

    private fun roomId(seed: Int) = ByteArray(16) { (it + seed).toByte() }

    @Test
    fun `a control frame carries the room id and the body`() {
        val room = roomId(1)
        val body = "ciao".toByteArray()

        val decoded = Framing.parse(Framing.frameControl(room, body))

        assertTrue(decoded is Framing.Frame.Control)
        decoded as Framing.Frame.Control
        assertArrayEquals(room, decoded.roomId)
        assertArrayEquals(body, decoded.body)
    }

    @Test
    fun `a chunk frame preserves transfer id index and total`() {
        val room = roomId(2)
        val transfer = roomId(9)
        val body = ByteArray(1024) { it.toByte() }

        val decoded = Framing.parse(
            Framing.frameChunk(room, transfer, index = 7, total = 40, body = body),
        )

        assertTrue(decoded is Framing.Frame.Chunk)
        decoded as Framing.Frame.Chunk
        assertArrayEquals(room, decoded.roomId)
        assertEquals(transfer.toHex(), decoded.transferId)
        assertEquals(7, decoded.index)
        assertEquals(40, decoded.total)
        assertArrayEquals(body, decoded.body)
    }

    @Test
    fun `a relay can read the room id without touching the body`() {
        val room = roomId(3)
        // Stand-in for ciphertext: opaque bytes the relay must not need to parse.
        val opaque = ByteArray(64) { (it * 31).toByte() }

        val decoded = Framing.parse(Framing.frameControl(room, opaque)) as Framing.Frame.Control

        assertArrayEquals(room, decoded.roomId)
        assertArrayEquals(opaque, decoded.body)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parsing rejects a truncated frame`() {
        Framing.parse(ByteArray(Framing.LENGTH_HEADER_SIZE))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parsing rejects a length that exceeds the maximum`() {
        val frame = Framing.frameControl(roomId(4), ByteArray(4))
        Framing.writeInt(frame, 1, Protocol.MAX_FRAME_BYTES + 1)
        Framing.parse(frame)
    }

    @Test
    fun `an unknown frame type is read as a control frame`() {
        val frame = Framing.frameControl(roomId(4), ByteArray(4))
        frame[0] = 99
        assertTrue(Framing.parse(frame) is Framing.Frame.Control)
    }

    @Test
    fun `length header and room id sizes line up with the wire format`() {
        val frame = Framing.frameControl(roomId(5), ByteArray(10))
        assertEquals(Framing.LENGTH_HEADER_SIZE + Framing.ROOM_ID_SIZE + 10, frame.size)
    }

    @Test
    fun `int round trips through the fixed width helpers`() {
        val buffer = ByteArray(4)
        listOf(0, 1, -1, Int.MAX_VALUE, Int.MIN_VALUE, 123456).forEach { value ->
            Framing.writeInt(buffer, 0, value)
            assertEquals(value, Framing.readInt(buffer, 0))
        }
    }
}
