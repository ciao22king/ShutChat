package dev.shutchat.app.core

/** Severity of a log line. Mirrors the levels every platform logger exposes. */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * Where log lines go.
 *
 * The core module runs on Android and on the desktop JVM, so it cannot call
 * `android.util.Log` directly. Each platform installs its own sink at startup.
 */
fun interface LogSink {
    fun log(level: LogLevel, tag: String, message: String, error: Throwable?)
}

/**
 * Logging facade shared by every module.
 *
 * Debug lines are suppressed unless [debugEnabled] is set, which the platform
 * layer does from its own build flag: a shipped APK must not write message
 * metadata to logcat, and a release desktop build should not either.
 */
object Log {
    private const val TAG_PREFIX = "ShutChat/"

    @Volatile
    var debugEnabled: Boolean = false

    @Volatile
    var sink: LogSink = ConsoleSink

    fun d(tag: String, message: String) {
        if (!debugEnabled) return
        emit(LogLevel.DEBUG, tag, message, null)
    }

    fun i(tag: String, message: String) = emit(LogLevel.INFO, tag, message, null)

    fun w(tag: String, message: String, error: Throwable? = null) =
        emit(LogLevel.WARN, tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) =
        emit(LogLevel.ERROR, tag, message, error)

    private fun emit(level: LogLevel, tag: String, message: String, error: Throwable?) {
        // A broken sink must never take the app down with it.
        runCatching { sink.log(level, TAG_PREFIX + tag, message, error) }
    }
}

/** Default sink: one line on stderr, which is what a desktop launch shows. */
private val ConsoleSink = LogSink { level, tag, message, error ->
    System.err.println("${level.name.first()} $tag: $message")
    error?.printStackTrace()
}
