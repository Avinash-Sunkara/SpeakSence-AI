package com.speaksense.ai

import com.speaksense.ai.call.CallStateMachine
import com.speaksense.ai.call.RingingStore
import com.speaksense.ai.model.CallDirection
import com.speaksense.ai.model.CallPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallStateMachineTest {
    private class FakeStore(var ringing: Boolean = false) : RingingStore {
        override fun markRinging() { ringing = true }
        override fun wasRingingRecently() = ringing
        override fun clear() { ringing = false }
    }

    @Test
    fun incomingCallStartsAndEnds() {
        val m = CallStateMachine(FakeStore())
        assertNull(m.onRawState(CallStateMachine.RINGING).started)
        val answered = m.onRawState(CallStateMachine.OFFHOOK)
        assertEquals(CallDirection.INCOMING, answered.started)
        assertEquals(CallPhase.ACTIVE, answered.info.phase)
        val ended = m.onRawState(CallStateMachine.IDLE)
        assertTrue(ended.ended)
        assertEquals(CallPhase.IDLE, ended.info.phase)
    }

    @Test
    fun outgoingCallIsDetectedWithoutRinging() {
        val m = CallStateMachine(FakeStore())
        val dialled = m.onRawState(CallStateMachine.OFFHOOK)
        assertEquals(CallDirection.OUTGOING, dialled.started)
        assertTrue(m.onRawState(CallStateMachine.IDLE).ended)
    }

    @Test
    fun duplicateEventsAreIgnored() {
        val m = CallStateMachine(FakeStore())
        m.onRawState(CallStateMachine.OFFHOOK)
        val again = m.onRawState(CallStateMachine.OFFHOOK)
        assertFalse(again.changed)
        assertNull(again.started)
    }

    @Test
    fun missedCallDoesNotReportEnded() {
        val m = CallStateMachine(FakeStore())
        m.onRawState(CallStateMachine.RINGING)
        val idle = m.onRawState(CallStateMachine.IDLE)
        assertFalse(idle.ended)
    }

    @Test
    fun callWaitingDoesNotRestartSession() {
        val m = CallStateMachine(FakeStore())
        assertEquals(CallDirection.OUTGOING, m.onRawState(CallStateMachine.OFFHOOK).started)
        m.onRawState(CallStateMachine.RINGING) // second caller
        val back = m.onRawState(CallStateMachine.OFFHOOK)
        assertNull(back.started)
        assertTrue(m.isInCall)
        assertTrue(m.onRawState(CallStateMachine.IDLE).ended)
    }

    @Test
    fun coldStartedProcessUsesPersistedRingingHint() {
        val m = CallStateMachine(FakeStore(ringing = true))
        assertEquals(CallDirection.INCOMING, m.onRawState(CallStateMachine.OFFHOOK).started)
    }
}
