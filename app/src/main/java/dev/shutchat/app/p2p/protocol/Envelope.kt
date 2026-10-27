package dev.shutchat.app.p2p.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Everything two ShutChat devices say to each other.
 *
 * Every envelope carries the [roomId] it belongs to. That is what lets a device
 * route a frame it cannot read (see [Framing]): the room id travels in the clear
 * in the frame header, the body is sealed with the room key.
 */
@Serializable
sealed interface Envelope {
    val roomId: String
}

/** First frame on a link. Announces the node and the rooms it holds. */
@Serializable
@SerialName("hello")
data class Hello(
    override val roomId: String,
    val nodeId: String,
    val displayName: String,
    val avatarSeed: String,
    /** Rooms this node participates in, so peers can build a routing table. */
    val knownRoomIds: List<String>,
    val protocolVersion: Int,
    val appVersion: String,
) : Envelope

/** Ack for a received hello; also used to confirm the peer is still there. */
@Serializable
@SerialName("hello-ack")
data class HelloAck(
    override val roomId: String,
    val nodeId: String,
    val displayName: String,
    val avatarSeed: String,
    val knownRoomIds: List<String>,
    val protocolVersion: Int,
    val appVersion: String,
) : Envelope

@Serializable
@SerialName("message")
data class WireMessage(
    override val roomId: String,
    val messageId: String,
    val chatId: String,
    val senderNodeId: String,
    val senderName: String,
    val kind: String,
    val body: String,
    val createdAt: Long,
    val replyToId: String? = null,
    val replyToPreview: String? = null,
    val attachment: WireAttachment? = null,
    /** Nodes that already handled this message, so relays do not loop. */
    val seenBy: List<String> = emptyList(),
    val hops: Int = 0,
) : Envelope

@Serializable
data class WireAttachment(
    val id: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMillis: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
@SerialName("receipt")
data class Receipt(
    override val roomId: String,
    val messageIds: List<String>,
    /** "delivered" or "read". */
    val state: String,
    val nodeId: String,
) : Envelope

@Serializable
@SerialName("typing")
data class Typing(
    override val roomId: String,
    val chatId: String,
    val nodeId: String,
    val displayName: String,
    val typing: Boolean,
) : Envelope

@Serializable
@SerialName("reaction")
data class ReactionUpdate(
    override val roomId: String,
    val messageId: String,
    val nodeId: String,
    val emoji: String,
    val removed: Boolean,
) : Envelope

@Serializable
@SerialName("edit")
data class EditMessage(
    override val roomId: String,
    val messageId: String,
    val nodeId: String,
    val newBody: String,
    val editedAt: Long,
) : Envelope

@Serializable
@SerialName("delete")
data class DeleteMessage(
    override val roomId: String,
    val messageId: String,
    val nodeId: String,
) : Envelope

@Serializable
@SerialName("members")
data class MemberRoster(
    override val roomId: String,
    val chatId: String,
    val members: List<WireMember>,
) : Envelope

@Serializable
data class WireMember(
    val nodeId: String,
    val displayName: String,
    val avatarSeed: String,
    val isOwner: Boolean,
    val joinedAt: Long,
)

/** Sent when a device joins a room, so others can add it to the roster. */
@Serializable
@SerialName("join")
data class JoinNotice(
    override val roomId: String,
    val chatId: String,
    val nodeId: String,
    val displayName: String,
    val avatarSeed: String,
) : Envelope

@Serializable
@SerialName("leave")
data class LeaveNotice(
    override val roomId: String,
    val chatId: String,
    val nodeId: String,
) : Envelope

/** Asks peers to resend anything this node is missing. */
@Serializable
@SerialName("sync")
data class SyncRequest(
    override val roomId: String,
    val chatId: String,
    val nodeId: String,
    /** Newest message timestamp already stored locally. */
    val sinceMillis: Long,
) : Envelope

@Serializable
@SerialName("sync-result")
data class SyncResult(
    override val roomId: String,
    val chatId: String,
    val messages: List<WireMessage>,
) : Envelope

@Serializable
@SerialName("ping")
data class Ping(
    override val roomId: String,
    val nodeId: String,
    val sentAt: Long,
) : Envelope

@Serializable
@SerialName("pong")
data class Pong(
    override val roomId: String,
    val nodeId: String,
    val sentAt: Long,
    val receivedAt: Long,
) : Envelope

/** Announced before the chunks of a file so the receiver can size its buffer. */
@Serializable
@SerialName("transfer-start")
data class TransferStart(
    override val roomId: String,
    val transferId: String,
    val attachmentId: String,
    val messageId: String,
    val fileName: String,
    val mimeType: String,
    val totalChunks: Int,
    val totalBytes: Long,
) : Envelope

@Serializable
@SerialName("transfer-complete")
data class TransferComplete(
    override val roomId: String,
    val transferId: String,
    val attachmentId: String,
    val messageId: String,
) : Envelope

@Serializable
@SerialName("transfer-cancel")
data class TransferCancel(
    override val roomId: String,
    val transferId: String,
    val reason: String,
) : Envelope

/** Raised when a peer's payload cannot be understood. */
class ProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)

object Protocol {
    const val VERSION = 1
    const val MAX_CONTROL_BYTES = 512 * 1024
    const val CHUNK_SIZE = 32 * 1024
    const val MAX_FRAME_BYTES = 2 * 1024 * 1024

    /** Refuse to relay a message that has already crossed this many devices. */
    const val MAX_HOPS = 4
}
