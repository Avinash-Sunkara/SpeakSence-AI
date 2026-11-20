package com.speaksense.ai.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.speaksense.ai.SpeakSenseApp

/** Wakes the process on phone-state changes. Does no work itself and trusts nothing in the intent. */
class CallStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val app = context.applicationContext as? SpeakSenseApp ?: return
        app.container.callMonitor.onPhoneStateBroadcast(intent.getStringExtra(TelephonyManager.EXTRA_STATE))
    }
}
