package dev.shutchat.app.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import dev.shutchat.app.core.Log
import java.io.File

/**
 * Records a voice message to a local file.
 *
 * The output is AAC inside an MP4 container rather than raw PCM: a voice note is
 * sent over Bluetooth and over a public broker, so the bytes on the wire are the
 * thing that decides whether it arrives. Roughly 64 kbit/s mono is transparent
 * enough for speech and about twenty times smaller than uncompressed audio.
 *
 * The recorder writes straight into the staging directory the transfer layer
 * reads from, so sending a voice note needs no extra copy of the file.
 *
 * `MediaRecorder` has no "is this still running" query and no idempotent stop:
 * [stop] after a failure throws, and [stop] twice throws. Every call here is
 * therefore guarded and leaves the object in a state where the next [start]
 * works, because a recorder that wedges after one error would silently break
 * voice notes for the rest of the session.
 */
class VoiceRecorder(
    private val context: Context,
    private val store: MediaStore,
) {

    /** A finished recording: the file on disk and how long it ran. */
    data class Recording(val file: File, val durationMillis: Long)

    private var recorder: MediaRecorder? = null
    private var target: File? = null
    private var startedAtMillis = 0L

    val isRecording: Boolean get() = recorder != null

    /** Starts capturing. False means the microphone is busy or unavailable. */
    fun start(): Boolean {
        if (recorder != null) return false

        val file = store.newVoiceFile()
        val mediaRecorder = newRecorder()
        return try {
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mediaRecorder.setAudioEncodingBitRate(BIT_RATE)
            mediaRecorder.setAudioSamplingRate(SAMPLE_RATE)
            mediaRecorder.setOutputFile(file.absolutePath)
            mediaRecorder.prepare()
            mediaRecorder.start()

            recorder = mediaRecorder
            target = file
            startedAtMillis = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            Log.w(TAG, "registrazione non avviata", e)
            runCatching { mediaRecorder.release() }
            store.delete(file)
            recorder = null
            target = null
            false
        }
    }

    /**
     * Peak amplitude since the last call, 0..32767.
     *
     * Polled by the UI to draw the level meter. It is read-only and safe to call
     * while stopped, so the caller does not have to track the lifecycle.
     */
    fun amplitude(): Int = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)

    /** Milliseconds since [start], for the on-screen counter. */
    fun elapsedMillis(): Long =
        if (recorder == null) 0L else System.currentTimeMillis() - startedAtMillis

    /**
     * Finishes the recording.
     *
     * Returns null when nothing usable was captured — a release before the
     * encoder had written a single frame produces an empty file, and an empty
     * file is worse than no message because it arrives as a bubble that plays
     * silence.
     */
    fun stop(): Recording? {
        val mediaRecorder = recorder ?: return null
        val file = target
        recorder = null
        target = null
        val duration = System.currentTimeMillis() - startedAtMillis

        return try {
            mediaRecorder.stop()
            mediaRecorder.release()
            if (file == null || !file.exists() || file.length() == 0L) {
                file?.let(store::delete)
                null
            } else {
                Recording(file, duration)
            }
        } catch (e: Exception) {
            // Thrown when the recording is shorter than the encoder's first frame.
            Log.w(TAG, "registrazione scartata", e)
            runCatching { mediaRecorder.release() }
            file?.let(store::delete)
            null
        }
    }

    /** Abandons the recording and deletes the partial file. */
    fun cancel() {
        val mediaRecorder = recorder ?: return
        val file = target
        recorder = null
        target = null
        runCatching { mediaRecorder.stop() }
        runCatching { mediaRecorder.release() }
        file?.let(store::delete)
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }

    private companion object {
        const val TAG = "VoiceRecorder"

        /** Mono speech at 64 kbit/s: clear, and small enough to relay. */
        const val BIT_RATE = 64_000
        const val SAMPLE_RATE = 44_100
    }
}
