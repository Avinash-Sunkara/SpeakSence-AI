package com.speaksense.ai

import android.app.Application
import com.speaksense.ai.di.AppContainer

class SpeakSenseApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifications.createChannels()
        // The listener must exist before call monitoring starts so an already-active call is not missed.
        container.sessionController.attach()
        container.callMonitor.ensureStarted()
        container.callMonitor.syncFromSystem()
    }
}
