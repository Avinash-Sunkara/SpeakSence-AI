package com.speaksense.ai.call

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import com.speaksense.ai.model.CallDirection
import com.speaksense.ai.model.CallInfo
import com.speaksense.ai.permissions.PermissionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Detects incoming / outgoing / active / ended calls using only READ_PHONE_STATE:
 *  - a manifest receiver wakes the process (works after reboot and process death, no always-on service),
 *  - TelephonyCallback (API 31+) gives live updates while the process is alive,
 *  - both feed one state machine, which de-duplicates events.
 */
class CallStateMonitor(
    private val context: Context,
    private val permissions: PermissionManager,
) {
    interface Listener {
        fun onCallActive(direction: CallDirection)
        fun onCallEnded()
    }

    @Volatile
    var listener: Listener? = null

    private val prefs: SharedPreferences = context.getSharedPreferences("speaksense_call", Context.MODE_PRIVATE)
    private val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    private val machine = CallStateMachine(object : RingingStore {
        override fun markRinging() {
            prefs.edit().putLong(KEY_RING_AT, SystemClock.elapsedRealtime()).apply()
        }

        override fun wasRingingRecently(): Boolean {
            val saved = prefs.getLong(KEY_RING_AT, -1L)
            val now = SystemClock.elapsedRealtime()
            // elapsedRealtime restarts at reboot, so a saved value in the future means "stale".
            return saved in 0..now && now - saved < RING_VALID_MS
        }

        override fun clear() {
            prefs.edit().remove(KEY_RING_AT).apply()
        }
    })

    private val _callInfo = MutableStateFlow(CallInfo())
    val callInfo: StateFlow<CallInfo> = _callInfo.asStateFlow()

    private var registered = false

    private val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            onRawState(state)
        }
    }

    /** Safe to call repeatedly. Needs READ_PHONE_STATE; does nothing until it is granted. */
    fun ensureStarted() {
        if (registered || !permissions.hasPhoneState()) return
        try {
            telephony.registerTelephonyCallback(context.mainExecutor, callback)
            registered = true
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_PHONE_STATE missing", e)
        } catch (e: Exception) {
            Log.w(TAG, "registerTelephonyCallback failed", e)
        }
    }

    /** Called by the manifest receiver. The broadcast extra is only a hint; the real state is re-read. */
    fun onPhoneStateBroadcast(extraState: String?) {
        if (extraState == TelephonyManager.EXTRA_STATE_RINGING) {
            prefs.edit().putLong(KEY_RING_AT, SystemClock.elapsedRealtime()).apply()
        }
        ensureStarted()
        syncFromSystem()
    }

    @Suppress("DEPRECATION")
    fun syncFromSystem() {
        if (!permissions.hasPhoneState()) return
        try {
            onRawState(telephony.callState)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot read call state", e)
        }
    }

    @Suppress("DEPRECATION")
    fun isCallActiveNow(): Boolean = try {
        permissions.hasPhoneState() && telephony.callState != TelephonyManager.CALL_STATE_IDLE
    } catch (e: SecurityException) {
        false
    }

    fun onRawState(raw: Int) {
        val step = synchronized(lock) {
            val s = machine.onRawState(raw)
            if (s.changed) _callInfo.value = s.info
            s
        }
        val started = step.started
        if (started != null) dispatch { listener?.onCallActive(started) }
        if (step.ended) dispatch { listener?.onCallEnded() }
    }

    private fun dispatch(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private companion object {
        const val TAG = "CallStateMonitor"
        const val KEY_RING_AT = "ring_elapsed"
        const val RING_VALID_MS = 3 * 60 * 1000L
    }
}
