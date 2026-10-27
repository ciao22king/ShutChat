package dev.shutchat.app.data.repo

import dev.shutchat.app.core.Clock
import dev.shutchat.app.core.Log
import dev.shutchat.app.data.db.EnumMapping
import dev.shutchat.app.data.db.MessageEntity
import dev.shutchat.app.data.db.ShutChatDatabase
import dev.shutchat.app.domain.expiry.Expiry
import dev.shutchat.app.domain.model.MessageStatus
import dev.shutchat.app.p2p.MeshEngine
import dev.shutchat.app.p2p.protocol.WireAttachment
import dev.shutchat.app.p2p.protocol.WireMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Re-sends outgoing messages that never reached anyone.
 *
 * Without this the app silently loses messages: a message composed while the
 * recipient was offline was marked QUEUED and then never tried again, so it sat
 * in the transcript forever showing a clock glyph. The promise a messenger makes
 * is that the message arrives, so an offline recipient has to mean "later", not
 * "never".
 *
 * The queue is the messages table itself rather than a second structure: every
 * outgoing row already carries the body, the timestamp and the chat, so the
 * envelope can be rebuilt from it. A duplicate queue would be one more thing to
 * keep in step with the transcript, and it would lose entries on a crash that the
 * database would have survived.
 *
 * Retrying is driven by the transports coming up as well as by a timer: a peer
 * appearing on the LAN or the broker session reconnecting is exactly the event
 * that makes a previously undeliverable message deliverable, so waiting for the
 * next tick would only add latency.
 *
 * Messages are idempotent at the receiver, which is what makes blind re-sending
 * safe: a WireMessage is deduplicated by its own id, so a copy that arrives after
 * a receipt is acknowledged again rather than shown twice.
 */
class Outbox(
    private val db: ShutChatDatabase,
    private val clock: Clock,
    private val mesh: MeshEngine,
    private val scope: CoroutineScope,
) {

    /**
     * Per-message retry state.
     *
     * Backoff has to be per message, not global. A message stays in the undelivered
     * set after it was handed to a transport — that is what "no receipt yet" means —
     * so a single global counter would re-send every waiting message on the same
     * short interval and hammer a slow peer with identical frames.
     */
    private class Attempt(var count: Int = 0, var nextAt: Long = 0)

    private val attempts = ConcurrentHashMap<String, Attempt>()

    private val mutex = Mutex()

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        scope.launch {
            var idleTicks = 0
            while (isActive && running) {
                val handedOff = runCatching { flush() }.getOrDefault(0)
                idleTicks = if (handedOff > 0) 0 else idleTicks + 1
                delay(idleDelay(idleTicks))
            }
        }
    }

    fun stop() {
        running = false
    }

    /**
     * Rebuilds and re-sends every message that is due for another attempt.
     *
     * @return how many envelopes were handed to a transport.
     */
    suspend fun flush(): Int = mutex.withLock {
        if (!mesh.hasReachablePeer()) return 0
        val rows = runCatching { db.messageDao().undeliveredOutgoing() }.getOrDefault(emptyList())
        if (rows.isEmpty()) {
            attempts.clear()
            return 0
        }

        // Forget messages that are no longer waiting, so the map tracks the queue
        // rather than growing with every message ever sent.
        attempts.keys.retainAll(rows.mapTo(mutableSetOf()) { it.id })

        val now = clock.nowMillis()
        var handedOff = 0
        rows.forEach { row ->
            val chat = db.chatDao().find(row.chatId) ?: return@forEach
            if (!RetryPolicy.shouldRetry(Expiry.isExpired(chat.inviteExpiryDay, clock))) {
                // The code is dead, so no peer will ever accept this. Saying so is
                // better than retrying a room that no longer exists.
                fail(row.id)
                return@forEach
            }
            if (!RetryPolicy.isWithinRetryWindow(row.createdAt, now)) {
                fail(row.id)
                return@forEach
            }
            // Checked before encrypting: sealing an envelope for a room with nobody
            // in it is work that cannot make progress.
            if (!mesh.hasReachablePeerIn(chat.roomId)) return@forEach

            val attempt = attempts[row.id]
            if (attempt == null) {
                // First sighting: the message was just composed and already
                // broadcast once, so schedule the retry instead of sending an
                // immediate duplicate of something that left seconds ago.
                attempts[row.id] = Attempt(count = 1, nextAt = now + RetryPolicy.delayMillis(1))
                return@forEach
            }
            if (now < attempt.nextAt) return@forEach

            val envelope = rebuild(row, chat.roomId) ?: return@forEach
            val handedTo = runCatching { mesh.broadcast(envelope) }.getOrDefault(0)
            attempt.count++
            attempt.nextAt = now + RetryPolicy.delayMillis(attempt.count)
            if (handedTo > 0) {
                handedOff++
                // Still not delivered: the recipient has not acknowledged yet, and
                // this status is what keeps the message in the retry set.
                db.messageDao().setStatus(
                    row.id,
                    EnumMapping.messageStatusToInt(MessageStatus.SENDING),
                )
            }
        }
        if (handedOff > 0) Log.i(TAG, "rinviati $handedOff messaggi in attesa")
        handedOff
    }

    private suspend fun fail(messageId: String) {
        attempts.remove(messageId)
        db.messageDao().setStatus(messageId, EnumMapping.messageStatusToInt(MessageStatus.FAILED))
    }

    /**
     * Rebuilds the wire envelope from a stored row.
     *
     * Only what the receiver needs is reconstructed. Attachment bytes are not
     * re-sent here: a chunked transfer is resumable and owned by the transfer
     * layer, and a text announcement that arrives without its chunks is still
     * better than no message at all.
     */
    private suspend fun rebuild(row: MessageEntity, roomId: String): WireMessage? {
        val attachment = db.attachmentDao().find(row.id)
        return WireMessage(
            roomId = roomId,
            messageId = row.id,
            chatId = row.chatId,
            senderNodeId = row.senderNodeId,
            senderName = row.senderName,
            kind = row.kind,
            body = row.body,
            createdAt = row.createdAt,
            replyToId = row.replyToId,
            replyToPreview = row.replyToPreview,
            attachment = attachment?.let {
                WireAttachment(
                    id = it.id,
                    fileName = it.fileName,
                    mimeType = it.mimeType,
                    sizeBytes = it.sizeBytes,
                    durationMillis = it.durationMillis,
                    width = it.width,
                    height = it.height,
                )
            },
        )
    }

    /** How long to wait before looking at the queue again when nothing moved. */
    private fun idleDelay(idleTicks: Int): Long {
        var delay = TICK_MILLIS
        repeat((idleTicks - 1).coerceIn(0, 5)) { delay *= 2 }
        return delay.coerceAtMost(IDLE_MAX_MILLIS)
    }

    private companion object {
        const val TAG = "Outbox"
        const val TICK_MILLIS = 3_000L
        const val IDLE_MAX_MILLIS = 60_000L
    }
}
