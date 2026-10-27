package dev.shutchat.app.domain.codec

import dev.shutchat.app.core.hexToBytes
import dev.shutchat.app.core.toHex
import dev.shutchat.app.domain.expiry.Expiry
import dev.shutchat.app.domain.model.InviteCode
import dev.shutchat.app.domain.model.InviteKind
import dev.shutchat.app.domain.model.InvitePayload
import dev.shutchat.app.domain.model.InvalidInviteCodeException
import dev.shutchat.app.crypto.RoomCrypto
import java.security.SecureRandom

/**
 * Wire format of an invite code.
 *
 * ```
 * byte  0      version (high nibble) | kind (low nibble)
 * bytes 1..2   expiry day since 2024-01-01, big endian, 0xFFFF = never
 * bytes 3..4   CRC-16/CCITT over all 15 bytes with this field zeroed
 * bytes 5..14  rendezvous secret (80 bits)
 * ```
 *
 * 15 bytes encode to exactly 24 base32 characters, which is why the secret was
 * sized at 10 bytes: the code stays a tidy six groups of four.
 *
 * The secret is not a shared key for the whole room — it is a *rendezvous*
 * token. Peers derive the room id and a session key from it, then run a
 * per-pair ECDH handshake, so holding the code is enough to find the room but
 * not enough to read traffic captured from it (see `crypto.SessionCrypto`).
 */
object InviteCodec {

    const val VERSION = 1
    const val PAYLOAD_SIZE = 15
    const val SECRET_SIZE = 10
    private const val GROUP_SIZE = 4
    private const val URL_PREFIX = "shutchat://join/"

    private val random = SecureRandom()

    fun create(kind: InviteKind, expiryDay: Int?, secret: ByteArray = newSecret()): InviteCode {
        require(secret.size == SECRET_SIZE) { "secret must be $SECRET_SIZE bytes" }
        val payload = InvitePayload(
            version = VERSION,
            kind = kind,
            expiryDay = expiryDay,
            secretHex = secret.toHex(),
        )
        return InviteCode(
            text = encodePayload(kind, expiryDay, secret),
            payload = payload,
            roomId = roomIdOf(secret),
        )
    }

    fun newSecret(): ByteArray = ByteArray(SECRET_SIZE).also(random::nextBytes)

    fun encodePayload(kind: InviteKind, expiryDay: Int?, secret: ByteArray): String {
        val bytes = ByteArray(PAYLOAD_SIZE)
        bytes[0] = ((VERSION shl 4) or kind.wireValue).toByte()
        val day = expiryDay ?: Expiry.NEVER
        bytes[1] = ((day ushr 8) and 0xFF).toByte()
        bytes[2] = (day and 0xFF).toByte()
        bytes[3] = 0
        bytes[4] = 0
        secret.copyInto(bytes, destinationOffset = 5)
        val crc = Crc16.compute(bytes)
        bytes[3] = ((crc ushr 8) and 0xFF).toByte()
        bytes[4] = (crc and 0xFF).toByte()
        return group(Base32Crockford.encode(bytes))
    }

    /** Parses user input, tolerating spaces, dashes, lower case and URLs. */
    fun parse(input: String): InviteCode {
        val raw = normalize(input)
        if (raw.isEmpty()) throw InvalidInviteCodeException("Inserisci un codice")

        val bytes = try {
            Base32Crockford.decode(raw)
        } catch (e: IllegalArgumentException) {
            throw InvalidInviteCodeException("Il codice contiene caratteri non validi")
        }
        if (bytes.size != PAYLOAD_SIZE) {
            throw InvalidInviteCodeException("Il codice deve avere 24 caratteri")
        }

        val version = (bytes[0].toInt() and 0xFF) ushr 4
        if (version != VERSION) {
            throw InvalidInviteCodeException("Codice creato da una versione più recente di ShutChat")
        }
        val kind = InviteKind.fromWireValue(bytes[0].toInt() and 0x0F)
            ?: throw InvalidInviteCodeException("Tipo di codice sconosciuto")

        // The checksum covers the payload with its own two bytes zeroed, so the
        // stored value has to be masked out before recomputing.
        val stored = ((bytes[3].toInt() and 0xFF) shl 8) or (bytes[4].toInt() and 0xFF)
        bytes[3] = 0
        bytes[4] = 0
        val expected = Crc16.compute(bytes)
        if (expected != stored) {
            throw InvalidInviteCodeException("Codice non valido: controlla di averlo copiato tutto")
        }

        val dayRaw = ((bytes[1].toInt() and 0xFF) shl 8) or (bytes[2].toInt() and 0xFF)
        val expiryDay = if (dayRaw == Expiry.NEVER) null else dayRaw
        val secret = bytes.copyOfRange(5, PAYLOAD_SIZE)

        // Re-encode from the parts rather than the working buffer: the checksum
        // bytes were zeroed above to recompute them.
        return InviteCode(
            text = encodePayload(kind, expiryDay, secret),
            payload = InvitePayload(version, kind, expiryDay, secret.toHex()),
            roomId = roomIdOf(secret),
        )
    }

    fun secretOf(code: InviteCode): ByteArray = code.payload.secretHex.hexToBytes()

    /** Stable 128-bit room identifier. The same secret always maps to the same room. */
    fun roomIdOf(secret: ByteArray): String = RoomCrypto.roomId(secret).toHex()

    /** Renders 24 characters as six dash separated groups. */
    fun group(compact: String): String = compact
        .chunked(GROUP_SIZE)
        .joinToString("-")

    private fun normalize(input: String): String {
        var text = input.trim()
        if (text.startsWith(URL_PREFIX, ignoreCase = true)) {
            text = text.substring(URL_PREFIX.length)
        }
        return text
            .uppercase()
            .filter { it.isLetterOrDigit() }
    }

    /** Value to hand to a QR code or the share sheet. */
    fun toUri(code: InviteCode): String = URL_PREFIX + code.text.replace("-", "")
}
