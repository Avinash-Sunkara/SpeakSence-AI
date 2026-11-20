package com.speaksense.ai.speech

import com.speaksense.ai.data.AppSettings

/**
 * Engine C: offline Whisper.cpp. LABELLED FALLBACK, NOT IMPLEMENTED.
 *
 * Shipping Whisper.cpp needs an NDK/CMake build of the native library plus a multi-hundred-MB model,
 * and small Whisper models are weak at Telugu. This class keeps the engine slot (and the settings
 * option) so a native module can be plugged in later; see README, section "Optional: Whisper.cpp".
 * Until then it reports clearly that it is unavailable instead of pretending to work.
 */
class WhisperCppEngine : SpeechEngine {
    override val displayName: String = "Whisper.cpp (not installed)"

    override fun start(settings: AppSettings, callback: EngineCallback) {
        callback.onFatal(UNAVAILABLE_REASON)
    }

    override fun pause() {}
    override fun resume() {}
    override fun stop() {}

    companion object {
        const val UNAVAILABLE_REASON =
            "The offline Whisper.cpp engine is not bundled in this build. Choose the Android recogniser or your own backend in Settings."
    }
}
