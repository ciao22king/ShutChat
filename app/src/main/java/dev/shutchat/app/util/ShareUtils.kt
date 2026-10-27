package dev.shutchat.app.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import dev.shutchat.app.domain.codec.InviteCodec
import dev.shutchat.app.domain.model.InviteCode

/**
 * Sharing and clipboard helpers.
 *
 * The shared text carries the `shutchat://join/` URI so a recipient on a device
 * with the app installed can tap it, while the plain code sits underneath for
 * anyone who has to type it into a field.
 */
object ShareUtils {

    fun shareCode(context: Context, code: String, title: String? = null) {
        val invite = runCatching { InviteCodec.parse(code) }.getOrNull()
        val uri = invite?.let(InviteCodec::toUri)
        val body = buildString {
            appendLine(title?.let { "Ti invito su ShutChat: $it" } ?: "Ti invito su ShutChat")
            appendLine()
            appendLine(code)
            if (uri != null) {
                appendLine()
                append(uri)
            }
            appendLine()
            append(
                "ShutChat non usa un server: i messaggi viaggiano solo tra i dispositivi. " +
                    "Per parlare dobbiamo essere sulla stessa rete o vicini con il Bluetooth.",
            )
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, body)
            putExtra(Intent.EXTRA_SUBJECT, title ?: "ShutChat")
        }
        context.startActivity(Intent.createChooser(intent, "Condividi il codice"))
    }

    /** Copies a code with a label, so paste destinations show something useful. */
    fun copyCode(context: Context, code: String, label: String = "Codice ShutChat") {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(label, code))
    }

    fun shareText(context: Context, text: String, subject: String? = null) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }
        context.startActivity(Intent.createChooser(intent, subject ?: "Condividi"))
    }

    /** Renders an invite as the deep link a QR code should contain. */
    fun qrPayload(code: InviteCode): String = InviteCodec.toUri(code)
}
