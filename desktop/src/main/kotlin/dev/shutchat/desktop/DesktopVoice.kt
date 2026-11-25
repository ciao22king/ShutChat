package dev.shutchat.desktop

import dev.shutchat.app.core.Log
import java.io.ByteArrayOutputStream
import java.io.File
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlinx.coroutines.flow.MutableStateFlow

/** A finished recording: the file to send and how long it runs. */
data class DesktopRecording(val file: File, val durationMillis: Long)

/**
 * Voice notes, recorded as 16 kHz mono WAV.
 *
 * Android records AAC in an MP4 container; WAV is the format the JDK can write
 * without shipping an encoder, and every player that opens the Android files
 * opens this one too, so a note recorded here plays on the phone.
 */
class DesktopVoiceRecorder(private val files: DesktopFiles = DesktopFiles()) {

    private val format = AudioFormat(16_000f, 16, 1, true, false)

    private var line: TargetDataLine? = null
    private var thread: Thread? = null
    private var buffer: ByteArrayOutputStream? = null
    private var startedAt = 0L

    val recording = MutableStateFlow(false)
    val elapsedMillis = MutableStateFlow(0L)

    /** @return false when there is no capture device or it is busy. */
    fun start(): Boolean {
        if (recording.value) return false
        val info = DataLine.Info(TargetDataLine::class.java, format)
        val opened = runCatching { AudioSystem.getLine(info) as TargetDataLine }
            .onFailure { Log.w(TAG, "no microphone", it) }
            .getOrNull() ?: return false
        val started = runCatching { opened.open(format); opened.start() }
            .onFailure { Log.w(TAG, "cannot open the microphone", it) }
            .isSuccess
        if (!started) return false

        line = opened
        buffer = ByteArrayOutputStream()
        startedAt = System.currentTimeMillis()
        recording.value = true
        elapsedMillis.value = 0
        thread = Thread {
            val sink = buffer ?: return@Thread
            val chunk = ByteArray(4096)
            while (recording.value) {
                val read = runCatching { opened.read(chunk, 0, chunk.size) }.getOrDefault(-1)
                if (read <= 0) break
                sink.write(chunk, 0, read)
                elapsedMillis.value = System.currentTimeMillis() - startedAt
            }
        }.apply {
            isDaemon = true
            name = "shutchat-voice-recorder"
            start()
        }
        return true
    }

    /** Stops and writes the WAV. @return null when nothing usable was captured. */
    fun stop(): DesktopRecording? {
        if (!recording.value) return null
        recording.value = false
        runCatching { thread?.join(1_000) }
        thread = null
        val opened = line ?: return null
        line = null
        runCatching { opened.stop(); opened.close() }

        val bytes = buffer?.toByteArray() ?: return null
        buffer = null
        val duration = elapsedMillis.value
        if (bytes.isEmpty() || duration < MIN_MILLIS) return null

        val destination = files.voice()
        runCatching {
            AudioInputStream(bytes.inputStream(), format, (bytes.size / format.frameSize).toLong())
                .use { AudioSystem.write(it, AudioFileFormat.Type.WAVE, destination) }
        }.onFailure {
            Log.w(TAG, "cannot write ${destination.name}", it)
            return null
        }
        return DesktopRecording(destination, duration)
    }

    fun cancel() {
        recording.value = false
        runCatching { thread?.join(500) }
        thread = null
        line?.let { runCatching { it.stop(); it.close() } }
        line = null
        buffer = null
        elapsedMillis.value = 0
    }

    private companion object {
        const val TAG = "DesktopVoiceRecorder"
        const val MIN_MILLIS = 600L
    }
}

/** Plays a voice note and reports its position, one note at a time. */
class DesktopVoicePlayer {

    private var clip: Clip? = null
    private var watcher: Thread? = null

    /** Message id of the note currently playing, null when idle. */
    val playing = MutableStateFlow<String?>(null)
    val progress = MutableStateFlow(0f)

    fun toggle(messageId: String, file: File) {
        if (playing.value == messageId) {
            stop()
            return
        }
        stop()
        val opened = runCatching {
            AudioSystem.getClip().apply {
                open(AudioSystem.getAudioInputStream(file))
                start()
            }
        }.onFailure { Log.w(TAG, "cannot play ${file.name}", it) }.getOrNull() ?: return

        clip = opened
        playing.value = messageId
        progress.value = 0f
        watcher = Thread {
            while (opened.isRunning || opened.isActive) {
                val total = opened.microsecondLength
                if (total > 0) {
                    progress.value = (opened.microsecondPosition.toFloat() / total).coerceIn(0f, 1f)
                }
                Thread.sleep(120)
            }
            if (playing.value == messageId) stop()
        }.apply {
            isDaemon = true
            name = "shutchat-voice-player"
            start()
        }
    }

    fun stop() {
        runCatching { clip?.stop(); clip?.close() }
        clip = null
        watcher = null
        playing.value = null
        progress.value = 0f
    }

    private companion object {
        const val TAG = "DesktopVoicePlayer"
    }
}
