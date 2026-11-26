package dev.shutchat.app.ui.screens.call

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shutchat.app.MainActivity
import dev.shutchat.app.ShutChatApp
import dev.shutchat.app.p2p.call.CallPhase
import dev.shutchat.app.ui.theme.ShutChatTheme

/**
 * The ringing screen shown over whatever the user was doing.
 *
 * A peer to peer call has no push server behind it, so the way to reach someone
 * whose app is closed is to raise this screen from the notification that the
 * session service posts. It shows over the lock screen and turns the display on,
 * which is what makes an incoming call feel like a call and not like a message.
 */
class IncomingCallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        val caller = intent.getStringExtra(EXTRA_CALLER).orEmpty()
        val video = intent.getBooleanExtra(EXTRA_VIDEO, false)
        val container = (application as ShutChatApp).container

        setContent {
            ShutChatTheme {
                val call by container.calls.state.collectAsState()
                // The caller may hang up while this is up, and the controller
                // gives up on its own after the ring timeout.
                LaunchedEffect(call.phase) {
                    if (call.phase != CallPhase.RINGING && !isFinishing) finish()
                }
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0E0E12)) {
                    RingingScreen(
                        caller = call.peerName.ifBlank { caller },
                        video = video,
                        onAnswer = {
                            container.calls.accept()
                            container.notifier.clearIncomingCall()
                            startActivity(
                                Intent(this@IncomingCallActivity, MainActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                            finish()
                        },
                        onDecline = {
                            container.calls.decline()
                            container.notifier.clearIncomingCall()
                            finish()
                        },
                    )
                }
            }
        }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)
                ?.requestDismissKeyguard(this, null)
        }
    }

    companion object {
        const val EXTRA_CALLER = "dev.shutchat.app.extra.CALLER"
        const val EXTRA_VIDEO = "dev.shutchat.app.extra.VIDEO"
    }
}

@Composable
private fun RingingScreen(
    caller: String,
    video: Boolean,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(Color(0xFF2A2A33)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = caller.take(1).uppercase(),
                color = Color.White,
                fontSize = 48.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = caller,
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (video) "Incoming video call" else "Incoming call",
            color = Color(0xFFB4B4C0),
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            CallButton("Decline", Icons.Filled.CallEnd, Color(0xFFE5484D), onDecline)
            CallButton("Answer", Icons.Filled.Call, Color(0xFF2FBF71), onAnswer)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CallButton(label: String, icon: ImageVector, tint: Color, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(tint)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = Color(0xFFB4B4C0), style = MaterialTheme.typography.bodyMedium)
    }
}
