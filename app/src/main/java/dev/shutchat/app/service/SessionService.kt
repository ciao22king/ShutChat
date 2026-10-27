package dev.shutchat.app.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import dev.shutchat.app.ShutChatApp
import dev.shutchat.app.core.Log
import dev.shutchat.app.notify.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the mesh alive while the app is in the background.
 *
 * With no push server, a message can only arrive if something is listening, so
 * this foreground service is what makes background delivery work at all. It
 * declares `connectedDevice` because that is what it does: it holds Bluetooth
 * and LAN links. The user sees an ongoing notification and can stop it.
 */
class SessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val container = (application as? ShutChatApp)?.container ?: return
        container.notifier.ensureChannels()

        val notification = SessionNotification.build(this, peerCount = 0, roomCount = 0)
        try {
            ServiceCompat.startForeground(
                this,
                Notifier.SESSION_NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else {
                    0
                },
            )
            started = true
        } catch (e: Exception) {
            // On Android 14+ a missing permission throws rather than being
            // ignored; the app still works while in the foreground.
            Log.w(TAG, "startForeground rifiutato", e)
            stopSelf()
            return
        }

        // Keep the ongoing notification honest about how many peers are linked.
        scope.launch {
            container.mesh.peerConnected.collectLatest { refreshNotification() }
        }
        scope.launch {
            container.mesh.peerLost.collectLatest { refreshNotification() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun refreshNotification() {
        if (!started) return
        val container = (application as? ShutChatApp)?.container ?: return
        val peers = container.mesh.diagnostics.connectedPeers
        val rooms = container.mesh.diagnostics.roomsAdvertised
        runCatching {
            NotificationManagerCompat.from(this).notify(
                Notifier.SESSION_NOTIFICATION_ID,
                SessionNotification.build(this, peers, rooms),
            )
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SessionService"
        const val ACTION_STOP = "dev.shutchat.app.action.STOP_SESSION"
    }
}
