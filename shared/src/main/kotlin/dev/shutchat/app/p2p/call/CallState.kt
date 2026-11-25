package dev.shutchat.app.p2p.call

import dev.shutchat.app.p2p.protocol.CallMedia
import dev.shutchat.app.p2p.protocol.Protocol

/** Where a call is in its life. */
enum class CallPhase {
    /** No call in progress. */
    IDLE,

    /** We started it and are waiting for the peer to answer. */
    DIALING,

    /** The peer started it and the user has not answered yet. */
    RINGING,

    /** Both sides accepted; the media path is still being set up. */
    CONNECTING,

    /** Audio (and video) are flowing. */
    ACTIVE,

    /** Over. Kept on screen until the user dismisses it. */
    ENDED,
    ;

    val isLive: Boolean get() = this == DIALING || this == RINGING || this == CONNECTING || this == ACTIVE
}

/** Why a call ended. The label is what the UI shows. */
enum class CallEndReason(val label: String) {
    LOCAL_HANGUP("call ended"),
    REMOTE_HANGUP("the other side hung up"),
    DECLINED("call declined"),
    BUSY("busy"),
    TIMEOUT("no answer"),
    FAILED("connection failed"),
    UNSUPPORTED("calls are not available on this device"),
}

/**
 * Everything the call UI renders.
 *
 * A single immutable value rather than a bag of flags: the screen has one source
 * of truth, and the state machine below is a pure function from the previous
 * value plus an event to the next one, which is what makes it testable without a
 * microphone, a socket or a peer.
 */
data class CallSnapshot(
    val phase: CallPhase = CallPhase.IDLE,
    val callId: String? = null,
    val peerNodeId: String? = null,
    val peerName: String = "",
    val media: CallMedia = CallMedia.AUDIO,
    val outgoing: Boolean = false,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    val startedAt: Long = 0,
    val connectedAt: Long? = null,
    val endReason: CallEndReason? = null,
    /** Short line under the peer name: "ringing", "connecting...". */
    val notice: String? = null,
) {
    val isBusy: Boolean get() = phase.isLive

    fun elapsedMillis(now: Long): Long = when {
        connectedAt != null -> (now - connectedAt).coerceAtLeast(0)
        else -> 0
    }
}

/** Inputs to the state machine. */
sealed interface CallEvent {

    data class StartOutgoing(
        val callId: String,
        val peerNodeId: String?,
        val peerName: String,
        val media: CallMedia,
        val at: Long,
    ) : CallEvent

    data class IncomingOffer(
        val callId: String,
        val fromNodeId: String,
        val fromName: String,
        val media: CallMedia,
        val at: Long,
    ) : CallEvent

    /** The callee's device said it is ringing. */
    data object RemoteRinging : CallEvent

    /** The user pressed answer on an incoming call. */
    data object LocalAccepted : CallEvent

    /** The callee's SDP answer arrived. */
    data object RemoteAccepted : CallEvent

    /** The media stack reports ICE connected. */
    data object MediaConnected : CallEvent

    data object LocalHangup : CallEvent

    data class RemoteHangup(val reason: CallEndReason = CallEndReason.REMOTE_HANGUP) : CallEvent

    data class Failed(val notice: String) : CallEvent

    data class SetMuted(val muted: Boolean) : CallEvent

    data class SetSpeaker(val speaker: Boolean) : CallEvent

    /** Periodic clock, used for ring and connect timeouts. */
    data class Tick(val now: Long) : CallEvent

    /** Clear a finished call off the screen. */
    data object Dismiss : CallEvent
}

/**
 * The call state machine.
 *
 * Deliberately total and side-effect free: every event on every state returns a
 * state, so a late ICE candidate arriving after a hang-up cannot crash the app or
 * resurrect a dead call.
 */
object CallStateMachine {

    fun reduce(state: CallSnapshot, event: CallEvent): CallSnapshot = when (event) {
        is CallEvent.StartOutgoing -> {
            if (state.isBusy) state else CallSnapshot(
                phase = CallPhase.DIALING,
                callId = event.callId,
                peerNodeId = event.peerNodeId,
                peerName = event.peerName,
                media = event.media,
                outgoing = true,
                startedAt = event.at,
                notice = "ringing",
            )
        }

        is CallEvent.IncomingOffer -> {
            // A second offer while a call is live means the peer missed our
            // decline; the controller answers it with a busy signal.
            if (state.isBusy) state else CallSnapshot(
                phase = CallPhase.RINGING,
                callId = event.callId,
                peerNodeId = event.fromNodeId,
                peerName = event.fromName,
                media = event.media,
                outgoing = false,
                startedAt = event.at,
                notice = null,
            )
        }

        is CallEvent.RemoteRinging ->
            if (state.phase == CallPhase.DIALING) state.copy(notice = "ringing") else state

        is CallEvent.LocalAccepted ->
            if (state.phase == CallPhase.RINGING) {
                state.copy(phase = CallPhase.CONNECTING, notice = "connecting...")
            } else {
                state
            }

        is CallEvent.RemoteAccepted ->
            if (state.phase == CallPhase.DIALING || state.phase == CallPhase.RINGING) {
                state.copy(phase = CallPhase.CONNECTING, notice = "connecting...")
            } else {
                state
            }

        is CallEvent.MediaConnected ->
            if (state.phase == CallPhase.CONNECTING) {
                state.copy(
                    phase = CallPhase.ACTIVE,
                    connectedAt = System.currentTimeMillis(),
                    notice = null,
                )
            } else {
                state
            }

        is CallEvent.LocalHangup ->
            if (state.isBusy) end(state, CallEndReason.LOCAL_HANGUP) else state

        is CallEvent.RemoteHangup ->
            if (state.isBusy) end(state, event.reason) else state

        is CallEvent.Failed ->
            if (state.isBusy) end(state, CallEndReason.FAILED).copy(notice = event.notice) else state

        is CallEvent.SetMuted -> state.copy(muted = event.muted)

        is CallEvent.SetSpeaker -> state.copy(speaker = event.speaker)

        is CallEvent.Tick -> tick(state, event.now)

        is CallEvent.Dismiss -> if (state.phase == CallPhase.ENDED) CallSnapshot() else state
    }

    private fun tick(state: CallSnapshot, now: Long): CallSnapshot {
        if (!state.isBusy) return state
        val age = now - state.startedAt
        return when (state.phase) {
            CallPhase.DIALING, CallPhase.RINGING ->
                if (age >= Protocol.CALL_RING_TIMEOUT_MILLIS) {
                    end(state, CallEndReason.TIMEOUT)
                } else {
                    state
                }

            CallPhase.CONNECTING ->
                if (age >= Protocol.CALL_RING_TIMEOUT_MILLIS + Protocol.CALL_CONNECT_TIMEOUT_MILLIS) {
                    end(state, CallEndReason.FAILED).copy(notice = "connection failed")
                } else {
                    state
                }

            else -> state
        }
    }

    private fun end(state: CallSnapshot, reason: CallEndReason): CallSnapshot =
        state.copy(phase = CallPhase.ENDED, endReason = reason, notice = reason.label)
}
