package dev.shutchat.app.p2p.lan

import android.content.Context
import android.net.wifi.WifiManager

/**
 * Android's multicast lock.
 *
 * Without it the Wi-Fi stack filters multicast traffic and LAN discovery never
 * receives a single beacon, which looks exactly like "nobody is nearby".
 */
class AndroidMulticastLock(private val context: Context) : MulticastLockProvider {

    override fun acquire(): AutoCloseable {
        val lock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock("shutchat-lan")
            ?.apply {
                setReferenceCounted(false)
                runCatching { acquire() }
            }
        return AutoCloseable { runCatching { lock?.release() } }
    }
}
