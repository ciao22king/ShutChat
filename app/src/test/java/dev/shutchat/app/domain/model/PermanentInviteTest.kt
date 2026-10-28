package dev.shutchat.app.domain.model

import dev.shutchat.app.core.Clock
import dev.shutchat.app.domain.expiry.Expiry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A permanent invite is permanent as a *room*, not as a fixture of the device.
 *
 * These tests pin the distinction the delete feature depends on: "infinito" means
 * the code never expires, and nothing more. A permanent chat must never take the
 * expired branch, and must never be treated as expiring on the NEVER sentinel day.
 * (The codec's own round trip of a permanent code is covered by InviteCodecTest.)
 */
class PermanentInviteTest {

    private fun clockAt(millis: Long) = Clock { millis }

    private fun chat(expiryDay: Int?) = Chat(
        id = "chat-1",
        kind = ChatKind.DIRECT,
        title = "Giulia",
        status = ChatStatus.ACTIVE,
        inviteCode = "ABCDEFGHIJKLMNOPQRSTUVWX",
        inviteExpiryDay = expiryDay,
        createdAt = 0L,
        lastActivityAt = 0L,
    )

    @Test
    fun `a chat created with an infinite code reports a permanent invite`() {
        assertTrue(chat(null).isPermanentInvite)
    }

    @Test
    fun `a chat with a dated code does not report a permanent invite`() {
        assertFalse(chat(1_200).isPermanentInvite)
    }

    @Test
    fun `a permanent invite is never expired, however far the clock runs`() {
        // The delete path asks whether a chat is still alive to decide how to warn
        // the user; a permanent chat must never take the expired branch.
        assertFalse(Expiry.isExpired(null, clockAt(4_000_000_000_000L)))
    }

    @Test
    fun `a permanent code has no deadline to describe`() {
        assertEquals("Mai (∞)", Expiry.describeDeadline(null, clockAt(0L)))
    }

    @Test
    fun `the never sentinel is not a real expiry day`() {
        // 0xFFFF days after the origin lands in the year 2204. The codec maps it to
        // null on the way in; anything that read it as a day would keep the room
        // alive for two centuries instead of forever.
        assertTrue(Expiry.NEVER > Expiry.ORIGIN_DAY)
        assertTrue(Expiry.expiresAtMillis(Expiry.NEVER)!! > clockAt(0L).nowMillis())
    }

    @Test
    fun `permanence is a property of the payload too`() {
        val permanent = InvitePayload(
            version = 1,
            kind = InviteKind.DIRECT,
            expiryDay = null,
            secretHex = "00".repeat(16),
        )
        assertTrue(permanent.isPermanent)
        assertFalse(permanent.copy(expiryDay = 900).isPermanent)
    }
}
