package dev.shutchat.desktop

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Renders an invite code as a QR matrix the UI can draw as squares. */
object DesktopQr {

    /**
     * @return one boolean row per module, true for a dark module, or null when
     *         the code cannot be encoded (it is too long for a QR version).
     */
    fun matrix(text: String, size: Int = 512): Array<BooleanArray>? = runCatching {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val bits = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        Array(bits.height) { y -> BooleanArray(bits.width) { x -> bits.get(x, y) } }
    }.getOrNull()
}
