package dev.shutchat.app.p2p.protocol

import dev.shutchat.app.core.Format
import dev.shutchat.app.domain.model.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A voice note is the only message whose meaning lives in a number the receiver
 * never computes.
 *
 * The duration is measured by the sender's recorder and shipped in the envelope,
 * so if it is dropped in serialization the receiving bubble has nothing to show
 * until the bytes arrive, and a note still in flight reads as a zero-length clip.
 * These tests pin the field to the wire.
 */
class VoiceNoteWireTest {

    private fun voiceNote(durationMillis: Long?) = WireMessage(
        roomId = "a".repeat(32),
        messageId = "0123456789abcdef0123456789abcdef",
        chatId = "chat-1",
        senderNodeId = "node-1",
        senderName = "Giulia",
        kind = MessageKind.AUDIO.name,
        body = "",
        createdAt = 1_700_000_000_000L,
        attachment = WireAttachment(
            id = "attach-1",
            fileName = "voice-1700000000000.m4a",
            mimeType = "audio/mp4",
            sizeBytes = 8_192,
            durationMillis = durationMillis,
        ),
    )

    @Test
    fun `a voice note keeps its kind across the wire`() {
        val decoded = EnvelopeCodec.decode(EnvelopeCodec.encode(voiceNote(4_200))) as WireMessage
        assertEquals(MessageKind.AUDIO, MessageKind.fromName(decoded.kind))
    }

    @Test
    fun `the recorded duration survives serialization`() {
        val decoded = EnvelopeCodec.decode(EnvelopeCodec.encode(voiceNote(4_200))) as WireMessage
        assertEquals(4_200L, decoded.attachment?.durationMillis)
    }

    @Test
    fun `the audio mime type survives serialization`() {
        val decoded = EnvelopeCodec.decode(EnvelopeCodec.encode(voiceNote(1_000))) as WireMessage
        assertEquals("audio/mp4", decoded.attachment?.mimeType)
    }

    @Test
    fun `a missing duration stays absent rather than becoming zero`() {
        // Zero would render as a playable clip of no length; null is what lets the
        // bubble fall back to the transfer progress instead.
        val decoded = EnvelopeCodec.decode(EnvelopeCodec.encode(voiceNote(null))) as WireMessage
        assertNull(decoded.attachment?.durationMillis)
    }

    @Test
    fun `a voice note preview never leaks the file name`() {
        // The list row shows this string; a raw file name would expose how the
        // local recording is named to peers that only need to know it is audio.
        val preview = Format.messagePreview("", MessageKind.AUDIO.name)
        assertEquals("🎤 Messaggio vocale", preview)
        assertTrue(!preview.contains(".m4a"))
    }

    @Test
    fun `durations render as minutes and seconds`() {
        assertEquals("0:05", Format.duration(5_000))
        assertEquals("1:30", Format.duration(90_000))
        assertEquals("12:00", Format.duration(720_000))
    }
}
