package dev.shutchat.app.data.repo

import dev.shutchat.app.domain.model.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Retry rules.
 *
 * These cover the two decisions that were wrong before: a handed-off message was
 * reported as delivered, and a message for an offline peer was never retried at
 * all.
 */
class RetryPolicyTest {

    @Test
    fun `handing a frame to a transport is not delivery`() {
        // The bug this guards: with a peer on the LAN, broadcast() returns 1 the
        // moment the bytes are written to the socket, and the old code recorded
        // DELIVERED from that alone. Nothing had confirmed the peer received it.
        assertEquals(MessageStatus.SENDING, RetryPolicy.afterHandoff(1))
        assertEquals(MessageStatus.SENDING, RetryPolicy.afterHandoff(3))
    }

    @Test
    fun `no transport means the message is queued for later`() {
        assertEquals(MessageStatus.QUEUED, RetryPolicy.afterHandoff(0))
    }

    @Test
    fun `a live room is retried and an expired one is not`() {
        assertTrue(RetryPolicy.shouldRetry(roomExpired = false))
        assertFalse(RetryPolicy.shouldRetry(roomExpired = true))
    }

    @Test
    fun `backoff grows then stops growing`() {
        val first = RetryPolicy.delayMillis(1)
        val second = RetryPolicy.delayMillis(2)
        val third = RetryPolicy.delayMillis(3)
        assertTrue("il secondo tentativo deve attendere piu del primo", second > first)
        assertTrue("il terzo deve attendere piu del secondo", third > second)
    }

    @Test
    fun `backoff is capped so a dead peer stays cheap`() {
        assertEquals(120_000L, RetryPolicy.delayMillis(1000))
    }

    @Test
    fun `backoff never returns a non positive delay`() {
        (0..50).forEach { attempt ->
            assertTrue("tentativo $attempt", RetryPolicy.delayMillis(attempt) > 0)
        }
    }
}
