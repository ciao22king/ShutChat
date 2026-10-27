package dev.shutchat.app.data.repo

import dev.shutchat.app.domain.model.MessageStatus

/**
 * When an outgoing message is re-sent, and what its status means meanwhile.
 *
 * Kept as pure functions so the rules can be tested without a database, a
 * transport or a clock. The retry loop itself is in [Outbox]; this is only the
 * arithmetic it follows.
 */
object RetryPolicy {

    private const val BASE_MILLIS = 3_000L
    private const val MAX_MILLIS = 120_000L
    private const val MAX_DOUBLINGS = 6

    /**
     * How long a message is worth re-sending.
     *
     * Past a month an undelivered message is far more likely to be a dead room
     * than an absent friend, and continuing would re-encrypt the whole backlog on
     * every tick for nothing. Marking it failed is the honest outcome: the user
     * sees "non inviato" instead of an optimistic clock that will never resolve.
     */
    const val MAX_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000

    /**
     * Status to show after an envelope was handed to the transports.
     *
     * Handing a frame to a socket is not delivery, so this is [SENDING], not
     * [MessageStatus.DELIVERED]. A message becomes delivered when the recipient
     * says so with a receipt, which is also what lets the outbox stop retrying it.
     */
    fun afterHandoff(handedTo: Int): MessageStatus =
        if (handedTo > 0) MessageStatus.SENDING else MessageStatus.QUEUED

    /**
     * Whether a message still has a chance of arriving.
     *
     * A room whose invite code has expired is unreachable by design: every peer
     * has dropped it, so retrying would spin forever and the honest thing is to
     * stop and mark the message failed.
     */
    fun shouldRetry(roomExpired: Boolean): Boolean = !roomExpired

    /** Whether a message is still young enough to keep trying. */
    fun isWithinRetryWindow(createdAt: Long, now: Long): Boolean =
        now - createdAt < MAX_AGE_MILLIS

    /** Exponential backoff, capped so a message for an absent peer stays cheap. */
    fun delayMillis(attempt: Int): Long {
        if (attempt <= 1) return BASE_MILLIS
        var delay = BASE_MILLIS
        repeat((attempt - 1).coerceAtMost(MAX_DOUBLINGS)) { delay *= 2 }
        return delay.coerceAtMost(MAX_MILLIS)
    }
}
