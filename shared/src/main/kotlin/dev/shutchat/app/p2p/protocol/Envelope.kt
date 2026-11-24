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

// --------------------------------------------------------------------- calls
//
// A call is signalled, not tunnelled. The offer, the answer and the ICE
// candidates travel as ordinary sealed envelopes through whatever transport is
// up, and the audio and video then go peer to peer over WebRTC. That keeps the
// relay out of the media path: the broker sees the same sealed blobs it already
// forwards for text, and never carries a voice packet.

/** Media a call can carry. Encoded as a string so a newer peer can add more. */
enum class CallMedia(val wire: String) {
    AUDIO("audio"),
    VIDEO("video"),
    ;

    companion object {
        fun fromWire(value: String): CallMedia = entries.firstOrNull { it.wire == value } ?: AUDIO
    }
}

/**
 * First frame of a call: the caller's SDP offer.
 *
 * [toNodeId] is null for a call to the whole room. It is present for a direct
 * chat, where a group-sized ring would disturb members the caller never meant to
 * reach.
 */
@Serializable
@SerialName("call-offer")
data class CallOffer(
    override val roomId: String,
    val callId: String,
    val fromNodeId: String,
    val fromName: String,
    val media: String,
    val sdp: String,
    val createdAt: Long,
    /** After this instant the offer is stale and must not ring. */
    val expiresAt: Long,
    val toNodeId: String? = null,
) : Envelope

/** "I am ringing", so the caller can distinguish a slow network from silence. */
@Serializable
@SerialName("call-ringing")
data class CallRinging(
    override val roomId: String,
    val callId: String,
    val fromNodeId: String,
    val toNodeId: String,
) : Envelope

/** The callee's SDP answer. Its arrival is what moves a call to connected. */
@Serializable
@SerialName("call-answer")
data class CallAnswer(
    override val roomId: String,
    val callId: String,
    val fromNodeId: String,
    val toNodeId: String,
    val sdp: String,
) : Envelope

/** One ICE candidate, trickled as it is gathered. */
@Serializable
@SerialName("call-ice")
data class CallIce(
    override val roomId: String,
    val callId: String,
    val fromNodeId: String,
    val toNodeId: String,
    val candidate: String,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int = 0,
    /** True when the sender is the caller, so the receiver knows which side it feeds. */
    val fromCaller: Boolean,
) : Envelope

/** The callee refused. Distinct from [CallHangup] so the caller can say why. */
@Serializable
@SerialName("call-decline")
data class CallDecline(
    override val roomId: String,
    val callId: String,
    val fromNodeId: String,
    val toNodeId: String,
    val reason: String = "rifiutata",
) : Envelope

/** Either side ending a call, or reporting that the media path failed. */
@Serializable
@SerialName("call-hangup")
data class CallHangup(
    override val roomId: String,
    val callId: String,
    val fromNodeId: String,
    val toNodeId: String,
    val reason: String = "chiusa",
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

    /** How long an unanswered call keeps ringing before it gives up. */
    const val CALL_RING_TIMEOUT_MILLIS = 45_000L

    /** How long the media path may take to report a connection. */
    const val CALL_CONNECT_TIMEOUT_MILLIS = 30_000L
}
