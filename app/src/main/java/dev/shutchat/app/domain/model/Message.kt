package dev.shutchat.app.domain.model

enum class MessageKind {
    TEXT,
    IMAGE,
    AUDIO,
    FILE,
    /** Local, non-sent notice such as "Luca è entrato nel gruppo". */
    SYSTEM,
    ;

    companion object {
        fun fromName(name: String): MessageKind =
            entries.firstOrNull { it.name == name } ?: TEXT
    }
}

/**
 * Delivery state of an outgoing message.
 *
 * ShutChat has no server, so there is no "delivered to the server" step:
 * [QUEUED] means the message is waiting for the recipient to come back online,
 * and it is retried by every device that holds the room code.
 */
enum class MessageStatus {
    /** Only on this device, transport not ready yet. */
    PENDING,

    /** Handed to at least one transport, no acknowledgement yet. */
    SENDING,

    /** Waiting for an offline peer; retried when it reappears. */
    QUEUED,

    /** Peer acknowledged receipt. */
    DELIVERED,

    /** Peer opened the conversation. */
    READ,

    FAILED,
}

data class Message(
    val id: String,
    val chatId: String,
    val senderNodeId: String,
    val senderName: String,
    val kind: MessageKind,
    val body: String,
    val createdAt: Long,
    val status: MessageStatus,
    /** Set when the sender later edited the text. */
    val editedAt: Long? = null,
    val deleted: Boolean = false,
    val replyToId: String? = null,
    val replyToPreview: String? = null,
    val attachment: Attachment? = null,
    val reactions: List<Reaction> = emptyList(),
    /** Hop count when this message arrived through a relay. */
    val hops: Int = 0,
    val outgoing: Boolean = false,
) {
    val isOutgoing: Boolean get() = outgoing
}

data class Reaction(
    val messageId: String,
    val nodeId: String,
    val emoji: String,
)

data class Attachment(
    val id: String,
    val messageId: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val localPath: String?,
    /** Chunked transfer progress, 0f..1f. */
    val progress: Float = 1f,
    val durationMillis: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
)
