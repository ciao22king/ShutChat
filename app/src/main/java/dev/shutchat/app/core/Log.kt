package dev.shutchat.app.core

import android.util.Log as AndroidLog
import dev.shutchat.app.BuildConfig

/**
 * Logging facade. Debug/verbose traffic is compiled out of release builds so a
 * shipped APK never writes message metadata into logcat.
 */
object Log {
    private const val TAG_PREFIX = "ShutChat/"

    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) AndroidLog.d(TAG_PREFIX + tag, message)
    }

    fun i(tag: String, message: String) {
        AndroidLog.i(TAG_PREFIX + tag, message)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        AndroidLog.w(TAG_PREFIX + tag, message, error)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        AndroidLog.e(TAG_PREFIX + tag, message, error)
    }
}
