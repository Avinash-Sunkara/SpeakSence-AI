package com.speaksense.ai.speech

import android.content.Context
import com.speaksense.ai.audio.AudioCaptureManager
import com.speaksense.ai.data.AppSettings
import com.speaksense.ai.data.EngineChoice
import com.speaksense.ai.service.NetworkMonitor

data class EngineAvailability(val label: String, val ready: Boolean, val detail: String)

/** Creates the engine the user selected and forwards start/pause/resume/stop to it. */
class SpeechRecognitionManager(
    private val context: Context,
    private val audio: AudioCaptureManager,
    private val network: NetworkMonitor,
    private val languages: LanguageDetectionManager,
    private val offlineModels: OfflineModelChecker,
) {
    private var active: SpeechEngine? = null

    /**
     * The engine that will really run. If streaming is selected but no server address is set, we fall back to the
     * Android recogniser in ONLINE mode so captions still appear, and the UI says so (see [engineLabel]).
     */
    fun effectiveEngine(settings: AppSettings): EngineChoice =
        if (settings.engine == EngineChoice.STREAMING_CLOUD && settings.streamingUrl.isBlank())
            EngineChoice.ANDROID_RECOGNIZER
        else settings.engine

    fun engineLabel(settings: AppSettings): String = when (effectiveEngine(settings)) {
        EngineChoice.STREAMING_CLOUD -> "Google Cloud streaming (te-IN)"
        EngineChoice.ANDROID_RECOGNIZER ->
            if (settings.engine == EngineChoice.STREAMING_CLOUD) "Android recogniser (no server set)" else "Android recogniser"
        EngineChoice.REMOTE_BACKEND -> "Custom backend"
        EngineChoice.WHISPER_CPP -> "Whisper.cpp"
    }

    /** Main thread. Replaces any engine that is still running. */
    fun start(settings: AppSettings, callback: EngineCallback) {
        stop()
        val engine: SpeechEngine = when (effectiveEngine(settings)) {
            EngineChoice.STREAMING_CLOUD -> StreamingSpeechEngine(audio, network)
            EngineChoice.ANDROID_RECOGNIZER ->
                AndroidSpeechEngine(context.applicationContext, languages, offlineModels.lastStatus)
            EngineChoice.REMOTE_BACKEND -> RemoteSpeechEngine(audio, network)
            EngineChoice.WHISPER_CPP -> WhisperCppEngine()
        }
        active = engine
        engine.start(settings, callback)
    }

    fun pause() {
        active?.pause()
    }

    fun resume() {
        active?.resume()
    }

    fun stop() {
        active?.stop()
        active = null
    }

    fun describe(settings: AppSettings, status: OfflineModelStatus?): EngineAvailability = when (settings.engine) {
        EngineChoice.STREAMING_CLOUD ->
            if (settings.streamingUrl.trim().let { it.startsWith("wss://", true) || it.startsWith("ws://", true) }) {
                EngineAvailability("Google Cloud streaming", true, "Continuous streaming to your server (te-IN, live partial captions).")
            } else {
                EngineAvailability("Google Cloud streaming", false, "No server address yet. Captions use the Android recogniser (online) until you set a wss:// address in Settings.")
            }
        EngineChoice.ANDROID_RECOGNIZER -> when {
            status == null -> EngineAvailability("Android recogniser", false, "Checking this phone...")
            status.onDeviceRecognizerAvailable ->
                EngineAvailability("Android recogniser", true, "On-device recogniser available.")
            settings.allowOnlineRecognition && status.anyRecognizerAvailable ->
                EngineAvailability("Android recogniser", true, "No on-device recogniser. Online recognition is allowed, so audio goes to your phone's speech service.")
            else ->
                EngineAvailability("Android recogniser", false, "No on-device recogniser. Allow online recognition or use your own backend in Settings.")
        }
        EngineChoice.REMOTE_BACKEND ->
            if (settings.backendUrl.trim().startsWith("https://", ignoreCase = true)) {
                EngineAvailability("Custom backend", true, "Backend configured. Phrases of speech are sent to it over HTTPS.")
            } else {
                EngineAvailability("Custom backend", false, "Set an https:// backend address in Settings.")
            }
        EngineChoice.WHISPER_CPP -> EngineAvailability("Whisper.cpp", false, WhisperCppEngine.UNAVAILABLE_REASON)
    }
}
