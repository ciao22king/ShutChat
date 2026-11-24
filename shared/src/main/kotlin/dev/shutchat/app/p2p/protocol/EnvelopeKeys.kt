package dev.shutchat.app.p2p.protocol

/**
 * Identity used to collapse duplicate deliveries.
 *
 * Messages are keyed by their own id; control envelopes by type plus the fields
 * that make them distinct, which is enough because they are idempotent by design.
 *
 * This lives next to the envelope definitions rather than in the router, so a
 * new envelope type cannot be added without deciding how it is deduplicated, and
 * so both platform meshes agree.
 */
fun Envelope.dedupeKey(): String = when (this) {
    is WireMessage -> "m:$messageId"
    is Receipt -> "r:$nodeId:${messageIds.joinToString(",")}"
    is Typing -> "t:$nodeId:$chatId:$typing"
    is ReactionUpdate -> "x:$messageId:$nodeId:$emoji:$removed"
    is EditMessage -> "e:$messageId:$editedAt"
    is DeleteMessage -> "d:$messageId"
    is Ping -> "p:$nodeId:$sentAt"
    is Pong -> "o:$nodeId:$sentAt"
    is JoinNotice -> "j:$nodeId"
    is LeaveNotice -> "l:$nodeId"
    is MemberRoster -> "ro:$chatId:${members.size}"
    is SyncRequest -> "s:$nodeId:$sinceMillis"
    is SyncResult -> "sr:$chatId:${messages.size}"
    is Hello -> "h:$nodeId"
    is HelloAck -> "ha:$nodeId"
    is TransferStart -> "ts:$transferId"
    is TransferComplete -> "tc:$transferId"
    is TransferCancel -> "tx:$transferId"
    // Call frames are keyed by call plus author: an offer is repeated while the
    // call rings, and a second copy must not open a second session.
    is CallOffer -> "co:$callId:$fromNodeId"
    is CallRinging -> "cr:$callId:$fromNodeId"
    is CallAnswer -> "ca:$callId:$fromNodeId"
    is CallIce -> "ci:$callId:$fromNodeId:$sdpMLineIndex:$candidate"
    is CallDecline -> "cd:$callId:$fromNodeId"
    is CallHangup -> "ch:$callId:$fromNodeId"
}

/**
 * The node that sent this envelope, when the envelope names one.
 *
 * Used to drop our own frames when they come back from the relay: a broker
 * echoes a publish to every subscriber including the publisher, so without this
 * every message would be received twice.
 *
 * @return the sender, or an empty string for envelopes that are about a room
 *         rather than a person — a roster or a sync result has no single author.
 */
fun Envelope.senderNodeId(): String = when (this) {
    is WireMessage -> senderNodeId
    is Hello -> nodeId
    is HelloAck -> nodeId
    is Receipt -> nodeId
    is Typing -> nodeId
    is ReactionUpdate -> nodeId
    is EditMessage -> nodeId
    is DeleteMessage -> nodeId
    is JoinNotice -> nodeId
    is LeaveNotice -> nodeId
    is SyncRequest -> nodeId
    is Ping -> nodeId
    is Pong -> nodeId
    is CallOffer -> fromNodeId
    is CallRinging -> fromNodeId
    is CallAnswer -> fromNodeId
    is CallIce -> fromNodeId
    is CallDecline -> fromNodeId
    is CallHangup -> fromNodeId
    else -> ""
}

/**
 * Whether this envelope concerns a call rather than the conversation itself.
 *
 * The router uses this to keep call traffic out of the message store: a call is
 * transient, and writing a row for every ICE candidate would bloat the database
 * and the transcript.
 */
fun Envelope.isCallSignal(): Boolean = this is CallOffer ||
    this is CallRinging ||
    this is CallAnswer ||
    this is CallIce ||
    this is CallDecline ||
    this is CallHangup
