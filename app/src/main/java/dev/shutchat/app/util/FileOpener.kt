package dev.shutchat.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dev.shutchat.app.core.Log
import java.io.File

/**
 * Opening and exporting attachments.
 *
 * Incoming files live in the app's private storage, so viewing one means handing
 * a `content://` Uri to another app through the FileProvider declared in the
 * manifest. Nothing is copied into shared storage unless the user asks for it.
 */
object FileOpener {

    private const val TAG = "FileOpener"

    /** Opens [path] with whatever app handles [mimeType]. */
    fun open(context: Context, path: String, mimeType: String): Boolean {
        val file = File(path)
        if (!file.exists()) return false
        return try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Apri con"))
            true
        } catch (e: Exception) {
            Log.w(TAG, "nessuna app per $mimeType", e)
            false
        }
    }

    /** Shares an already-received file out to another app. */
    fun share(context: Context, path: String, mimeType: String, fileName: String): Boolean {
        val file = File(path)
        if (!file.exists()) return false
        return try {
            val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, fileName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, fileName))
            true
        } catch (e: Exception) {
            Log.w(TAG, "condivisione fallita", e)
            false
        }
    }
}
