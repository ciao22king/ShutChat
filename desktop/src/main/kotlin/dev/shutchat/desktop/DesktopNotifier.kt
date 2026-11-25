package dev.shutchat.desktop

import dev.shutchat.app.core.Log
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

/**
 * Desktop notifications through the system tray.
 *
 * There is no notification daemon on every Linux desktop, so this degrades to
 * silence rather than failing: a missing tray must never stop a message from
 * arriving.
 */
class DesktopNotifier {

    private var icon: TrayIcon? = null

    private fun tray(): TrayIcon? {
        icon?.let { return it }
        if (!SystemTray.isSupported()) return null
        return runCatching {
            val tray = SystemTray.getSystemTray()
            val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
            val created = TrayIcon(image, "ShutChat").apply { isImageAutoSize = true }
            tray.add(created)
            icon = created
            created
        }.onFailure { Log.w(TAG, "no system tray", it) }.getOrNull()
    }

    fun notify(title: String, body: String) {
        val tray = tray() ?: return
        runCatching { tray.displayMessage(title, body, TrayIcon.MessageType.INFO) }
            .onFailure { Log.w(TAG, "notification refused", it) }
    }

    fun dispose() {
        icon?.let { runCatching { SystemTray.getSystemTray().remove(it) } }
        icon = null
    }

    private companion object {
        const val TAG = "DesktopNotifier"
    }
}
