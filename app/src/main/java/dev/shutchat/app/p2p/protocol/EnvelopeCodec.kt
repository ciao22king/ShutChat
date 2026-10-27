package dev.shutchat.app.p2p.protocol

import kotlinx.serialization.json.Json

/**
 * Serialization for control envelopes.
 *
 * `ignoreUnknownKeys` is what makes version skew survivable: a newer peer can add
 * fields to an envelope and an older build still processes the message instead
 * of dropping the conversation.
 */
object EnvelopeCodec {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "t"
        isLenient = false
    }

    fun encode(envelope: Envelope): ByteArray =
        json.encodeToString(Envelope.serializer(), envelope).toByteArray()

    fun decode(bytes: ByteArray): Envelope = try {
        json.decodeFromString(Envelope.serializer(), bytes.decodeToString())
    } catch (e: Exception) {
        throw ProtocolException("envelope non decodificabile", e)
    }
}

/**
 * Binary framing shared by every transport.
 *
 * ```
 * byte 0        frame type: 1 = control, 2 = chunk
 * bytes 1..4    payload length, big endian
 * payload       roomId (16 raw bytes) || body
 * ```
 *
 * The room id sits *outside* the ciphertext on purpose: a device relaying for a
 * room it holds routes by those 16 bytes while the body stays sealed with a key
 * derived from the invite code.
 *
 * Chunk bodies are laid out as
 * `transferId (16) | index (4) | total (4) | sealed bytes`.
 */
object Framing {

    const val TYPE_CONTROL = 1
    const val TYPE_CHUNK = 2

    const val LENGTH_HEADER_SIZE = 5
    const val ROOM_ID_SIZE = 16
    const val TRANSFER_HEADER_SIZE = 24
    const val OVERHEAD = LENGTH_HEADER_SIZE + ROOM_ID_SIZE + TRANSFER_HEADER_SIZE

    fun frameControl(roomId: ByteArray, body: ByteArray): ByteArray {
        require(roomId.size == ROOM_ID_SIZE) { "roomId deve essere di 16 byte" }
        return frame(TYPE_CONTROL, roomId + body)
    }

    fun frameChunk(
        roomId: ByteArray,
        transferId: ByteArray,
        index: Int,
        total: Int,
        body: ByteArray,
    ): ByteArray {
        require(roomId.size == ROOM_ID_SIZE) { "roomId deve essere di 16 byte" }
        require(transferId.size == ROOM_ID_SIZE) { "transferId deve essere di 16 byte" }
        val payload = ByteArray(ROOM_ID_SIZE + TRANSFER_HEADER_SIZE + body.size)
        roomId.copyInto(payload, 0)
        transferId.copyInto(payload, ROOM_ID_SIZE)
        writeInt(payload, ROOM_ID_SIZE + 16, index)
        writeInt(payload, ROOM_ID_SIZE + 20, total)
        body.copyInto(payload, ROOM_ID_SIZE + TRANSFER_HEADER_SIZE)
        return frame(TYPE_CHUNK, payload)
    }

    private fun frame(type: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(LENGTH_HEADER_SIZE + payload.size)
        out[0] = type.toByte()
        writeInt(out, 1, payload.size)
        payload.copyInto(out, LENGTH_HEADER_SIZE)
        return out
    }

    fun readInt(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xFF) shl 24) or
            ((source[offset + 1].toInt() and 0xFF) shl 16) or
            ((source[offset + 2].toInt() and 0xFF) shl 8) or
            (source[offset + 3].toInt() and 0xFF)

    fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value ushr 24) and 0xFF).toByte()
        target[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 3] = (value and 0xFF).toByte()
    }

    sealed interface Frame {
        val roomId: ByteArray

        data class Control(override val roomId: ByteArray, val body: ByteArray) : Frame

        data class Chunk(
            override val roomId: ByteArray,
            val transferId: String,
            val index: Int,
            val total: Int,
            val body: ByteArray,
        ) : Frame
    }

    fun parse(raw: ByteArray): Frame {
        require(raw.size >= LENGTH_HEADER_SIZE) { "frame troppo corto" }
        val length = readInt(raw, 1)
        require(length in ROOM_ID_SIZE..Protocol.MAX_FRAME_BYTES) {
            "lunghezza frame incoerente: $length"
        }
        require(raw.size >= LENGTH_HEADER_SIZE + length) { "frame incompleto" }
        val payload = raw.copyOfRange(LENGTH_HEADER_SIZE, LENGTH_HEADER_SIZE + length)
        val roomId = payload.copyOfRange(0, ROOM_ID_SIZE)
        return when (raw[0].toInt()) {
            TYPE_CHUNK -> {
                require(payload.size >= ROOM_ID_SIZE + TRANSFER_HEADER_SIZE) { "chunk troppo corto" }
                Frame.Chunk(
                    roomId = roomId,
                    transferId = payload.copyOfRange(ROOM_ID_SIZE, ROOM_ID_SIZE + 16).joinToString("") {
                        "%02x".format(it)
                    },
                    index = readInt(payload, ROOM_ID_SIZE + 16),
                    total = readInt(payload, ROOM_ID_SIZE + 20),
                    body = payload.copyOfRange(ROOM_ID_SIZE + TRANSFER_HEADER_SIZE, payload.size),
                )
            }

            else -> Frame.Control(roomId, payload.copyOfRange(ROOM_ID_SIZE, payload.size))
        }
    }
}
