package dev.shutchat.app.media

import android.media.MediaPlayer
import dev.shutchat.app.core.Log
import java.io.File

/**
 * Plays one voice message at a time.
 *
 * One player for the whole screen rather than one per bubble: a conversation can
 * hold hundreds of voice notes and a `MediaPlayer` per bubble would hold a codec
 * open for every one of them. Starting a new note therefore stops the previous
 * one, which is also what a user expects when they tap a second bubble.
 *
 * The screen polls [position] for the playhead; this class only reports the length
 * once playback starts, through [play]'s `onProgress`, and announces the end
 * through `onFinished`. `MediaPlayer` has no position callback, so somebody has to
 * poll, and keeping that in the screen means one ticker per screen rather than one
 * per bubble.
 */
class VoicePlayer {

    private var player: MediaPlayer? = null
    private var playingId: String? = null

    /** Id of the message currently sounding, or null when silent. */
    val currentId: String? get() = playingId

    val isPlaying: Boolean get() = player != null

    /**
     * Plays [file] for message [messageId].
     *
     * @return false when the file is missing or the platform refuses it, so the
     *         screen can say so instead of showing a bar that never moves.
     */
    fun play(
        messageId: String,
        file: File,
        onProgress: (positionMillis: Long, durationMillis: Long) -> Unit,
        onFinished: () -> Unit,
    ): Boolean {
        stop()
        if (!file.exists() || file.length() == 0L) {
            Log.w(TAG, "file vocale assente o vuoto: $messageId")
            return false
        }

        return try {
            val mediaPlayer = MediaPlayer()

            // prepareAsync, not prepare: this runs on the main thread from a click,
            // and a synchronous prepare blocks the UI while the platform reads the
            // file. The start therefore happens in onPrepared rather than here.
            mediaPlayer.setOnPreparedListener { mp ->
                // The user may have tapped a second note while this one prepared, in
                // which case stop() already detached it and starting it would put two
                // voices over each other.
                if (player !== mp) return@setOnPreparedListener
                mp.start()
                onProgress(0L, mp.duration.toLong().coerceAtLeast(0L))
            }

            // Attached before prepare so a very short note cannot finish in the gap
            // between starting and wiring the listener, which would leave the
            // playhead stuck and the bubble unplayable a second time.
            mediaPlayer.setOnCompletionListener { mp ->
                if (player === mp) {
                    stop()
                    onFinished()
                }
            }

            mediaPlayer.setDataSource(file.absolutePath)
            player = mediaPlayer
            playingId = messageId
            mediaPlayer.prepareAsync()
            true
        } catch (e: Exception) {
            Log.w(TAG, "riproduzione non riuscita: $messageId", e)
            stop()
            false
        }
    }

    /** Position and total length of the current playback, if any. */
    fun position(): Pair<Long, Long> {
        val mediaPlayer = player ?: return 0L to 0L
        return runCatching {
            mediaPlayer.currentPosition.toLong() to mediaPlayer.duration.toLong()
        }.getOrDefault(0L to 0L)
    }

    /** Stops playback and releases the codec. Safe to call when idle. */
    fun stop() {
        val mediaPlayer = player
        player = null
        playingId = null
        if (mediaPlayer == null) return
        runCatching { if (mediaPlayer.isPlaying) mediaPlayer.stop() }
        runCatching { mediaPlayer.reset() }
        runCatching { mediaPlayer.release() }
    }

    private companion object {
        const val TAG = "VoicePlayer"
    }
}
