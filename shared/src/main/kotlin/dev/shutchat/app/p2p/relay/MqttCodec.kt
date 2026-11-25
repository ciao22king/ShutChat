package dev.shutchat.app.p2p.relay

/**
 * Minimal MQTT 3.1.1 encoder/decoder.
 *
 * Hand written rather than pulled from a library because ShutChat only ever needs
 * four packets — connect, subscribe, publish, ping — and because this half of the
 * transport is pure byte arithmetic, which means it can be tested without a
 * broker, a socket or an emulator.
 *
 * Only the subset actually used is implemented. A packet type this decoder does
 * not know about is reported as [Packet.Unknown] rather than treated as an error:
 * a broker is allowed to send things we did not ask for, and the connection
 * should survive them.
 *
 * Wire format (MQTT 3.1.1, OASIS standard):
 * ```
 * fixed header: type (4 bits) | flags (4 bits) | remaining length (varint)
 * ```
 * The remaining-length varint is the one part worth care: it is little-endian
 * base 128 with a continuation bit, so 128 encodes as `0x80 0x01`.
 */
object MqttCodec {

    const val TYPE_CONNECT = 1
    const val TYPE_CONNACK = 2
    const val TYPE_PUBLISH = 3
    const val TYPE_PUBACK = 4
    const val TYPE_SUBSCRIBE = 8
    const val TYPE_SUBACK = 9
    const val TYPE_PINGREQ = 12
    const val TYPE_PINGRESP = 13
    const val TYPE_DISCONNECT = 14

    /** Protocol name as it appears on the wire, with its two length bytes. */
    private val PROTOCOL_NAME =
        byteArrayOf(0, 4, 'M'.code.toByte(), 'Q'.code.toByte(), 'T'.code.toByte(), 'T'.code.toByte())
    private const val PROTOCOL_LEVEL = 4

    /** Anything larger than this is refused before allocating, to bound memory. */
    const val MAX_REMAINING_LENGTH = 268_435_455

    sealed interface Packet {
        data class ConnAck(val sessionPresent: Boolean, val returnCode: Int) : Packet
        data class Publish(
            val topic: String,
            val payload: ByteArray,
            /** 0 = at most once, 1 = at least once (the broker acknowledges). */
            val qos: Int = 0,
            val packetId: Int = 0,
        ) : Packet
        data class PubAck(val packetId: Int) : Packet
        data class SubAck(val packetId: Int, val granted: List<Int>) : Packet
        data object PingResp : Packet
        data class Unknown(val type: Int) : Packet
    }

    // ------------------------------------------------------------------ encoding

    fun connect(clientId: String, keepAliveSeconds: Int, cleanSession: Boolean = true): ByteArray {
        // Bit 1 of the connect flags is the clean-session bit. Leaving it clear
        // asks the broker to keep the session — and any QoS 1 messages queued
        // for this client id — until we come back.
        val connectFlags = if (cleanSession) 0x02 else 0x00
        // Variable header: protocol name, level, connect flags, keep alive.
        // The keep alive is two bytes and is not optional — without it the
        // broker reads the client id length as the keep alive and the rest of
        // the packet shifts, which ends in a silent disconnect.
        val variableHeader = PROTOCOL_NAME + byteArrayOf(
            PROTOCOL_LEVEL.toByte(),
            connectFlags.toByte(),
            (keepAliveSeconds ushr 8).toByte(),
            (keepAliveSeconds and 0xFF).toByte(),
        )
        val payload = string(clientId)
        val body = variableHeader + payload
        return fixedHeader(TYPE_CONNECT, 0) + remainingLength(body.size) + body
    }

    fun subscribe(packetId: Int, topic: String, qos: Int = 0): ByteArray {
        val body = byteArrayOf((packetId ushr 8).toByte(), (packetId and 0xFF).toByte()) +
            string(topic) +
            byteArrayOf(qos.toByte())
        // Flags for SUBSCRIBE are fixed at 0b0010 by the standard.
        return fixedHeader(TYPE_SUBSCRIBE, 0x02) + remainingLength(body.size) + body
    }

    /**
     * Encodes a PUBLISH.
     *
     * QoS 1 carries a packet id and is answered with a PUBACK, which is what
     * lets the broker confirm it took custody of the frame. That matters for
     * distance: with a persistent session the broker also keeps QoS 1 messages
     * for a subscriber that is offline, so a message sent while the other device
     * is asleep is delivered when it wakes.
     */
    fun publish(topic: String, payload: ByteArray, qos: Int = 0, packetId: Int = 0): ByteArray {
        val idBytes = if (qos > 0) {
            byteArrayOf((packetId ushr 8).toByte(), (packetId and 0xFF).toByte())
        } else {
            ByteArray(0)
        }
        val body = string(topic) + idBytes + payload
        return fixedHeader(TYPE_PUBLISH, qos shl 1) + remainingLength(body.size) + body
    }

    /** Acknowledges a QoS 1 PUBLISH we received. */
    fun puback(packetId: Int): ByteArray {
        val body = byteArrayOf((packetId ushr 8).toByte(), (packetId and 0xFF).toByte())
        return fixedHeader(TYPE_PUBACK, 0) + remainingLength(body.size) + body
    }

    fun pingRequest(): ByteArray = byteArrayOf((TYPE_PINGREQ shl 4).toByte(), 0)

    fun disconnect(): ByteArray = byteArrayOf((TYPE_DISCONNECT shl 4).toByte(), 0)

    private fun fixedHeader(type: Int, flags: Int): ByteArray =
        byteArrayOf(((type shl 4) or (flags and 0x0F)).toByte())

    private fun string(value: String): ByteArray {
        val bytes = value.toByteArray()
        require(bytes.size <= 0xFFFF) { "string too long for MQTT" }
        return byteArrayOf((bytes.size ushr 8).toByte(), (bytes.size and 0xFF).toByte()) + bytes
    }

    /** Base-128 varint, least significant group first. */
    fun remainingLength(length: Int): ByteArray {
        require(length in 0..MAX_REMAINING_LENGTH) { "length out of range" }
        var value = length
        val out = ArrayList<Byte>(4)
        do {
            var digit = value % 128
            value /= 128
            if (value > 0) digit = digit or 0x80
            out.add(digit.toByte())
        } while (value > 0)
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ decoding

    /** Result of reading one packet: either a whole packet, or a request for more bytes. */
    sealed interface ReadResult {
        data class Packet(val packet: MqttCodec.Packet, val consumed: Int) : ReadResult
        data object NeedMoreData : ReadResult
    }

    /**
     * Tries to decode one packet from [buffer].
     *
     * @return the packet and how many bytes it occupied, or [ReadResult.NeedMoreData]
     *         when the buffer holds only part of it. The caller keeps the tail.
     */
    fun read(buffer: ByteArray): ReadResult {
        if (buffer.isEmpty()) return ReadResult.NeedMoreData
        val type = (buffer[0].toInt() and 0xF0) ushr 4
        val flags = buffer[0].toInt() and 0x0F

        var multiplier = 1
        var length = 0
        var index = 1
        while (true) {
            if (index >= buffer.size) return ReadResult.NeedMoreData
            val digit = buffer[index].toInt() and 0xFF
            length += (digit and 0x7F) * multiplier
            multiplier *= 128
            index++
            if (digit and 0x80 == 0) break
            if (multiplier > 128 * 128 * 128) return ReadResult.NeedMoreData
        }
        if (length > MAX_REMAINING_LENGTH) return ReadResult.NeedMoreData
        if (buffer.size < index + length) return ReadResult.NeedMoreData

        val body = buffer.copyOfRange(index, index + length)
        val consumed = index + length
        return ReadResult.Packet(decode(type, flags, body), consumed)
    }

    private fun decode(type: Int, flags: Int, body: ByteArray): Packet = when (type) {
        TYPE_CONNACK -> {
            if (body.size < 2) {
                Packet.Unknown(type)
            } else {
                Packet.ConnAck(sessionPresent = body[0].toInt() and 1 == 1, returnCode = body[1].toInt())
            }
        }

        TYPE_PUBLISH -> decodePublish(flags, body)
        TYPE_PUBACK -> {
            if (body.size < 2) {
                Packet.Unknown(type)
            } else {
                Packet.PubAck(((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF))
            }
        }

        TYPE_SUBACK -> decodeSubAck(body)
        TYPE_PINGRESP -> Packet.PingResp
        else -> Packet.Unknown(type)
    }

    private fun decodePublish(flags: Int, body: ByteArray): Packet {
        val qos = (flags and 0x06) ushr 1
        if (body.size < 2) return Packet.Unknown(TYPE_PUBLISH)
        val topicLength = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
        // topic length prefix (2) + topic + packet id (2) only when QoS > 0.
        val headerSize = 2 + topicLength + if (qos > 0) 2 else 0
        if (body.size < headerSize) return Packet.Unknown(TYPE_PUBLISH)
        val topic = String(body, 2, topicLength)
        val packetId = if (qos > 0) {
            ((body[2 + topicLength].toInt() and 0xFF) shl 8) or
                (body[3 + topicLength].toInt() and 0xFF)
        } else {
            0
        }
        val payload = body.copyOfRange(headerSize, body.size)
        return Packet.Publish(topic, payload, qos, packetId)
    }

    private fun decodeSubAck(body: ByteArray): Packet {
        if (body.size < 2) return Packet.Unknown(TYPE_SUBACK)
        val packetId = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
        val granted = body.drop(2).map { it.toInt() and 0xFF }
        return Packet.SubAck(packetId, granted)
    }
}
