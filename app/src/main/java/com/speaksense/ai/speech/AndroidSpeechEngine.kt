package com.speaksense.ai.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.speaksense.ai.data.AppSettings
import kotlin.math.min

/**
 * Engine A: Android SpeechRecognizer, preferring the ON-DEVICE recogniser.
 * The default (possibly cloud) recogniser is used only if the user enabled online recognition.
 * Must be driven from the main thread. Restarts itself after each utterance because
 * SpeechRecognizer is designed for short dictation, not endless listening.
 */
class AndroidSpeechEngine(
    private val context: Context,
    private val languages: LanguageDetectionManager,
    private val offlineStatus: OfflineModelStatus?,
) : SpeechEngine {

    override val displayName: String = "Android SpeechRecognizer"

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var callback: EngineCallback? = null
    private var settings = AppSettings()
    private var plan: LanguagePlan? = null
    private var usingOnDevice = false
    private var running = false
    private var paused = false
    private var generation = 0
    private var heardSound = false
    private var consecutiveEmpty = 0
    private var consecutiveFailures = 0
    private var downloadRequested = false
    private var languageFallbackUsed = false
    private var lastDetectedLanguage: String? = null
    private var restartRunnable: Runnable? = null

    override fun start(settings: AppSettings, callback: EngineCallback) {
        onMain {
            stopInternal()
            this.settings = settings
            this.callback = callback
            this.plan = languages.buildPlan(settings, offlineStatus)
            running = true
            paused = false
            consecutiveEmpty = 0
            consecutiveFailures = 0
            if (createRecognizer()) listen()
        }
    }

    override fun pause() {
        onMain {
            if (!running) return@onMain
            paused = true
            cancelRestart()
            destroyRecognizer() // releases the microphone
        }
    }

    override fun resume() {
        onMain {
            if (!running || !paused) return@onMain
            paused = false
            if (createRecognizer()) listen()
        }
    }

    override fun stop() {
        onMain { stopInternal() }
    }

    private fun stopInternal() {
        running = false
        paused = false
        cancelRestart()
        destroyRecognizer()
        callback = null
    }

    // ---- recogniser lifecycle -------------------------------------------------------------

    private fun createRecognizer(): Boolean {
        destroyRecognizer()
        val created: SpeechRecognizer? = try {
            when {
                // Online mode first: Telugu has no on-device pack on most phones (including Vivo), and the
                // on-device-first order used to end in "no recogniser" and therefore no captions at all.
                settings.allowOnlineRecognition && SpeechRecognizer.isRecognitionAvailable(context) -> {
                    usingOnDevice = false
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context) -> {
                    usingOnDevice = true
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not create recogniser", e)
            null
        }
        if (created == null) {
            fatal(
                if (settings.allowOnlineRecognition) {
                    "No speech recogniser is available on this phone."
                } else {
                    "This phone has no on-device speech recogniser. Enable online recognition in Settings (audio is then " +
                        "processed by your phone's speech service) or use your own backend."
                },
            )
            return false
        }
        generation++
        created.setRecognitionListener(Listener(generation))
        recognizer = created
        return true
    }

    private fun destroyRecognizer() {
        generation++ // any late callback from the old recogniser is ignored
        val r = recognizer ?: return
        recognizer = null
        try { r.cancel() } catch (e: Exception) { /* ignore */ }
        try { r.destroy() } catch (e: Exception) { /* ignore */ }
    }

    private fun listen() {
        if (!running || paused) return
        val r = recognizer ?: return
        val p = plan ?: return
        heardSound = false
        try {
            r.startListening(buildIntent(p))
        } catch (e: Exception) {
            Log.w(TAG, "startListening failed", e)
            backoff("Speech engine error, retrying")
        }
    }

    private fun buildIntent(p: LanguagePlan): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, p.primaryTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Only prefer offline when the user did not allow online; forcing offline for Telugu returned nothing.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !settings.allowOnlineRecognition)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            // Extras added in Android 13 / 14. String keys are used so older SDK stubs are never needed;
            // recognisers that do not understand them simply ignore them.
            if (Build.VERSION.SDK_INT >= 33 && p.useNativeDetection) {
                putExtra("android.speech.extra.ENABLE_LANGUAGE_DETECTION", true)
                putStringArrayListExtra("android.speech.extra.LANGUAGE_DETECTION_ALLOWED_LANGUAGES", ArrayList(p.allowedTags))
            }
            if (Build.VERSION.SDK_INT >= 34 && p.useNativeSwitch) {
                putExtra("android.speech.extra.ENABLE_LANGUAGE_SWITCH", "balanced")
                putStringArrayListExtra("android.speech.extra.LANGUAGE_SWITCH_ALLOWED_LANGUAGES", ArrayList(p.allowedTags))
            }
        }

    private fun scheduleRestart(delayMs: Long, recreate: Boolean) {
        cancelRestart()
        val runnable = Runnable {
            restartRunnable = null
            if (!running || paused) return@Runnable
            if (recreate || recognizer == null) {
                if (!createRecognizer()) return@Runnable
            }
            listen()
        }
        restartRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun cancelRestart() {
        restartRunnable?.let { handler.removeCallbacks(it) }
        restartRunnable = null
    }

    // ---- results and errors ---------------------------------------------------------------

    private fun handleResults(results: Bundle?) {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()?.trim().orEmpty()
        if (text.isNotEmpty()) {
            consecutiveEmpty = 0
            consecutiveFailures = 0
            val detected = lastDetectedLanguage
            lastDetectedLanguage = null
            callback?.onFinal(text, detected)
        } else if (heardSound) {
            onEmptyResult()
        }
        scheduleRestart(RESTART_DELAY_MS, recreate = false)
    }

    private fun onEmptyResult() {
        consecutiveEmpty++
        val p = plan ?: return
        if (p.adaptiveToggle && p.alternateTag != null && consecutiveEmpty >= EMPTY_BEFORE_SWITCH) {
            consecutiveEmpty = 0
            val swapped = languages.swap(p)
            plan = swapped
            callback?.onStatus("Trying ${swapped.primaryTag}")
        }
    }

    private fun handleError(error: Int) {
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                if (heardSound) onEmptyResult()
                scheduleRestart(150L, recreate = false)
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> backoff("Speech engine busy, retrying", recreate = true)
            SpeechRecognizer.ERROR_CLIENT -> scheduleRestart(500L, recreate = true)
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                fatal("Microphone permission was turned off. Open SpeakSense AI and allow it again.")
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> backoff("Speech service unreachable, waiting for network")
            SpeechRecognizer.ERROR_AUDIO -> backoff("Microphone unavailable, retrying", recreate = true)
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> onLanguageUnavailable()
            else -> backoff("Speech engine error ($error), retrying", recreate = true)
        }
    }

    private fun onLanguageUnavailable() {
        val p = plan ?: return
        if (usingOnDevice && !downloadRequested && Build.VERSION.SDK_INT >= 33) {
            downloadRequested = true
            try {
                recognizer?.triggerModelDownload(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, p.primaryTag),
                )
                callback?.onStatus("Downloading ${p.primaryTag} speech model...")
                scheduleRestart(15_000L, recreate = false)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Model download request failed", e)
            }
        }
        val alt = p.alternateTag
        if (alt != null && !languageFallbackUsed) {
            languageFallbackUsed = true
            plan = languages.swap(p)
            callback?.onStatus("${p.primaryTag} is not available offline, using $alt")
            scheduleRestart(300L, recreate = false)
            return
        }
        fatal(
            "Speech recognition for ${p.primaryTag} is not available on this phone. Download the language in your " +
                "phone's voice input / Google app offline speech settings, or enable online recognition in Settings.",
        )
    }

    private fun backoff(message: String, recreate: Boolean = false) {
        consecutiveFailures++
        if (consecutiveFailures > MAX_FAILURES) {
            fatal("Speech recognition keeps failing. Please restart captions.")
            return
        }
        callback?.onStatus(message)
        val delay = min(10_000L, 1_000L shl min(consecutiveFailures - 1, 4))
        scheduleRestart(delay, recreate)
    }

    private fun fatal(message: String) {
        val cb = callback
        running = false
        cancelRestart()
        destroyRecognizer()
        cb?.onFatal(message)
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    private inner class Listener(private val gen: Int) : RecognitionListener {
        private fun stale() = gen != generation || !running

        override fun onReadyForSpeech(params: Bundle?) {
            if (stale()) return
            callback?.onListening()
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {
            if (stale()) return
            // Typical recogniser range is about -2..10 dB.
            val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            if (level > 0.25f) heardSound = true
            callback?.onAudioLevel(level)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            if (stale()) return
            handleError(error)
        }

        override fun onResults(results: Bundle?) {
            if (stale()) return
            handleResults(results)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (stale()) return
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if (text.isNotEmpty()) callback?.onPartial(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onLanguageDetection(results: Bundle) {
            if (stale()) return
            lastDetectedLanguage = results.getString("detected_language")
        }
    }

    private companion object {
        const val TAG = "AndroidSpeechEngine"
        const val RESTART_DELAY_MS = 60L
        const val EMPTY_BEFORE_SWITCH = 3
        const val MAX_FAILURES = 12
    }
}
