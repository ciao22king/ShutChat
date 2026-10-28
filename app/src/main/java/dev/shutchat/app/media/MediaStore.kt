package dev.shutchat.app.media

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import dev.shutchat.app.core.Log
import java.io.File
import java.io.InputStream

/**
 * Local storage for attachments.
 *
 * Incoming files land in the app's private directory: nothing is written to
 * shared storage without the user explicitly saving it, so a chat cannot leave
 * traces in the gallery or the downloads folder by itself.
 */
class MediaStore(private val context: Context) {

    /** Metadata read off a content Uri, used to name and size the transfer. */
    data class PickedFile(
        val uri: Uri,
        val fileName: String,
        val mimeType: String,
        val sizeBytes: Long,
    )

    fun incomingDir(): File =
        File(context.filesDir, "attachments/incoming").apply { mkdirs() }

    fun outgoingDir(): File =
        File(context.cacheDir, "attachments/outgoing").apply { mkdirs() }

    fun thumbsDir(): File =
        File(context.filesDir, "attachments/thumbs").apply { mkdirs() }

    /**
     * Where recordings are written.
     *
     * Under `filesDir`, not the cache, because unlike a picked photo a recording
     * exists nowhere else: if the cache were trimmed the voice note would be
     * gone for good, and the bubble would play silence.
     */
    fun voiceDir(): File =
        File(context.filesDir, "attachments/voice").apply { mkdirs() }

    /**
     * A fresh path for a recording.
     *
     * Named from the clock rather than from an id because `MediaRecorder` needs
     * the path before the message exists, so the name is chosen up front and kept.
     */
    fun newVoiceFile(): File =
        File(voiceDir(), "voice-${System.currentTimeMillis()}.m4a")

    /** Destination for a file identified by attachment id and original name. */
    fun destinationFor(attachmentId: String, fileName: String): File =
        File(incomingDir(), "$attachmentId-${sanitize(fileName)}")

    /** Copies a picked file into cache so it survives the picker's permission. */
    fun stageForSending(attachmentId: String, picked: PickedFile): File? = try {
        val target = File(outgoingDir(), "$attachmentId-${sanitize(picked.fileName)}")
        context.contentResolver.openInputStream(picked.uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
        }
        target.takeIf { it.exists() }
    } catch (e: Exception) {
        Log.w(TAG, "impossibile preparare ${picked.fileName}", e)
        null
    }

    fun describe(uri: Uri): PickedFile? {
        val resolver = context.contentResolver
        val name = queryName(resolver, uri) ?: uri.lastPathSegment ?: "file"
        val size = querySize(resolver, uri)
        val mime = resolver.getType(uri) ?: guessMime(name)
        return PickedFile(uri, name, mime, size)
    }

    fun open(uri: Uri): InputStream? = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()

    fun delete(file: File) {
        runCatching { file.delete() }
    }

    /** Removes staged outgoing copies older than a day. */
    fun cleanOutgoingCache() {
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        outgoingDir().listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    private fun queryName(resolver: ContentResolver, uri: Uri): String? =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()

    private fun querySize(resolver: ContentResolver, uri: Uri): Long =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
            } ?: 0L
        }.getOrDefault(0L)

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96).ifBlank { "file" }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        "m4a", "aac" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "ogg", "opus" -> "audio/ogg"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }

    /** Human readable size, used in the bubble footer. */
    fun readableSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f kB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }

    /** True when the platform can hand this Uri to another app for viewing. */
    fun canShare(mimeType: String): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && mimeType.isNotEmpty()

    private companion object {
        const val TAG = "MediaStore"
    }
}
