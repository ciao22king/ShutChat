package dev.shutchat.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import dev.shutchat.app.MainActivity
import dev.shutchat.app.R
import dev.shutchat.app.ShutChatApp
import dev.shutchat.app.core.Log
import dev.shutchat.app.notify.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles the "Rispondi" action on a message notification.
 *
 * It runs as a receiver rather than through the activity so a reply does not
 * force the UI open, which would defeat the point of a quick reply.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REPLY) return
        val chatId = intent.getStringExtra(MainActivity.EXTRA_CHAT_ID) ?: return
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_REPLY)
            ?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return

        val app = context.applicationContext as? ShutChatApp ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.container.sendText(chatId, text)
            } catch (e: Exception) {
                Log.w(TAG, "risposta rapida non inviata", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "NotificationAction"
        const val ACTION_REPLY = "dev.shutchat.app.action.REPLY"
    }
}

/**
 * Ongoing notification that the app is listening.
 *
 * ShutChat has no push server, so receiving a message while the app is closed
 * requires a process to be alive. This notification is the honest disclosure of
 * that: the user can see the app is reachable, and can stop it from the shade.
 */
object SessionNotification {

    fun build(context: Context, peerCount: Int, roomCount: Int): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getBroadcast(
            context,
            1,
            Intent(SessionService.ACTION_STOP).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val summary = when {
            peerCount == 0 -> "In ascolto su $roomCount stanze"
            peerCount == 1 -> "1 peer collegato"
            else -> "$peerCount peer collegati"
        }
        return NotificationCompat.Builder(context, Notifier.CHANNEL_SESSION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("ShutChat è attivo")
            .setContentText(summary)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(R.drawable.ic_notification, "Interrompi", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}

/** Lets the user stop the listener from the notification shade. */
class SessionStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SessionService.ACTION_STOP) return
        context.stopService(Intent(context, SessionService::class.java))
    }
}
