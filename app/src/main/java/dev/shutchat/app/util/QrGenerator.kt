package dev.shutchat.app.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * QR rendering for invite codes.
 *
 * Error correction is set to M rather than the usual L: a code shown on a screen
 * gets photographed at an angle in bad light, and the extra redundancy is what
 * makes that work. The margin is kept at 1 module so the code stays compact on a
 * phone display.
 */
object QrGenerator {

    fun render(content: String, sizePx: Int = 640): Bitmap? = try {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(sizePx * sizePx)
        val dark = Color.rgb(18, 23, 21)
        val light = Color.WHITE
        for (y in 0 until sizePx) {
            val row = y * sizePx
            for (x in 0 until sizePx) {
                pixels[row + x] = if (matrix[x, y]) dark else light
            }
        }
        Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
    } catch (e: Exception) {
        null
    }

    /** True when [payload] looks like something ShutChat should try to parse. */
    fun looksLikeInvite(payload: String): Boolean {
        val trimmed = payload.trim()
        return trimmed.startsWith("shutchat://join/", ignoreCase = true) ||
            trimmed.replace("-", "").length == 24
    }
}
