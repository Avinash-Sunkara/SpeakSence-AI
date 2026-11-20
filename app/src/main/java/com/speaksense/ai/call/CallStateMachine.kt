package com.speaksense.ai.call

import com.speaksense.ai.model.CallDirection
import com.speaksense.ai.model.CallInfo
import com.speaksense.ai.model.CallPhase

/** Remembers that the phone rang, so a cold-started process can still tell incoming from outgoing. */
interface RingingStore {
    fun markRinging()
    fun wasRingingRecently(): Boolean
    fun clear()
}

/**
 * Pure state machine (no Android classes) turning raw TelephonyManager call states into
 * "call started (incoming/outgoing)" and "call ended" events. Unit-tested on the JVM.
 *
 * Raw states: 0 = IDLE, 1 = RINGING, 2 = OFFHOOK (dialling, active or on hold).
 *
 * Limitation: public APIs cannot tell "dialling" from "connected" for outgoing calls, so an outgoing
 * call counts as active as soon as it is dialled.
 */
class CallStateMachine(private val store: RingingStore) {

    data class Step(
        val info: CallInfo,
        val started: CallDirection?,
        val ended: Boolean,
        val changed: Boolean,
    )

    private var lastRaw = IDLE
    private var inCall = false
    private var info = CallInfo()

    val isInCall: Boolean get() = inCall
    val currentInfo: CallInfo get() = info

    fun onRawState(raw: Int): Step {
        if (raw == lastRaw) return Step(info, null, false, false)
        val previous = lastRaw
        lastRaw = raw
        var started: CallDirection? = null
        var ended = false
        when (raw) {
            RINGING -> {
                // A second ring (call waiting) during a call must not reset the active call.
                if (!inCall) {
                    store.markRinging()
                    info = CallInfo(CallPhase.RINGING, CallDirection.INCOMING)
                }
            }
            OFFHOOK -> {
                if (!inCall) {
                    inCall = true
                    val direction =
                        if (previous == RINGING || store.wasRingingRecently()) CallDirection.INCOMING
                        else CallDirection.OUTGOING
                    info = CallInfo(CallPhase.ACTIVE, direction)
                    started = direction
                }
            }
            else -> {
                store.clear()
                if (inCall) {
                    inCall = false
                    ended = true
                }
                info = CallInfo(CallPhase.IDLE, CallDirection.UNKNOWN)
            }
        }
        return Step(info, started, ended, true)
    }

    companion object {
        const val IDLE = 0
        const val RINGING = 1
        const val OFFHOOK = 2
    }
}
