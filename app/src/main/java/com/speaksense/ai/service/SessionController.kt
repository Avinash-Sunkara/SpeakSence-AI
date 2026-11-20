package com.speaksense.ai.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.speaksense.ai.call.CallStateMonitor
import com.speaksense.ai.di.AppContainer
import com.speaksense.ai.model.CallDirection
import com.speaksense.ai.model.SessionPhase
import com.speaksense.ai.model.SessionUiState
import com.speaksense.ai.overlay.FloatingCaptionManager

/**
 * Reacts to call events. When a call becomes active it shows the overlay first (an overlay needs no
 * microphone and a visible overlay window helps Android allow the foreground service to start), then
 * tries to start the caption service. If Android refuses, a one-tap notification is posted instead.
 */
class SessionController(
    private val context: Context,
    private val container: AppContainer,
) : CallStateMonitor.Listener {

    /** Set when the user closes captions during a call, so the same call is not restarted. */
    @Volatile
    var dismissedForThisCall = false

    fun attach() {
        container.callMonitor.listener = this
        installDefaultControls()
    }

    override fun onCallActive(direction: CallDirection) {
        dismissedForThisCall = false
        val settings = container.settings.current
        if (!settings.autoCallDetection) return

        container.notifications.cancelPrompt()
        if (settings.floatingCaptions && container.permissions.canDrawOverlays()) {
            val waiting = SessionUiState(phase = SessionPhase.AWAITING_START, direction = direction)
            container.sessionState.update { waiting }
            container.overlay.applySettings(settings)
            container.overlay.show()
            container.overlay.render(waiting)
        }
        if (!launchService(CaptionForegroundService.ACTION_START_SESSION, direction)) {
            container.notifications.postStartPrompt(direction)
        }
    }

    override fun onCallEnded() {
        dismissedForThisCall = false
        container.notifications.cancelPrompt()
        context.stopService(Intent(context, CaptionForegroundService::class.java))
        container.overlay.hide()
        container.sessionState.reset()
    }

    /** Overlay buttons used while the service is not running (for example when auto-start was blocked). */
    fun installDefaultControls() {
        container.overlay.controls = object : FloatingCaptionManager.Controls {
            override fun onStart() {
                launchService(CaptionForegroundService.ACTION_BEGIN_LISTENING, container.callMonitor.callInfo.value.direction)
            }

            override fun onPause() {}

            override fun onResume() {
                onStart()
            }

            override fun onClose() {
                dismissedForThisCall = true
                container.overlay.hide()
                container.sessionState.reset()
            }
        }
    }

    private fun launchService(action: String, direction: CallDirection): Boolean = try {
        val intent = Intent(context, CaptionForegroundService::class.java)
            .setAction(action)
            .putExtra(CaptionForegroundService.EXTRA_DIRECTION, direction.name)
        ContextCompat.startForegroundService(context, intent)
        true
    } catch (e: Exception) {
        // ForegroundServiceStartNotAllowedException and friends: Android said no. We do not work around it.
        Log.w(TAG, "Android did not allow the caption service to start: ${e.javaClass.simpleName}")
        false
    }

    private companion object {
        const val TAG = "SessionController"
    }
}
