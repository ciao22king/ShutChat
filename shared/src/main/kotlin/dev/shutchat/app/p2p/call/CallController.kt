package dev.shutchat.app.p2p.call

import dev.shutchat.app.core.Clock
import dev.shutchat.app.p2p.protocol.CallAnswer
import dev.shutchat.app.p2p.protocol.CallDecline
import dev.shutchat.app.p2p.protocol.CallHangup
import dev.shutchat.app.p2p.protocol.CallIce
import dev.shutchat.app.p2p.protocol.CallMedia
import dev.shutchat.app.p2p.protocol.CallOffer
import dev.shutchat.app.p2p.protocol.CallRinging
import dev.shutchat.app.p2p.protocol.Envelope
import dev.shutchat.app.p2p.protocol.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One ICE candidate, in the shape both WebRTC wrappers understand. */
data class IceCandidate(
    val candidate: String,
    val sdpMid: String?,
    val sdpMLineIndex: Int,
)

/**
 * The audio/video engine behind a call.
 *
 * Signalling is platform-neutral; the media is not. Android uses
 * `org.webrtc` from the WebRTC SDK, the desktop uses the Java wrapper. Both are
 * hidden behind this interface so [CallController] never learns which one it has.
 */
interface CallMediaSession {

    /** False when the device has no working media stack (no mic, no native lib). */
    val isSupported: Boolean

    /** Creates the local offer; the session reports it through `onLocalSdp`. */
    suspend fun startOutgoing(callId: String, media: CallMedia): Boolean

    /** Applies the remote offer and produces the local answer. */
    suspend fun acceptIncoming(callId: String, media: CallMedia, remoteSdp: String): Boolean

    suspend fun applyRemoteAnswer(remoteSdp: String): Boolean

    suspend fun addRemoteCandidate(candidate: IceCandidate)

    fun setMuted(muted: Boolean)

    fun setSpeakerOn(speaker: Boolean)

    fun close()
}

/** Builds a [CallMediaSession] wired to the controller's callbacks. */
fun interface CallMediaFactory {
    fun create(
        onLocalSdp: (String) -> Unit,
        onLocalCandidate: (IceCandidate) -> Unit,
        onConnected: () -> Unit,
        onFailed: (String) -> Unit,
    ): CallMediaSession?
}

/**
 * Drives one call at a time.
 *
 * Responsibilities are narrow on purpose: turn user actions and incoming
 * envelopes into [CallEvent]s, keep the media session in step, and hand the
 * resulting [CallSnapshot] to the UI. It owns no transport and no socket, so the
 * same class runs on Android and on the desktop.
 */
class CallController(
    private val scope: CoroutineScope,
    private val selfNodeId: () -> String,
    private val selfName: () -> String,
    private val clock: Clock,
    private val send: suspend (Envelope) -> Unit,
    private val mediaFactory: CallMediaFactory?,
    /** Called when a peer starts a call, so the platform can ring or notify. */
    private val onIncoming: (CallSnapshot) -> Unit = {},
) {

    private val _state = MutableStateFlow(CallSnapshot())
    val state: StateFlow<CallSnapshot> = _state.asStateFlow()

    private var media: CallMediaSession? = null
    private var roomId: String? = null
    private var remoteSdp: String? = null

    /** Candidates that arrived before the media session could take them. */
    private val earlyCandidates = ArrayDeque<CallIce>()

    private var ticker: Job? = null

    /**
     * The live media session.
     *
     * Exposed only so a platform view can attach its surfaces: the controller
     * itself never touches a renderer.
     */
    val currentMedia: CallMediaSession? get() = media

    init {
        ticker = scope.launch {
            while (isActive) {
                delay(1_000)
                val snapshot = _state.value
                if (snapshot.isBusy) {
                    apply(CallEvent.Tick(clock.nowMillis()))
                }
            }
        }
    }

    // ------------------------------------------------------------------ actions

    fun startCall(
        roomId: String,
        peerNodeId: String?,
        peerName: String,
        kind: CallMedia = CallMedia.AUDIO,
    ) {
        if (_state.value.isBusy) return
        val session = mediaFactory?.create(
            onLocalSdp = { sdp -> onLocalSdp(sdp) },
            onLocalCandidate = { candidate -> onLocalCandidate(candidate) },
            onConnected = { apply(CallEvent.MediaConnected) },
            onFailed = { reason -> apply(CallEvent.Failed(reason)) },
        )
        if (session == null || !session.isSupported) {
            _state.value = CallSnapshot(
                phase = CallPhase.ENDED,
                endReason = CallEndReason.UNSUPPORTED,
                notice = CallEndReason.UNSUPPORTED.label,
            )
            session?.close()
            return
        }
        media = session
        this.roomId = roomId
        remoteSdp = null
        earlyCandidates.clear()

        val callId = newCallId()
        apply(CallEvent.StartOutgoing(callId, peerNodeId, peerName, kind, clock.nowMillis()))
        scope.launch {
            val started = runCatching { session.startOutgoing(callId, kind) }.getOrDefault(false)
            if (!started) {
                apply(CallEvent.Failed("microphone unavailable"))
                cleanup()
            }
        }
    }

    /** Answers the incoming call currently ringing. */
    fun accept() {
        val snapshot = _state.value
        if (snapshot.phase != CallPhase.RINGING) return
        val callId = snapshot.callId ?: return
        val offer = remoteSdp
        val session = media ?: return
        if (offer == null) {
            apply(CallEvent.Failed("invalid offer"))
            return
        }
        apply(CallEvent.LocalAccepted)
        scope.launch {
            val ok = runCatching { session.acceptIncoming(callId, snapshot.media, offer) }
                .getOrDefault(false)
            if (!ok) {
                apply(CallEvent.Failed("could not start audio"))
                hangUpInternal("audio error")
                return@launch
            }
            flushEarlyCandidates()
        }
    }

    /** Refuses the incoming call. */
    fun decline() {
        val snapshot = _state.value
        if (snapshot.phase != CallPhase.RINGING) return
        val callId = snapshot.callId ?: return
        val peer = snapshot.peerNodeId ?: return
        val room = roomId ?: return
        val node = selfNodeId()
        apply(CallEvent.RemoteHangup(CallEndReason.DECLINED))
        scope.launch {
            runCatching {
                send(CallDecline(room, callId, node, peer, CallEndReason.DECLINED.label))
            }
        }
        cleanup()
    }

    /** Ends the call from this side and tells the peer. */
    fun hangUp() {
        val snapshot = _state.value
        if (!snapshot.isBusy) return
        apply(CallEvent.LocalHangup)
        hangUpInternal(CallEndReason.LOCAL_HANGUP.label)
    }

    fun toggleMute(): Boolean {
        val next = !_state.value.muted
        apply(CallEvent.SetMuted(next))
        media?.setMuted(next)
        return next
    }

    fun toggleSpeaker(): Boolean {
        val next = !_state.value.speaker
        apply(CallEvent.SetSpeaker(next))
        media?.setSpeakerOn(next)
        return next
    }

    fun dismiss() {
        apply(CallEvent.Dismiss)
        cleanup()
    }

    // ------------------------------------------------------------------ inbound

    /**
     * Feeds a call envelope to the controller.
     *
     * @return true when the envelope belonged to the call layer. The router uses
     *         this to keep call traffic out of the message store.
     */
    fun onEnvelope(envelope: Envelope, fromNodeId: String): Boolean {
        val snapshot = _state.value
        return when (envelope) {
            is CallOffer -> {
                if (envelope.toNodeId != null && envelope.toNodeId != selfNodeId()) {
                    return true
                }
                onOffer(envelope)
                true
            }

            is CallRinging -> {
                if (snapshot.callId == envelope.callId && snapshot.outgoing) {
                    apply(CallEvent.RemoteRinging)
                }
                true
            }

            is CallAnswer -> {
                if (snapshot.callId == envelope.callId && snapshot.outgoing) {
                    apply(CallEvent.RemoteAccepted)
                    scope.launch {
                        val ok = runCatching { media?.applyRemoteAnswer(envelope.sdp) == true }
                            .getOrDefault(false)
                        if (!ok) apply(CallEvent.Failed("invalid answer"))
                        flushEarlyCandidates()
                    }
                }
                true
            }

            is CallIce -> {
                if (snapshot.callId != envelope.callId) return true
                val session = media
                val candidate = IceCandidate(envelope.candidate, envelope.sdpMid, envelope.sdpMLineIndex)
                if (session == null) {
                    earlyCandidates.addLast(envelope)
                } else {
                    scope.launch { runCatching { session.addRemoteCandidate(candidate) } }
                }
                true
            }

            is CallDecline -> {
                if (snapshot.callId == envelope.callId && snapshot.outgoing) {
                    apply(CallEvent.RemoteHangup(CallEndReason.DECLINED))
                    cleanup()
                }
                true
            }

            is CallHangup -> {
                if (snapshot.callId == envelope.callId && snapshot.isBusy) {
                    apply(CallEvent.RemoteHangup(CallEndReason.REMOTE_HANGUP))
                    cleanup()
                }
                true
            }

            else -> false
        }
    }

    private fun onOffer(offer: CallOffer) {
        val current = _state.value
        if (current.isBusy) {
            // Already on a call: answer with a decline rather than leave the
            // caller ringing for 45 seconds.
            scope.launch {
                runCatching {
                    send(
                        CallDecline(
                            offer.roomId,
                            offer.callId,
                            selfNodeId(),
                            offer.fromNodeId,
                            CallEndReason.BUSY.label,
                        ),
                    )
                }
            }
            return
        }
        if (offer.expiresAt in 1..clock.nowMillis()) {
            scope.launch {
                runCatching {
                    send(
                        CallDecline(
                            offer.roomId,
                            offer.callId,
                            selfNodeId(),
                            offer.fromNodeId,
                            "offer expired",
                        ),
                    )
                }
            }
            return
        }

        val session = mediaFactory?.create(
            onLocalSdp = { sdp -> onLocalSdp(sdp) },
            onLocalCandidate = { candidate -> onLocalCandidate(candidate) },
            onConnected = { apply(CallEvent.MediaConnected) },
            onFailed = { reason -> apply(CallEvent.Failed(reason)) },
        )
        if (session == null || !session.isSupported) {
            scope.launch {
                runCatching {
                    send(
                        CallDecline(
                            offer.roomId,
                            offer.callId,
                            selfNodeId(),
                            offer.fromNodeId,
                            CallEndReason.UNSUPPORTED.label,
                        ),
                    )
                }
            }
            session?.close()
            return
        }

        media = session
        roomId = offer.roomId
        remoteSdp = offer.sdp
        earlyCandidates.clear()
        apply(
            CallEvent.IncomingOffer(
                callId = offer.callId,
                fromNodeId = offer.fromNodeId,
                fromName = offer.fromName,
                media = CallMedia.fromWire(offer.media),
                at = clock.nowMillis(),
            ),
        )
        // Tell the caller we are ringing: a silent phone and a ringing one look
        // identical from the other end otherwise.
        scope.launch {
            runCatching {
                send(CallRinging(offer.roomId, offer.callId, selfNodeId(), offer.fromNodeId))
            }
        }
        onIncoming(_state.value)
    }

    // ------------------------------------------------------------------ helpers

    private fun onLocalSdp(sdp: String) {
        val snapshot = _state.value
        val callId = snapshot.callId ?: return
        val room = roomId ?: return
        val node = selfNodeId()
        if (snapshot.outgoing) {
            val offer = CallOffer(
                roomId = room,
                callId = callId,
                fromNodeId = node,
                fromName = selfName(),
                media = snapshot.media.wire,
                sdp = sdp,
                createdAt = clock.nowMillis(),
                expiresAt = clock.nowMillis() + Protocol.CALL_RING_TIMEOUT_MILLIS,
                toNodeId = snapshot.peerNodeId,
            )
            scope.launch { runCatching { send(offer) } }
        } else {
            val answer = CallAnswer(
                roomId = room,
                callId = callId,
                fromNodeId = node,
                toNodeId = snapshot.peerNodeId ?: return,
                sdp = sdp,
            )
            scope.launch { runCatching { send(answer) } }
        }
    }

    private fun onLocalCandidate(candidate: IceCandidate) {
        val snapshot = _state.value
        val callId = snapshot.callId ?: return
        val room = roomId ?: return
        val peer = snapshot.peerNodeId ?: return
        val ice = CallIce(
            roomId = room,
            callId = callId,
            fromNodeId = selfNodeId(),
            toNodeId = peer,
            candidate = candidate.candidate,
            sdpMid = candidate.sdpMid,
            sdpMLineIndex = candidate.sdpMLineIndex,
            fromCaller = snapshot.outgoing,
        )
        scope.launch { runCatching { send(ice) } }
    }

    private suspend fun flushEarlyCandidates() {
        val session = media ?: return
        while (earlyCandidates.isNotEmpty()) {
            val ice = earlyCandidates.removeFirst()
            runCatching {
                session.addRemoteCandidate(IceCandidate(ice.candidate, ice.sdpMid, ice.sdpMLineIndex))
            }
        }
    }

    private fun hangUpInternal(reason: String) {
        val snapshot = _state.value
        val callId = snapshot.callId ?: return
        val room = roomId ?: return
        val peer = snapshot.peerNodeId ?: return
        val node = selfNodeId()
        scope.launch {
            runCatching { send(CallHangup(room, callId, node, peer, reason)) }
        }
        cleanup()
    }

    private fun cleanup() {
        runCatching { media?.close() }
        media = null
        remoteSdp = null
        earlyCandidates.clear()
    }

    private fun apply(event: CallEvent) {
        _state.value = CallStateMachine.reduce(_state.value, event)
    }

    private fun newCallId(): String = dev.shutchat.app.core.Ids.newId()

    /** For tests: stop the timeout ticker. */
    fun shutdown() {
        ticker?.cancel()
        ticker = null
        cleanup()
    }
}
