package dev.shutchat.app.p2p

import dev.shutchat.app.core.hexToBytes
import dev.shutchat.app.core.toHex
import dev.shutchat.app.crypto.RoomCrypto

import dev.shutchat.app.p2p.protocol.Framing
import dev.shutchat.app.p2p.protocol.Protocol
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the chunk AAD contract between the sender and the mesh.
 *
 * The sender seals a chunk with the transfer id as associated data; the mesh has
 * to use the same value or every chunk fails to decrypt and files silently never
 * arrive. This test frames a chunk the way both clients do, then decrypts it the
 * way `MeshEngine` does.
 */
class ChunkAadTest {

    private val secret = ByteArray(32) { (it * 7).toByte() }
    private val roomId = RoomCrypto.roomId(secret)
    private val transferId = ByteArray(16) { (it * 13 + 1).toByte() }
    private val transferKey = RoomCrypto.transferKey(secret)

    @Test
    fun `a chunk sealed with the transfer id opens with the transfer id`() {
        val plaintext = Random(1).nextBytes(Protocol.CHUNK_SIZE)
        val sealed = RoomCrypto.encrypt(transferKey, plaintext, transferId)
        val frame = Framing.frameChunk(roomId, transferId, index = 3, total = 9, body = sealed)

        val parsed = Framing.parse(frame) as Framing.Frame.Chunk
        assertEquals(3, parsed.index)
        assertEquals(9, parsed.total)
        assertEquals(transferId.toHex(), parsed.transferId)

        val opened = RoomCrypto.decrypt(transferKey, parsed.body, parsed.transferId.hexToBytes())
        assertArrayEquals(plaintext, opened)
    }

    @Test
    fun `a chunk does not open with the room id as associated data`() {
        val sealed = RoomCrypto.encrypt(transferKey, ByteArray(64) { 1 }, transferId)
        val frame = Framing.frameChunk(roomId, transferId, index = 0, total = 1, body = sealed)
        val parsed = Framing.parse(frame) as Framing.Frame.Chunk

        val opened = runCatching { RoomCrypto.decrypt(transferKey, parsed.body, parsed.roomId) }.getOrNull()
        assertNull("the room id must not be accepted as associated data", opened)
    }

    @Test
    fun `a chunk cannot be moved to another transfer`() {
        val sealed = RoomCrypto.encrypt(transferKey, ByteArray(32) { 2 }, transferId)
        val other = ByteArray(16) { 9 }
        val opened = runCatching { RoomCrypto.decrypt(transferKey, sealed, other) }.getOrNull()
        assertNull(opened)
    }
}
