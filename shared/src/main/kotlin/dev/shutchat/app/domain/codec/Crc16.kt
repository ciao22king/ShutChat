package dev.shutchat.app.domain.codec

/** CRC-16/CCITT-FALSE. Used to reject mistyped invite codes before any I/O. */
object Crc16 {

    fun compute(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Int {
        var crc = 0xFFFF
        for (i in offset until offset + length) {
            crc = crc xor ((bytes[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) {
                    (crc shl 1) xor 0x1021
                } else {
                    crc shl 1
                }
                crc = crc and 0xFFFF
            }
        }
        return crc and 0xFFFF
    }
}
