package com.speaksense.ai.service

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.speaksense.ai.SpeakSenseApp
import com.speaksense.ai.data.EngineChoice
import com.speaksense.ai.di.AppContainer
import com.speaksense.ai.model.CallDirection
import com.speaksense.ai.model.CallPhase
import com.speaksense.ai.model.CaptionLine
import com.speaksense.ai.model.MicHealth
import com.speaksense.ai.model.SessionPhase
import com.speaksense.ai.model.SessionUiState
import com.speaksense.ai.overlay.FloatingCaptionManager
import com.speaksense.ai.speech.EngineCallback
import com.speaksense.ai.speech.ScriptClassifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns one caption session per call: overlay, speech engine, microphone, notification, transcript.
 *
 * Foreground-service strategy (Android 14+ rules):
 *  1. Try to enter foreground with the MICROPHONE type. Allowed only when Android considers the app
 *     "in use" (for example a visible overlay or a user tap). That gives automatic captions.
 *  2. If refused, run as a SPECIAL_USE service showing the overlay with a Start button; the tap on
 *     Start (or the notification) is the user interaction that lets us upgrade to the microphone type.
 * We never try to bypass a refusal.
 */
class CaptionForegroundService : Service() {

    private lateinit var container: AppContainer
    private var scope: CoroutineScope? = null
    private var sessionActive = false
    private var listening = false
    private var micForeground = false
    private var transcriptSessionId: Long? = null
    private var startJob: Job? = null
    private var healthJob: Job? = null
    private var powerReceiver: BroadcastReceiver? = null
    private var listeningSince = 0L
    private var lastNotificationKey: String? = null

    @Volatile
    private var lastLoudAt = 0L

    override fun onCreate() {
        super.onCreate()
        container = (application as SpeakSenseApp).container
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_SESSION

        if (intent == null) {
            // Restarted by Android after the process was killed. Only continue if the call is still on.
            if (!container.callMonitor.isCallActiveNow() || container.sessionController.dismissedForThisCall) {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        when (action) {
            ACTION_STOP -> {
                endSession(userInitiated = true)
                return START_NOT_STICKY
            }
            ACTION_BEGIN_LISTENING -> {
                val micOk = promoteToForeground(wantMic = true)
                if (!sessionActive) beginSession(directionFrom(intent))
                if (micOk) startEngine() else setError(MIC_BLOCKED_MESSAGE)
            }
            else -> {
                val wantAuto = container.settings.current.autoStartListening && container.permissions.hasMic()
                val micOk = promoteToForeground(wantMic = wantAuto)
                if (!sessionActive) beginSession(directionFrom(intent))
                when {
                    wantAuto && micOk -> startEngine()
                    wantAuto -> setAwaiting("Android needs one tap to start the microphone. Tap Start.")
                    else -> setAwaiting(null)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (sessionActive) teardown()
        super.onDestroy()
    }

    // ---- foreground handling --------------------------------------------------------------

    /** Returns true if the service now holds a foreground state that allows microphone use. */
    private fun promoteToForeground(wantMic: Boolean): Boolean {
        val notification = container.notifications.buildSession(container.sessionState.state.value)
        val hasMic = container.permissions.hasMic()
        if (Build.VERSION.SDK_INT >= 34) {
            if (wantMic && hasMic) {
                try {
                    ServiceCompat.startForeground(
                        this, NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                    )
                    micForeground = true
                    return true
                } catch (e: Exception) {
                    Log.w(TAG, "Microphone foreground type refused: ${e.javaClass.simpleName}")
                }
            }
            if (micForeground) return true // already upgraded earlier; keep it
            return try {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
                false
            } catch (e: Exception) {
                Log.e(TAG, "Cannot enter foreground at all", e)
                stopSelf()
                false
            }
        }
        // Android 12-13: no per-type enforcement at start, but a background-started service may get a silent mic.
        return try {
            startForeground(NOTIFICATION_ID, notification)
            micForeground = hasMic
            hasMic
        } catch (e: Exception) {
            Log.e(TAG, "Cannot enter foreground", e)
            stopSelf()
            false
        }
    }

    // ---- session lifecycle ----------------------------------------------------------------

    private fun directionFrom(intent: Intent?): CallDirection {
        val name = intent?.getStringExtra(EXTRA_DIRECTION)
        val parsed = try {
            if (name == null) null else CallDirection.valueOf(name)
        } catch (e: IllegalArgumentException) {
            null
        }
        return parsed ?: container.callMonitor.callInfo.value.direction
    }

    private fun beginSession(direction: CallDirection) {
        sessionActive = true
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = newScope
        val settings = container.settings.current

        container.sessionState.update {
            SessionUiState(
                phase = SessionPhase.AWAITING_START,
                direction = direction,
                engineName = container.speech.engineLabel(settings),
            )
        }
        container.overlay.controls = overlayControls
        if (settings.floatingCaptions && container.permissions.canDrawOverlays()) {
            container.overlay.applySettings(settings)
            container.overlay.show()
        }
        container.network.start()
        registerPowerReceiver()
        refreshDeviceStatus()

        newScope.launch { container.network.online.collect { online -> container.sessionState.update { it.copy(networkAvailable = online) } } }
        newScope.launch { container.settings.settings.collect { s -> container.overlay.applySettings(s) } }
        newScope.launch {
            container.sessionState.state.collect { st ->
                container.overlay.render(st)
                updateNotification(st)
            }
        }
        newScope.launch {
            container.callMonitor.callInfo.collect { info ->
                if (info.phase == CallPhase.IDLE) endSession(userInitiated = false)
            }
        }
        if (settings.saveTranscripts) {
            container.appScope.launch(Dispatchers.IO) {
                try {
                    transcriptSessionId = container.captions.startSession(direction.name, container.speech.engineLabel(settings))
                } catch (e: Exception) {
                    Log.w(TAG, "Could not create transcript session", e)
                }
            }
        }
    }

    private fun startEngine() {
        if (!sessionActive || listening) return
        listening = true
        listeningSince = SystemClock.elapsedRealtime()
        lastLoudAt = 0L
        val settings = container.settings.current
        container.sessionState.update {
            it.copy(
                phase = SessionPhase.STARTING,
                message = null,
                micHealth = MicHealth.UNKNOWN,
                partial = "",
                engineName = container.speech.engineLabel(settings),
            )
        }
        refreshDeviceStatus()
        startJob?.cancel()
        startJob = scope?.launch {
            if (container.speech.effectiveEngine(settings) == EngineChoice.ANDROID_RECOGNIZER && container.offlineModels.lastStatus == null) {
                withTimeoutOrNull(3_000L) { container.offlineModels.check() }
            }
            if (!sessionActive || !listening) return@launch
            container.speech.start(container.settings.current, engineCallback)
            startHealthLoop()
        }
    }

    private fun pauseListening() {
        if (!sessionActive || !listening) return
        container.speech.pause()
        healthJob?.cancel()
        container.sessionState.update { it.copy(phase = SessionPhase.PAUSED, partial = "", message = null) }
    }

    private fun resumeListening() {
        if (!sessionActive) return
        if (!listening) {
            startEngine()
            return
        }
        listeningSince = SystemClock.elapsedRealtime()
        lastLoudAt = 0L
        container.sessionState.update { it.copy(phase = SessionPhase.STARTING, message = null) }
        container.speech.resume()
        startHealthLoop()
    }

    private fun startFromOverlay() {
        val micOk = promoteToForeground(wantMic = true)
        if (micOk) startEngine() else setError(MIC_BLOCKED_MESSAGE)
    }

    private fun setAwaiting(message: String?) {
        container.sessionState.update { it.copy(phase = SessionPhase.AWAITING_START, message = message) }
    }

    private fun setError(message: String) {
        listening = false
        container.sessionState.update { it.copy(phase = SessionPhase.ERROR, message = message, partial = "") }
    }

    private fun endSession(userInitiated: Boolean) {
        if (userInitiated) container.sessionController.dismissedForThisCall = true
        if (sessionActive) teardown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Releases microphone, engine, overlay and receivers. Safe to call more than once. */
    private fun teardown() {
        sessionActive = false
        listening = false
        startJob?.cancel()
        healthJob?.cancel()
        scope?.cancel()
        scope = null
        try { container.speech.stop() } catch (e: Exception) { Log.w(TAG, "speech.stop failed", e) }
        unregisterPowerReceiver()
        container.network.stop()
        container.overlay.hide()
        container.sessionController.installDefaultControls()
        val id = transcriptSessionId
        transcriptSessionId = null
        if (id != null) {
            container.appScope.launch(Dispatchers.IO) {
                try { container.captions.endSession(id) } catch (e: Exception) { Log.w(TAG, "endSession failed", e) }
            }
        }
        container.sessionState.reset()
    }

    // ---- engine callbacks -----------------------------------------------------------------

    private val engineCallback = object : EngineCallback {
        override fun onListening() {
            container.sessionState.update {
                if (it.phase == SessionPhase.STARTING) it.copy(phase = SessionPhase.LISTENING, message = null) else it
            }
        }

        override fun onPartial(text: String) {
            container.sessionState.update {
                it.copy(
                    partial = text,
                    micHealth = MicHealth.RECEIVING_AUDIO,
                    phase = if (it.phase == SessionPhase.STARTING) SessionPhase.LISTENING else it.phase,
                    message = null,
                )
            }
        }

        override fun onFinal(text: String, detectedLanguageTag: String?) {
            val line = CaptionLine(text, ScriptClassifier.classify(text), System.currentTimeMillis())
            container.sessionState.update {
                it.copy(
                    lines = (it.lines + line).takeLast(MAX_LINES_IN_MEMORY),
                    partial = "",
                    micHealth = MicHealth.RECEIVING_AUDIO,
                    recognizedAnyText = true,
                    phase = if (it.phase == SessionPhase.STARTING) SessionPhase.LISTENING else it.phase,
                    message = null,
                )
            }
            val id = transcriptSessionId
            if (id != null && container.settings.current.saveTranscripts) {
                container.appScope.launch(Dispatchers.IO) {
                    try { container.captions.appendLine(id, line) } catch (e: Exception) { Log.w(TAG, "appendLine failed", e) }
                }
            }
        }

        override fun onAudioLevel(level: Float) {
            if (level >= LOUD_LEVEL) lastLoudAt = SystemClock.elapsedRealtime()
        }

        override fun onStatus(message: String?) {
            container.sessionState.update { it.copy(message = message) }
        }

        override fun onAudioBlocked(blocked: Boolean) {
            if (blocked) {
                // Never show "listening" while Android is feeding us digital silence.
                container.sessionState.update {
                    it.copy(micHealth = MicHealth.SILENT, message = BLOCKED_MESSAGE)
                }
            } else {
                container.sessionState.update {
                    it.copy(micHealth = MicHealth.RECEIVING_AUDIO, message = null)
                }
            }
        }

        override fun onFatal(message: String) {
            listening = false
            healthJob?.cancel()
            container.speech.stop()
            setError(message)
        }
    }

    private val overlayControls = object : FloatingCaptionManager.Controls {
        override fun onStart() = startFromOverlay()
        override fun onPause() = pauseListening()
        override fun onResume() = resumeListening()
        override fun onClose() = endSession(userInitiated = true)
    }

    // ---- health, battery saver, notification ---------------------------------------------

    private fun startHealthLoop() {
        healthJob?.cancel()
        healthJob = scope?.launch {
            while (isActive) {
                delay(HEALTH_INTERVAL_MS)
                refreshDeviceStatus()
                val now = SystemClock.elapsedRealtime()
                val st = container.sessionState.state.value
                if (st.phase != SessionPhase.LISTENING && st.phase != SessionPhase.STARTING) continue
                val heardRecently = lastLoudAt != 0L && now - lastLoudAt < SILENCE_WARNING_MS
                val silent = !st.recognizedAnyText && !heardRecently && now - listeningSince > SILENCE_WARNING_MS
                val health = when {
                    st.recognizedAnyText || heardRecently -> MicHealth.RECEIVING_AUDIO
                    silent -> MicHealth.SILENT
                    else -> st.micHealth
                }
                if (health != st.micHealth) container.sessionState.update { it.copy(micHealth = health) }
            }
        }
    }

    private fun refreshDeviceStatus() {
        val saver = (getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode
        val speaker = container.audio.isSpeakerphoneOn()
        container.sessionState.update { it.copy(speakerphoneOn = speaker, batterySaver = saver) }
    }

    private fun registerPowerReceiver() {
        if (powerReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                refreshDeviceStatus()
            }
        }
        ContextCompat.registerReceiver(
            this, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        powerReceiver = receiver
    }

    private fun unregisterPowerReceiver() {
        val receiver = powerReceiver ?: return
        try { unregisterReceiver(receiver) } catch (e: Exception) { /* already gone */ }
        powerReceiver = null
    }

    private fun updateNotification(state: SessionUiState) {
        if (!sessionActive) return
        val key = "${state.phase}|${state.message}|${state.speakerphoneOn}|${state.batterySaver}|${state.networkAvailable}|${state.recognizedAnyText}|${state.micHealth}"
        if (key == lastNotificationKey) return
        lastNotificationKey = key
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, container.notifications.buildSession(state))
    }

    companion object {
        const val ACTION_START_SESSION = "com.speaksense.ai.action.START_SESSION"
        const val ACTION_BEGIN_LISTENING = "com.speaksense.ai.action.BEGIN_LISTENING"
        const val ACTION_STOP = "com.speaksense.ai.action.STOP"
        const val EXTRA_DIRECTION = "direction"
        const val NOTIFICATION_ID = 1001

        private const val TAG = "CaptionService"
        private const val SILENCE_WARNING_MS = 12_000L
        private const val HEALTH_INTERVAL_MS = 5_000L
        private const val LOUD_LEVEL = 0.06f
        private const val MAX_LINES_IN_MEMORY = 40
        private const val BLOCKED_MESSAGE =
            "No sound is reaching the microphone. Android or this phone is blocking it during the call. " +
                "Turn speakerphone ON, or use Live Caption (Mode A)."
        private const val MIC_BLOCKED_MESSAGE =
            "Android did not allow microphone capture. Open the SpeakSense AI app, then try again."
    }
}
