package dev.shutchat.app.ui.screens.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.shutchat.app.core.Format
import dev.shutchat.app.di.AppContainer
import dev.shutchat.app.p2p.call.AndroidCallMedia
import dev.shutchat.app.p2p.call.CallPhase
import dev.shutchat.app.p2p.protocol.CallMedia
import dev.shutchat.app.ui.component.InitialsAvatar
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer

/**
 * Full-screen call UI.
 *
 * Drawn above every destination rather than as a route: a call can arrive while
 * the user is on any screen, and navigating away from a conversation must not
 * hide the ringing phone.
 */
@Composable
fun CallOverlay(container: AppContainer) {
    val call by container.calls.state.collectAsState()
    if (call.phase == CallPhase.IDLE) return

    val video = call.media == CallMedia.VIDEO && call.phase != CallPhase.ENDED
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.phase) {
        while (call.phase.isLive) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xF0101014)) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (video) VideoStage(container)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Spacer(Modifier.height(48.dp))

                if (!video) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        InitialsAvatar(
                            seed = call.peerNodeId ?: "call",
                            name = call.peerName.ifBlank { "Call" },
                            size = 96.dp,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = call.peerName.ifBlank { "Call" },
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White,
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val status = when (call.phase) {
                        CallPhase.DIALING -> "Calling..."
                        CallPhase.RINGING ->
                            if (call.media == CallMedia.VIDEO) "Incoming video call" else "Incoming call"

                        CallPhase.CONNECTING -> "Connecting..."
                        CallPhase.ACTIVE -> Format.duration(call.elapsedMillis(now))
                        CallPhase.ENDED -> call.endReason?.label ?: "Call ended"
                        CallPhase.IDLE -> ""
                    }
                    Text(
                        text = status,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFFD6D6DE),
                        fontWeight = FontWeight.Medium,
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when (call.phase) {
                        CallPhase.RINGING -> {
                            CallAction(
                                icon = Icons.Default.CallEnd,
                                label = "Decline",
                                tint = Color(0xFFE5484D),
                                onClick = { container.calls.decline() },
                            )
                            CallAction(
                                icon = Icons.Default.Call,
                                label = "Accept",
                                tint = Color(0xFF30A46C),
                                onClick = { container.calls.accept() },
                            )
                        }

                        CallPhase.DIALING -> CallAction(
                            icon = Icons.Default.CallEnd,
                            label = "Cancel",
                            tint = Color(0xFFE5484D),
                            onClick = { container.calls.hangUp() },
                        )

                        CallPhase.CONNECTING, CallPhase.ACTIVE -> {
                            CallAction(
                                icon = if (call.muted) Icons.Default.MicOff else Icons.Default.Mic,
                                label = if (call.muted) "Unmute" else "Mute",
                                tint = if (call.muted) Color(0xFFE5484D) else Color(0xFF2A2A33),
                                onClick = { container.calls.toggleMute() },
                            )
                            CallAction(
                                icon = if (call.speaker) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                                label = "Speaker",
                                tint = if (call.speaker) Color(0xFF3E63DD) else Color(0xFF2A2A33),
                                onClick = { container.calls.toggleSpeaker() },
                            )
                            CallAction(
                                icon = Icons.Default.CallEnd,
                                label = "Close",
                                tint = Color(0xFFE5484D),
                                onClick = {
                                    container.calls.hangUp()
                                    container.notifier.clearIncomingCall()
                                },
                            )
                        }

                        CallPhase.ENDED -> CallAction(
                            icon = Icons.Default.CallEnd,
                            label = "Close",
                            tint = Color(0xFF2A2A33),
                            onClick = {
                                container.calls.dismiss()
                                container.notifier.clearIncomingCall()
                            },
                        )

                        CallPhase.IDLE -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun CallAction(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = CircleShape,
            color = tint,
            modifier = Modifier.size(64.dp),
            onClick = onClick,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = Color.White)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0xFFB8B8C4))
    }
}

/**
 * Remote video full screen, local preview in a corner.
 *
 * Both surfaces come from WebRTC and must be released with the call; the
 * disposable effect is what guarantees that even if the overlay is removed while
 * the call is still up.
 */
@Composable
private fun VideoStage(container: AppContainer) {
    val media = container.calls.currentMedia as? AndroidCallMedia
    val eglContext: EglBase.Context? = remember(media) { media?.eglContext() }
    var remote by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var local by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    if (eglContext == null) {
        Box(Modifier.fillMaxSize().background(Color(0xFF141418)))
        return
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF141418))) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                SurfaceViewRenderer(context).apply {
                    init(eglContext, null)
                    setEnableHardwareScaler(true)
                    setMirror(false)
                    remote = this
                }
            },
        )
        AndroidView(
            modifier = Modifier
                .padding(16.dp)
                .size(width = 110.dp, height = 150.dp)
                .align(Alignment.TopEnd),
            factory = { context ->
                SurfaceViewRenderer(context).apply {
                    init(eglContext, null)
                    setEnableHardwareScaler(true)
                    setMirror(true)
                    local = this
                }
            },
        )
    }

    DisposableEffect(media, local, remote) {
        media?.attachRenderers(local, remote)
        onDispose {
            runCatching { remote?.release() }
            runCatching { local?.release() }
        }
    }
}
