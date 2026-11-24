package dev.shutchat.app.p2p.call

import dev.shutchat.app.p2p.protocol.CallMedia
import dev.shutchat.app.p2p.protocol.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The call state machine is where a ringing phone, a lost answer and a stale ICE
 * candidate meet. It is a pure function, so every one of those can be pinned
 * here instead of hoping a real call reproduces them.
 */
class CallStateMachineTest {

    private fun outgoing() = CallStateMachine.reduce(
        CallSnapshot(),
        CallEvent.StartOutgoing("c1", "peer", "Luca", CallMedia.AUDIO, at = 1_000),
    )

    private fun incoming() = CallStateMachine.reduce(
        CallSnapshot(),
        CallEvent.IncomingOffer("c2", "peer", "Luca", CallMedia.VIDEO, at = 1_000),
    )

    @Test
    fun `an outgoing call starts dialing`() {
        val state = outgoing()
        assertEquals(CallPhase.DIALING, state.phase)
        assertTrue(state.outgoing)
        assertEquals("c1", state.callId)
        assertTrue(state.isBusy)
    }

    @Test
    fun `an incoming call rings and carries the media kind`() {
        val state = incoming()
        assertEquals(CallPhase.RINGING, state.phase)
        assertFalse(state.outgoing)
        assertEquals(CallMedia.VIDEO, state.media)
    }

    @Test
    fun `a second call while busy is ignored`() {
        val busy = outgoing()
        val second = CallStateMachine.reduce(
            busy,
            CallEvent.IncomingOffer("c9", "other", "Anna", CallMedia.AUDIO, at = 2_000),
        )
        assertEquals("c1", second.callId)
        assertEquals(CallPhase.DIALING, second.phase)
    }

    @Test
    fun `answer moves both sides through connecting to active`() {
        val dialing = CallStateMachine.reduce(outgoing(), CallEvent.RemoteAccepted)
        assertEquals(CallPhase.CONNECTING, dialing.phase)
        val active = CallStateMachine.reduce(dialing, CallEvent.MediaConnected)
        assertEquals(CallPhase.ACTIVE, active.phase)
    }

    @Test
    fun `accepting an incoming call does not answer before media connects`() {
        val accepted = CallStateMachine.reduce(incoming(), CallEvent.LocalAccepted)
        assertEquals(CallPhase.CONNECTING, accepted.phase)
        assertEquals(CallPhase.ACTIVE, CallStateMachine.reduce(accepted, CallEvent.MediaConnected).phase)
    }

    @Test
    fun `remote ringing only annotates a dialing call`() {
        val ringing = CallStateMachine.reduce(outgoing(), CallEvent.RemoteRinging)
        assertEquals(CallPhase.DIALING, ringing.phase)
        assertEquals("ringing", ringing.notice)
        // On an idle state it must not invent a call.
        assertEquals(CallPhase.IDLE, CallStateMachine.reduce(CallSnapshot(), CallEvent.RemoteRinging).phase)
    }

    @Test
    fun `an unanswered call times out at the ring deadline`() {
        val dialing = outgoing()
        val justBefore = CallStateMachine.reduce(
            dialing,
            CallEvent.Tick(1_000 + Protocol.CALL_RING_TIMEOUT_MILLIS - 1),
        )
        assertEquals(CallPhase.DIALING, justBefore.phase)

        val expired = CallStateMachine.reduce(
            dialing,
            CallEvent.Tick(1_000 + Protocol.CALL_RING_TIMEOUT_MILLIS),
        )
        assertEquals(CallPhase.ENDED, expired.phase)
        assertEquals(CallEndReason.TIMEOUT, expired.endReason)
    }

    @Test
    fun `a media path that never connects fails instead of hanging`() {
        val connecting = CallStateMachine.reduce(outgoing(), CallEvent.RemoteAccepted)
        val deadline = 1_000 + Protocol.CALL_RING_TIMEOUT_MILLIS + Protocol.CALL_CONNECT_TIMEOUT_MILLIS
        val failed = CallStateMachine.reduce(connecting, CallEvent.Tick(deadline))
        assertEquals(CallPhase.ENDED, failed.phase)
        assertEquals(CallEndReason.FAILED, failed.endReason)
    }

    @Test
    fun `an active call is not ended by the ring clock`() {
        val active = CallStateMachine.reduce(
            CallStateMachine.reduce(outgoing(), CallEvent.RemoteAccepted),
            CallEvent.MediaConnected,
        )
        val later = CallStateMachine.reduce(active, CallEvent.Tick(10_000_000))
        assertEquals(CallPhase.ACTIVE, later.phase)
    }

    @Test
    fun `hanging up is idempotent and late events cannot revive a call`() {
        val ended = CallStateMachine.reduce(outgoing(), CallEvent.LocalHangup)
        assertEquals(CallPhase.ENDED, ended.phase)
        assertEquals(CallEndReason.LOCAL_HANGUP, ended.endReason)

        assertEquals(ended, CallStateMachine.reduce(ended, CallEvent.LocalHangup))
        assertEquals(ended, CallStateMachine.reduce(ended, CallEvent.MediaConnected))
        assertEquals(ended, CallStateMachine.reduce(ended, CallEvent.RemoteAccepted))
    }

    @Test
    fun `a decline is reported as declined, not as a hang up`() {
        val ended = CallStateMachine.reduce(outgoing(), CallEvent.RemoteHangup(CallEndReason.DECLINED))
        assertEquals(CallEndReason.DECLINED, ended.endReason)
    }

    @Test
    fun `dismiss clears only a finished call`() {
        assertEquals(CallPhase.IDLE, CallStateMachine.reduce(
            CallStateMachine.reduce(outgoing(), CallEvent.LocalHangup),
            CallEvent.Dismiss,
        ).phase)
        // A live call must survive a stray dismiss.
        assertEquals(CallPhase.DIALING, CallStateMachine.reduce(outgoing(), CallEvent.Dismiss).phase)
    }

    @Test
    fun `mute and speaker are independent toggles`() {
        var state = CallStateMachine.reduce(
            CallStateMachine.reduce(outgoing(), CallEvent.RemoteAccepted),
            CallEvent.MediaConnected,
        )
        state = CallStateMachine.reduce(state, CallEvent.SetMuted(true))
        state = CallStateMachine.reduce(state, CallEvent.SetSpeaker(true))
        assertTrue(state.muted)
        assertTrue(state.speaker)
        state = CallStateMachine.reduce(state, CallEvent.SetMuted(false))
        assertFalse(state.muted)
        assertTrue(state.speaker)
    }

    @Test
    fun `elapsed time counts from the moment media connected`() {
        val active = CallStateMachine.reduce(
            CallStateMachine.reduce(outgoing(), CallEvent.RemoteAccepted),
            CallEvent.MediaConnected,
        )
        val connectedAt = active.connectedAt
        assertTrue(connectedAt != null)
        assertTrue(active.elapsedMillis(connectedAt!! + 5_000) >= 5_000)
        assertNull(CallSnapshot().connectedAt)
    }
}
