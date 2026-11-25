package dev.shutchat.desktop

import java.io.File

/**
 * Where attachments live on disk.
 *
 * Mirrors the Android MediaStore layout: one directory for what arrives, one for
 * what is sent, one for voice notes. Files keep their original name after the
 * attachment id so a user opening the folder can tell what is what.
 */
class DesktopFiles(root: File = File(DesktopSettings.dataDir().toFile(), "files")) {

    private val incomingDir = File(root, "incoming").apply { mkdirs() }
    private val outgoingDir = File(root, "outgoing").apply { mkdirs() }
    private val voiceDir = File(root, "voice").apply { mkdirs() }

    fun incoming(attachmentId: String, fileName: String): File =
        File(incomingDir, "$attachmentId-${sanitize(fileName)}")

    fun outgoing(fileName: String): File =
        File(outgoingDir, "${System.currentTimeMillis()}-${sanitize(fileName)}")

    fun voice(): File = File(voiceDir, "voice-${System.currentTimeMillis()}.wav")

    companion object {
        fun sanitize(name: String): String =
            name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96).ifBlank { "file" }
    }
}
