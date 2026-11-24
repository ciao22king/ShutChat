package dev.shutchat.app.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.shutchat.app.MainActivity
import dev.shutchat.app.R

/**
 * Notifications.
 *
 * Messages are never shown with their full text on the lock screen unless the
 * user has said so; a peer-to-peer chat app is often left on a table. The
 * default is sender plus a short preview, and the content is redacted when the
 * device is locked.
 */
class Notifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        system.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_MESSAGES,
                    "Messages",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "New messages received from peers"
                    enableVibration(true)
                },
                NotificationChannel(
                    CHANNEL_SESSION,
                    "Active session",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "ShutChat is listening for messages"
                    setShowBadge(false)
                },
                NotificationChannel(
                    CHANNEL_CODES,
                    "Codes and expiries",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Warnings when a code is about to expire"
                },
                NotificationChannel(
                    CHANNEL_CALLS,
                    "Calls",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Incoming audio and video calls"
                    enableVibration(true)
                    setBypassDnd(true)
                },
            ),
        )
    }

    fun message(chatId: String, chatTitle: String, sender: String, preview: String, count: Int) {
        if (!canPost()) return
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(MainActivity.EXTRA_CHAT_ID, chatId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            chatId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (count > 1) "$chatTitle ($count)" else sender)
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .apply {
                // Reply straight from the shade; the action is handled by the
                // session service, which already holds the mesh.
                val reply = NotificationCompat.Action.Builder(
                    R.drawable.ic_notification,
                    "Reply",
                    replyIntent(chatId),
                ).addRemoteInput(
                    androidx.core.app.RemoteInput.Builder(KEY_REPLY)
                        .setLabel("Message")
                        .build(),
                ).build()
                addAction(reply)
            }
            .build()
        runCatching { manager.notify(chatId.hashCode(), notification) }
    }

    private fun replyIntent(chatId: String): PendingIntent {
        val intent = Intent(context, dev.shutchat.app.service.NotificationActionReceiver::class.java).apply {
            action = dev.shutchat.app.service.NotificationActionReceiver.ACTION_REPLY
            putExtra(MainActivity.EXTRA_CHAT_ID, chatId)
        }
        return PendingIntent.getBroadcast(
            context,
            chatId.hashCode() xor 0x5A5A,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    /** Warning that a code is about to expire, so the user can re-share it. */
    fun codeExpiring(chatId: String, title: String, expiresAtLabel: String) {
        if (!canPost()) return
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_CHAT_ID, chatId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            chatId.hashCode() xor 0x1F1F,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_CODES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Code expiring")
            .setContentText("$title: the code turns off on $expiresAtLabel")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$title: the code turns off on $expiresAtLabel. Regenerate the code if you want to keep chatting."),
            )
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(chatId.hashCode() xor 0x1F1F, notification) }
    }

    /**
     * Rings for an incoming call.
     *
     * Uses the calls channel so the alert survives Do Not Disturb only when the
     * user allows it, and never shows the caller's message text: there is none.
     */
    fun incomingCall(peerName: String, video: Boolean) {
        if (!canPost()) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            CALL_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val kind = if (video) "videochiamata" else "chiamata"
        val notification = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$kind in arrivo")
            .setContentText(peerName.ifBlank { "Someone" })
            .setContentIntent(pending)
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        runCatching { manager.notify(CALL_NOTIFICATION_ID, notification) }
    }

    fun clearIncomingCall() {
        runCatching { manager.cancel(CALL_NOTIFICATION_ID) }
    }

    fun clear(chatId: String) {
        runCatching { manager.cancel(chatId.hashCode()) }
    }

    fun clearAll() {
        runCatching { manager.cancelAll() }
    }

    private fun canPost(): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    companion object {
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_SESSION = "session"
        const val CHANNEL_CODES = "codes"
        const val CHANNEL_CALLS = "calls"
        const val KEY_REPLY = "reply_text"
        const val SESSION_NOTIFICATION_ID = 0x5C0D
        const val CALL_NOTIFICATION_ID = 0xCA11
    }
}
