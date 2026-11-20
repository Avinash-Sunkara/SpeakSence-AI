package com.speaksense.ai.speech

import com.speaksense.ai.data.AppSettings

/** All callbacks are delivered on the main thread. */
interface EngineCallback {
    /** The engine is ready and listening. */
    fun onListening()
    fun onPartial(text: String)
    fun onFinal(text: String, detectedLanguageTag: String?)

    /** Normalised 0..1 input level, used only to tell "silent microphone" from "quiet room". */
    fun onAudioLevel(level: Float)

    /** A temporary, recoverable condition (for example "waiting for network"). */
    fun onStatus(message: String?)

    /**
     * true = the microphone path delivers only digital silence (Android is blocking call audio);
     * false = real audio is flowing again. Engines that cannot tell leave the default no-op.
     */
    fun onAudioBlocked(blocked: Boolean) {}

    /** The engine cannot continue. The session shows the message and stops listening. */
    fun onFatal(message: String)
}

interface SpeechEngine {
    val displayName: String
    fun start(settings: AppSettings, callback: EngineCallback)
    fun pause()
    fun resume()
    fun stop()
}
