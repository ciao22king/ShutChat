package dev.shutchat.app.core

import java.security.SecureRandom

/** Random helpers for message ids, invite codes and group secrets. */
object Ids {
    private val random = SecureRandom()

    fun newId(): String = randomHex(16)

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    fun randomHex(size: Int): String = randomBytes(size).toHex()
}

private val HEX_DIGITS = "0123456789abcdef".toCharArray()

fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val value = this[i].toInt() and 0xFF
        out[i * 2] = HEX_DIGITS[value ushr 4]
        out[i * 2 + 1] = HEX_DIGITS[value and 0x0F]
    }
    return String(out)
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex string must have an even length" }
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        out[i] = ((this[i * 2].digitToInt(16) shl 4) or this[i * 2 + 1].digitToInt(16)).toByte()
    }
    return out
}
