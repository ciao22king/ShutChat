package dev.shutchat.app.p2p.call

import android.content.Context
import android.media.AudioManager
import dev.shutchat.app.core.Log
import dev.shutchat.app.p2p.protocol.CallMedia
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate as RtcIceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Android side of a call: WebRTC audio, plus video when the call asked for it.
 *
 * Signalling never passes through here. [CallController] sends the offer, the
 * answer and the ICE candidates as sealed envelopes; this class only turns SDP
 * strings into media and hands back the local SDP as the callbacks fire.
 *
 * Media goes peer to peer over the best path ICE finds, so the public relay
 * carries a handful of small signalling frames instead of a voice stream.
 */
class AndroidCallMediaFactory(private val context: Context) : CallMediaFactory {

    override fun create(
        onLocalSdp: (String) -> Unit,
        onLocalCandidate: (IceCandidate) -> Unit,
        onConnected: () -> Unit,
        onFailed: (String) -> Unit,
    ): CallMediaSession {
        PeerConnectionFactoryHolder.ensure(context)
        return AndroidCallMedia(context, onLocalSdp, onLocalCandidate, onConnected, onFailed)
    }
}

class AndroidCallMedia(
    private val context: Context,
    private val onLocalSdp: (String) -> Unit,
    private val onLocalCandidate: (IceCandidate) -> Unit,
    private val onConnected: () -> Unit,
    private val onFailed: (String) -> Unit,
) : CallMediaSession {

    override val isSupported: Boolean get() = PeerConnectionFactoryHolder.available

    private val eglBase: EglBase by lazy { EglBase.create() }

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null

    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null

    private var wantsVideo = false
    private var closed = false
    private var eglCreated = false

    /** UI hook: attach the two surfaces before or during the call. */
    fun attachRenderers(local: SurfaceViewRenderer?, remote: SurfaceViewRenderer?) {
        localRenderer = local
        remoteRenderer = remote
        local?.let { videoTrack?.addSink(it) }
    }

    /** The context a renderer must initialise with. */
    fun eglContext(): EglBase.Context = eglBase.eglBaseContext

    override suspend fun startOutgoing(callId: String, media: CallMedia): Boolean {
        wantsVideo = media == CallMedia.VIDEO
        if (!prepare()) return false
        val pc = peerConnection ?: return false
        pc.createOffer(
            object : SimpleSdpObserver() {
                override fun onCreateSuccess(description: SessionDescription) {
                    pc.setLocalDescription(
                        object : SimpleSdpObserver() {
                            override fun onSetSuccess() = onLocalSdp(description.description)
                            override fun onSetFailure(error: String?) = fail(error)
                        },
                        description,
                    )
                }

                override fun onCreateFailure(error: String?) = fail(error)
            },
            MediaConstraints(),
        )
        return true
    }

    override suspend fun acceptIncoming(callId: String, media: CallMedia, remoteSdp: String): Boolean {
        wantsVideo = media == CallMedia.VIDEO
        if (!prepare()) return false
        val pc = peerConnection ?: return false
        pc.setRemoteDescription(
            object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    pc.createAnswer(
                        object : SimpleSdpObserver() {
                            override fun onCreateSuccess(description: SessionDescription) {
                                pc.setLocalDescription(
                                    object : SimpleSdpObserver() {
                                        override fun onSetSuccess() =
                                            onLocalSdp(description.description)

                                        override fun onSetFailure(error: String?) = fail(error)
                                    },
                                    description,
                                )
                            }

                            override fun onCreateFailure(error: String?) = fail(error)
                        },
                        MediaConstraints(),
                    )
                }

                override fun onSetFailure(error: String?) = fail(error)
            },
            SessionDescription(SessionDescription.Type.OFFER, remoteSdp),
        )
        return true
    }

    override suspend fun applyRemoteAnswer(remoteSdp: String): Boolean {
        val pc = peerConnection ?: return false
        pc.setRemoteDescription(
            object : SimpleSdpObserver() {
                override fun onSetFailure(error: String?) = fail(error)
            },
            SessionDescription(SessionDescription.Type.ANSWER, remoteSdp),
        )
        return true
    }

    override suspend fun addRemoteCandidate(candidate: IceCandidate) {
        val pc = peerConnection ?: return
        runCatching {
            pc.addIceCandidate(
                RtcIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate),
            )
        }.onFailure { Log.d(TAG, "ICE candidate dropped: ${it.message}") }
    }

    override fun setMuted(muted: Boolean) {
        audioTrack?.setEnabled(!muted)
    }

    override fun setSpeakerOn(speaker: Boolean) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            @Suppress("DEPRECATION")
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = speaker
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { videoCapturer?.stopCapture() }
        runCatching { videoCapturer?.dispose() }
        videoCapturer = null
        runCatching { localRenderer?.let { renderer -> videoTrack?.removeSink(renderer) } }
        runCatching { remoteRenderer?.release() }
        runCatching { localRenderer?.release() }
        remoteRenderer = null
        localRenderer = null
        runCatching { videoSource?.dispose() }
        runCatching { audioSource?.dispose() }
        runCatching { peerConnection?.close() }
        runCatching { peerConnection?.dispose() }
        runCatching { surfaceHelper?.dispose() }
        runCatching { factory?.dispose() }
        if (eglCreated) runCatching { eglBase.release() }
        peerConnection = null
        factory = null
        audioTrack = null
        videoTrack = null
        videoSource = null
        audioSource = null
        surfaceHelper = null
    }

    // ------------------------------------------------------------------ setup

    private fun prepare(): Boolean {
        if (!isSupported) return false
        if (peerConnection != null) return true

        eglCreated = true
        val eglContext = eglBase.eglBaseContext
        val factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
            .createPeerConnectionFactory()
        this.factory = factory

        val audioSource = factory.createAudioSource(MediaConstraints())
        val audioTrack = factory.createAudioTrack("shutchat-audio", audioSource)
        this.audioSource = audioSource
        this.audioTrack = audioTrack

        val config = PeerConnection.RTCConfiguration(
            CallIceServers.default.map { server ->
                PeerConnection.IceServer.builder(server.urls)
                    .setUsername(server.username ?: "")
                    .setPassword(server.credential ?: "")
                    .createIceServer()
            },
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val pc = factory.createPeerConnection(config, observer)
        if (pc == null) {
            fail("peer connection not created")
            return false
        }
        peerConnection = pc
        pc.addTrack(audioTrack, listOf(STREAM_ID))

        if (wantsVideo) startVideo(factory, pc)
        return true
    }

    private fun startVideo(factory: PeerConnectionFactory, pc: PeerConnection) {
        val enumerator = Camera2Enumerator(context)
        val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
            ?: run {
                Log.w(TAG, "no camera, the call stays audio")
                return
            }
        val capturer = enumerator.createCapturer(name, null) ?: return
        val helper = SurfaceTextureHelper.create("shutchat-capture", eglBase.eglBaseContext)
        val source = factory.createVideoSource(capturer.isScreencast)
        capturer.initialize(helper, context, source.capturerObserver)
        capturer.startCapture(640, 480, 24)
        val track = factory.createVideoTrack("shutchat-video", source)

        videoCapturer = capturer
        surfaceHelper = helper
        videoSource = source
        videoTrack = track
        pc.addTrack(track, listOf(STREAM_ID))
        localRenderer?.let { track.addSink(it) }
    }

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: RtcIceCandidate) {
            onLocalCandidate(IceCandidate(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex))
        }

        override fun onIceCandidatesRemoved(candidates: Array<out RtcIceCandidate>) = Unit

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED,
                -> onConnected()

                PeerConnection.IceConnectionState.FAILED -> fail("network unreachable")
                else -> Unit
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit

        override fun onAddStream(stream: MediaStream) = Unit

        override fun onRemoveStream(stream: MediaStream) = Unit

        override fun onDataChannel(channel: DataChannel) = Unit

        override fun onRenegotiationNeeded() = Unit

        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
            attachRemote(receiver.track())
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            attachRemote(transceiver.receiver.track())
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> onConnected()
                PeerConnection.PeerConnectionState.FAILED -> fail("connection lost")
                else -> Unit
            }
        }
    }

    private fun attachRemote(track: MediaStreamTrack?) {
        val video = track as? VideoTrack ?: return
        remoteRenderer?.let { video.addSink(it) }
    }

    private fun fail(reason: String?) {
        Log.w(TAG, "media: ${reason ?: "errore sconosciuto"}")
        onFailed(reason ?: "media error")
    }

    /** Collapses the four callbacks every SDP step needs into the two that matter. */
    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }

    private companion object {
        const val TAG = "AndroidCallMedia"
        const val STREAM_ID = "shutchat"
    }
}

/**
 * Initialises the native WebRTC library once per process.
 *
 * `PeerConnectionFactory.initialize` is process-wide and must run before the
 * first factory is built; doing it in a holder keeps that ordering out of the
 * call code.
 */
private object PeerConnectionFactoryHolder {

    @Volatile
    private var initialised = false

    @Volatile
    var available: Boolean = false
        private set

    fun ensure(context: Context): Boolean {
        if (initialised) return available
        synchronized(this) {
            if (initialised) return available
            initialised = true
            available = runCatching {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions(),
                )
                true
            }.onFailure { Log.e(TAG, "WebRTC not available", it) }
                .getOrDefault(false)
            return available
        }
    }

    private const val TAG = "AndroidCallMedia"
}
