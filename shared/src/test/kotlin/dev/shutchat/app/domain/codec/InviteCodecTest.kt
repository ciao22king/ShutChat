package dev.shutchat.app.domain.codec

import dev.shutchat.app.domain.expiry.Expiry
import dev.shutchat.app.domain.model.InviteKind
import dev.shutchat.app.domain.model.InvalidInviteCodeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The invite code is the only thing that carries access, so these tests pin its
 * wire format: if a round trip stopped preserving the expiry day, or two different
 * secrets collided on a room id, the failure would otherwise only show up as two
 * devices silently unable to see each other.
 */
class InviteCodecTest {

    @Test
    fun `encode produces 24 characters in six groups`() {
        val code = InviteCodec.create(InviteKind.DIRECT, expiryDay = 400)
        val compact = code.text.replace("-", "")
        assertEquals(24, compact.length)
        assertEquals(6, code.text.split("-").size)
    }

    @Test
    fun `round trip preserves kind expiry and room id`() {
        val original = InviteCodec.create(InviteKind.GROUP, expiryDay = 1234)
        val parsed = InviteCodec.parse(original.text)

        assertEquals(InviteKind.GROUP, parsed.kind)
        assertEquals(1234, parsed.payload.expiryDay)
        assertEquals(original.roomId, parsed.roomId)
        assertEquals(original.payload.secretHex, parsed.payload.secretHex)
        // Parsing must re-emit the identical code, checksum bytes included.
        assertEquals(original.text, parsed.text)
    }

    @Test
    fun `infinite expiry survives the round trip as null`() {
        val original = InviteCodec.create(InviteKind.DIRECT, expiryDay = null)
        val parsed = InviteCodec.parse(original.text)

        assertNull(parsed.payload.expiryDay)
        assertTrue(parsed.payload.isPermanent)
    }

    @Test
    fun `parsing tolerates case spaces dashes and uris`() {
        val code = InviteCodec.create(InviteKind.DIRECT, expiryDay = 90)
        val compact = code.text.replace("-", "")

        assertEquals(code.roomId, InviteCodec.parse(compact.lowercase()).roomId)
        assertEquals(code.roomId, InviteCodec.parse("  ${code.text}  ").roomId)
        assertEquals(code.roomId, InviteCodec.parse(InviteCodec.toUri(code)).roomId)
    }

    @Test
    fun `same secret always maps to the same room`() {
        val secret = ByteArray(InviteCodec.SECRET_SIZE) { it.toByte() }
        val a = InviteCodec.create(InviteKind.DIRECT, 10, secret)
        val b = InviteCodec.create(InviteKind.GROUP, 20, secret)

        assertEquals(a.roomId, b.roomId)
    }

    @Test
    fun `different secrets map to different rooms`() {
        val a = InviteCodec.create(InviteKind.DIRECT, 10)
        val b = InviteCodec.create(InviteKind.DIRECT, 10)

        assertTrue(a.roomId != b.roomId)
    }

    @Test
    fun `a corrupted character is rejected by the checksum`() {
        val code = InviteCodec.create(InviteKind.DIRECT, expiryDay = 500)
        val compact = code.text.replace("-", "").toCharArray()
        // Flip one character in the secret region, keeping it inside the alphabet.
        compact[20] = if (compact[20] == 'A') 'B' else 'A'
        val corrupted = String(compact).chunked(4).joinToString("-")

        val failure = runCatching { InviteCodec.parse(corrupted) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure is InvalidInviteCodeException)
    }

    @Test
    fun `a wrong length is rejected`() {
        val failure = runCatching { InviteCodec.parse("ABCD-EFGH") }.exceptionOrNull()
        assertTrue(failure is InvalidInviteCodeException)
    }

    @Test
    fun `expiry day derived from a preset matches what the code carries`() {
        val clock = dev.shutchat.app.core.Clock { 1_760_000_000_000L }
        val day = Expiry.presetExpiryDay(Expiry.Preset.THREE_DAYS, clock)
        val code = InviteCodec.create(InviteKind.DIRECT, day)

        assertEquals(day, InviteCodec.parse(code.text).payload.expiryDay)
        assertEquals(day, code.payload.expiryDay)
    }
}
