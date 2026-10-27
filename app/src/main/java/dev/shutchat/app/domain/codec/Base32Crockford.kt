package dev.shutchat.app.domain.codec

/**
 * Base32 using Crockford's alphabet: no I, L, O or U, so a code read off a
 * screen or a screenshot cannot be confused by 1/l/I or 0/O. Decoding is
 * deliberately forgiving and folds the ambiguous glyphs back to their digits.
 *
 * 15 bytes encode to exactly 24 characters, which is why the invite payload was
 * sized at 15 bytes.
 */
object Base32Crockford {

    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val DECODE_TABLE = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, char -> table[char.code] = index }
        // Forgiving aliases for the characters Crockford's alphabet omits.
        table['I'.code] = 1
        table['L'.code] = 1
        table['O'.code] = 0
        table['U'.code] = 0
        table['o'.code] = 0
        table['i'.code] = 1
        table['l'.code] = 1
        table['u'.code] = 0
    }

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.append(ALPHABET[(buffer ushr bits) and 0x1F])
            }
        }
        if (bits > 0) {
            out.append(ALPHABET[(buffer shl (5 - bits)) and 0x1F])
        }
        return out.toString()
    }

    /** @throws IllegalArgumentException when the text is not valid base32. */
    fun decode(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (char in text) {
            val value = if (char.code < 128) DECODE_TABLE[char.code] else -1
            require(value >= 0) { "carattere non valido: '$char'" }
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write((buffer ushr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
