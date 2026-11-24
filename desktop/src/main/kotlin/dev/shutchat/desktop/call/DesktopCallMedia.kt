package dev.shutchat.desktop.call

import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCAnswerOptions
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCIceServer
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCPeerConnectionState
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import dev.onvoid.webrtc.media.audio.AudioOptions
import dev.onvoid.webrtc.media.audio.AudioTrack
import dev.onvoid.webrtc.media.audio.AudioTrackSource
import dev.shutchat.app.core.Log
import dev.shutchat.app.p2p.call.CallIceServers
import dev.shutchat.app.p2p.call.CallMediaFactory
import dev.shutchat.app.p2p.call.CallMediaSession
import dev.shutchat.app.p2p.call.IceCandidate
import dev.shutchat.app.p2p.protocol.CallMedia

/**
 * Desktop side of a call, on top of the native libwebrtc Java wrapper.
 *
 * Audio only: a desktop has no camera in the mesh's trust model, and a
 * videochiamata started from the phone still connects — the answer simply
 * declines the video section, which WebRTC handles without renegotiation.
 *
 * The native library is loaded lazily and its absence is not fatal: if it cannot
 * load, [isSupported] is false and the caller tells the user calls are
 * unavailable instead of crashing the app.
 */
class DesktopCallMediaFactory : CallMediaFactory {

    override fun create(
        onLocalSdp: (String) -> Unit,
        onLocalCandidate: (IceCandidate) -> Unit,
        onConnected: () -> Unit,
        onFailed: (String) -> Unit,
    ): CallMediaSession = DesktopCallMedia(onLocalSdp, onLocalCandidate, onConnected, onFailed)
}

class DesktopCallMedia(
    private val onLocalSdp: (String) -> Unit,
    private val onLocalCandidate: (IceCandidate) -> Unit,
    private val onConnected: () -> Unit,
    private val onFailed: (String) -> Unit,
) : CallMediaSession {

    private val factory: PeerConnectionFactory? = runCatching { PeerConnectionFactory() }
        .onFailure { Log.w(TAG, "libwebrtc non caricabile: ${it.message}") }
        .getOrNull()

    override val isSupported: Boolean get() = factory != null

    private var peerConnection: RTCPeerConnection? = null
    private var audioSource: AudioTrackSource? = null
    private var audioTrack: AudioTrack? = null
    private var closed = false

    override suspend fun startOutgoing(callId: String, media: CallMedia): Boolean {
        val pc = prepare() ?: return false
        pc.createOffer(
            RTCOfferOptions(),
            object : CreateSessionDescriptionObserver {
                override fun onSuccess(description: RTCSessionDescription) {
                    pc.setLocalDescription(
                        description,
                        object : SetSessionDescriptionObserver {
                            override fun onSuccess() = onLocalSdp(description.sdp)
                            override fun onFailure(error: String) = fail(error)
                        },
                    )
                }

                override fun onFailure(error: String) = fail(error)
            },
        )
        return true
    }

    override suspend fun acceptIncoming(callId: String, media: CallMedia, remoteSdp: String): Boolean {
        val pc = prepare() ?: return false
        pc.setRemoteDescription(
            RTCSessionDescription(RTCSdpType.OFFER, remoteSdp),
            object : SetSessionDescriptionObserver {
                override fun onSuccess() {
                    pc.createAnswer(
                        RTCAnswerOptions(),
                        object : CreateSessionDescriptionObserver {
                            override fun onSuccess(description: RTCSessionDescription) {
                                pc.setLocalDescription(
                                    description,
                                    object : SetSessionDescriptionObserver {
                                        override fun onSuccess() = onLocalSdp(description.sdp)
                                        override fun onFailure(error: String) = fail(error)
                                    },
                                )
                            }

                            override fun onFailure(error: String) = fail(error)
                        },
                    )
                }

                override fun onFailure(error: String) = fail(error)
            },
        )
        return true
    }

    override suspend fun applyRemoteAnswer(remoteSdp: String): Boolean {
        val pc = peerConnection ?: return false
        pc.setRemoteDescription(
            RTCSessionDescription(RTCSdpType.ANSWER, remoteSdp),
            object : SetSessionDescriptionObserver {
                override fun onSuccess() = Unit
                override fun onFailure(error: String) = fail(error)
            },
        )
        return true
    }

    override suspend fun addRemoteCandidate(candidate: IceCandidate) {
        val pc = peerConnection ?: return
        runCatching {
            pc.addIceCandidate(RTCIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate))
        }.onFailure { Log.d(TAG, "candidato ICE scartato: ${it.message}") }
    }

    override fun setMuted(muted: Boolean) {
        audioTrack?.setEnabled(!muted)
    }

    override fun setSpeakerOn(speaker: Boolean) {
        // The desktop plays through whatever the system default output is; there
        // is no earpiece to switch away from, so the toggle is informational.
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { peerConnection?.close() }
        runCatching { audioTrack?.dispose() }
        runCatching { factory?.dispose() }
        peerConnection = null
        audioTrack = null
        audioSource = null
    }

    private fun prepare(): RTCPeerConnection? {
        val factory = factory ?: return null
        peerConnection?.let { return it }

        val config = RTCConfiguration()
        CallIceServers.default.forEach { server ->
            val ice = RTCIceServer()
            ice.urls = server.urls
            server.username?.let { ice.username = it }
            server.credential?.let { ice.password = it }
            config.iceServers.add(ice)
        }

        val connection = factory.createPeerConnection(config, observer) ?: run {
            fail("peer connection non creata")
            return null
        }
        val source = factory.createAudioSource(
            AudioOptions().apply {
                echoCancellation = true
                noiseSuppression = true
                autoGainControl = true
            },
        )
        val track = factory.createAudioTrack("shutchat-audio", source)
        connection.addTrack(track, listOf(STREAM_ID))

        peerConnection = connection
        audioSource = source
        audioTrack = track
        return connection
    }

    private val observer = object : PeerConnectionObserver {
        override fun onIceCandidate(candidate: RTCIceCandidate) {
            onLocalCandidate(IceCandidate(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex))
        }

        override fun onConnectionChange(state: RTCPeerConnectionState) {
            when (state) {
                RTCPeerConnectionState.CONNECTED -> onConnected()
                RTCPeerConnectionState.FAILED -> fail("connessione persa")
                else -> Unit
            }
        }
    }

    private fun fail(reason: String) {
        Log.w(TAG, "media: $reason")
        onFailed(reason)
    }

    private companion object {
        const val TAG = "DesktopCallMedia"
        const val STREAM_ID = "shutchat"
    }
}
