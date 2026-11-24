package dev.shutchat.app.core

import android.util.Log as PlatformLog
import dev.shutchat.app.BuildConfig

/**
 * Routes the shared [Log] facade to logcat.
 *
 * Debug lines stay suppressed in release builds, because the protocol carries
 * message metadata in log arguments and a shipped APK must not write that to a
 * log any other app can read.
 */
object AndroidLog {

    fun install() {
        Log.debugEnabled = BuildConfig.DEBUG
        Log.sink = LogSink { level, tag, message, error ->
            when (level) {
                LogLevel.DEBUG -> PlatformLog.d(tag, message)
                LogLevel.INFO -> PlatformLog.i(tag, message)
                LogLevel.WARN -> PlatformLog.w(tag, message, error)
                LogLevel.ERROR -> PlatformLog.e(tag, message, error)
            }
        }
    }
}
