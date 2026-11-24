package dev.shutchat.app.p2p.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deduplication and self-echo filtering are what keep a relayed message from
 * arriving twice. They live next to the envelope definitions so a new type cannot
 * skip them, and these tests pin the two rules that matter.
 */
class EnvelopeKeysTest {

    private fun wire(id: String = "m1", sender: String = "node-a", body: String = "ciao") = WireMessage(
        roomId = "room",
        messageId = id,
        chatId = "chat",
        senderNodeId = sender,
        senderName = "Luca",
        kind = "TEXT",
        body = body,
        createdAt = 1_000,
    )

    private fun offer(callId: String = "c1") = CallOffer(
        roomId = "room",
        callId = callId,
        fromNodeId = "node-a",
        fromName = "Luca",
        media = "audio",
        sdp = "v=0",
        createdAt = 1_000,
        expiresAt = 46_000,
    )

    @Test
    fun `a message is keyed by its own id`() {
        assertEquals("m:m1", wire().dedupeKey())
        assertEquals(wire().dedupeKey(), wire(body = "testo diverso").dedupeKey())
        assertNotEquals(wire("m1").dedupeKey(), wire("m2").dedupeKey())
    }

    @Test
    fun `a repeated call offer is one call`() {
        // The caller re-sends the offer while ringing; both copies must land on
        // the same call id so the callee does not get two ringing screens.
        assertEquals(offer().dedupeKey(), offer().dedupeKey())
        assertNotEquals(offer("c1").dedupeKey(), offer("c2").dedupeKey())
    }

    @Test
    fun `ice candidates are distinct per candidate`() {
        fun ice(candidate: String) = CallIce(
            roomId = "room",
            callId = "c1",
            fromNodeId = "node-a",
            toNodeId = "node-b",
            candidate = candidate,
            fromCaller = true,
        )
        assertNotEquals(ice("a").dedupeKey(), ice("b").dedupeKey())
    }

    @Test
    fun `the sender is read from envelopes that name one`() {
        assertEquals("node-a", wire().senderNodeId())
        assertEquals("node-a", offer().senderNodeId())
        assertEquals("node-a", Ping(roomId = "room", nodeId = "node-a", sentAt = 1).senderNodeId())
    }

    @Test
    fun `room wide envelopes have no single sender`() {
        // A roster describes the room, not a person: dropping it as "our own
        // echo" would make a group forget its members.
        val roster = MemberRoster("room", "chat", emptyList())
        assertEquals("", roster.senderNodeId())
    }

    @Test
    fun `call traffic is distinguishable from conversation traffic`() {
        assertTrue(offer().isCallSignal())
        assertTrue(CallRinging("room", "c1", "node-a", "node-b").isCallSignal())
        assertTrue(CallHangup("room", "c1", "node-a", "node-b").isCallSignal())
        assertFalse(wire().isCallSignal())
        assertFalse(Receipt("room", listOf("m1"), "delivered", "node-a").isCallSignal())
    }
}
