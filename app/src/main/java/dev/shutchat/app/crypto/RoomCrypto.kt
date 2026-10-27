package dev.shutchat.app.crypto

import dev.shutchat.app.core.toHex
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Room-level cryptography.
 *
 * ShutChat has no server and no accounts, so the invite code is the only thing
 * two devices share. Everything else is derived from it:
 *
 * ```
 * invite secret (80 bit)
 *   ├─ roomId     = SHA-256("shutchat/room"    || secret)[0..16]   routing, public
 *   ├─ messageKey = SHA-256("shutchat/msg"     || secret)          AES-256-GCM
 *   ├─ transferKey= SHA-256("shutchat/file"    || secret)          AES-256-GCM
 *   └─ beaconTag  = SHA-256("shutchat/beacon"  || secret)[0..8]    discovery proof
 * ```
 *
 * A single room key (rather than a per-pair key) is what makes groups work with
 * no server: any member can seal a message that every other member can open, and
 * a member who joins later can still be sent history. The trade-off is explicit
 * and surfaced in the UI — a leaked invite code exposes that room, which is why
 * codes expire by default and why each chat shows its safety number.
 *
 * Keys are derived on demand and never written to disk. Losing the database
 * therefore does not expose old ciphertext, because only plaintext rows are
 * stored, and those live in the app's private storage.
 */
object RoomCrypto {

    private const val GCM_TAG_BITS = 128
    const val NONCE_SIZE = 12
    const val KEY_SIZE = 32

    private val random = SecureRandom()

    fun roomId(secret: ByteArray): ByteArray = derive(secret, "shutchat/room", 16)

    fun messageKey(secret: ByteArray): ByteArray = derive(secret, "shutchat/msg", KEY_SIZE)

    fun transferKey(secret: ByteArray): ByteArray = derive(secret, "shutchat/file", KEY_SIZE)

    fun beaconTag(secret: ByteArray): ByteArray = derive(secret, "shutchat/beacon", 8)

    /**
     * Short authentication string for a room. Two devices holding the same code
     * show the same digits, so users can compare them out loud to confirm nobody
     * swapped a code in transit.
     */
    fun safetyNumber(secret: ByteArray): String {
        val digest = derive(secret, "shutchat/sas", 5)
        val value = digest.fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xFF) }
        return (value % 100_000_000_000L).toString().padStart(11, '0')
            .chunked(4)
            .joinToString(" ")
    }

    /** AES-256-GCM: confidentiality plus integrity in one pass. */
    fun encrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray? = null): ByteArray {
        val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
        )
        aad?.let(cipher::updateAAD)
        return nonce + cipher.doFinal(plaintext)
    }

    /** @throws GeneralSecurityException when the payload was tampered with. */
    fun decrypt(key: ByteArray, payload: ByteArray, aad: ByteArray? = null): ByteArray {
        require(payload.size > NONCE_SIZE) { "payload troppo corto" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, payload.copyOfRange(0, NONCE_SIZE)),
        )
        aad?.let(cipher::updateAAD)
        return cipher.doFinal(payload, NONCE_SIZE, payload.size - NONCE_SIZE)
    }

    fun fingerprint(secret: ByteArray): String = derive(secret, "shutchat/fp", 8).toHex()

    /**
     * HKDF-SHA256. Used instead of a bare hash so each purpose gets an
     * independent key with domain separation, and so a future version can raise
     * the output size without breaking existing rooms.
     */
    private fun derive(input: ByteArray, info: String, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val pseudoRandomKey = mac.doFinal(input)
        mac.init(SecretKeySpec(pseudoRandomKey, "HmacSHA256"))
        val output = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.update(previous)
            mac.update(info.toByteArray())
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val chunk = minOf(previous.size, length - offset)
            previous.copyInto(output, offset, 0, chunk)
            offset += chunk
            counter++
        }
        return output
    }

    /** Exposed for tests that need a deterministic digest. */
    fun sha256(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(input)
}
