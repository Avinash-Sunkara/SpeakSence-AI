package com.speaksense.ai.data

enum class PreferredLanguage { TELUGU, ENGLISH, TELUGU_ENGLISH_MIXED }

enum class EngineChoice {
    /** Continuous WebSocket streaming to your own proxy that calls Google Cloud Speech-to-Text (te-IN). */
    STREAMING_CLOUD,
    ANDROID_RECOGNIZER,
    REMOTE_BACKEND,
    WHISPER_CPP,
}

data class AppSettings(
    val autoCallDetection: Boolean = true,
    val floatingCaptions: Boolean = true,
    /** Try to start the microphone automatically when a call becomes active. */
    val autoStartListening: Boolean = true,
    val language: PreferredLanguage = PreferredLanguage.TELUGU_ENGLISH_MIXED,
    val autoLanguageDetection: Boolean = true,
    val fontSizeSp: Int = 24,
    val captionWidthPercent: Int = 92,
    val captionOpacityPercent: Int = 85,
    val engine: EngineChoice = EngineChoice.STREAMING_CLOUD,
    /** Online mode is required for Telugu on most phones (no on-device Telugu pack). */
    val allowOnlineRecognition: Boolean = true,
    /** wss:// address of the streaming proxy (see backend-streaming/). Empty = not configured. */
    val streamingUrl: String = "",
    val backendUrl: String = "",
    val backendToken: String = "",
    /** Only applies to the backend engine and the microphone test. */
    val noiseSuppression: Boolean = true,
    /** Off by default: echo cancellation removes the caller's voice coming out of the speaker. */
    val echoCancellation: Boolean = false,
    /** Off by default. Audio is never stored; only recognised text, encrypted, when this is on. */
    val saveTranscripts: Boolean = false,
    val overlayX: Int = -1,
    val overlayY: Int = -1,
    val setupCompleted: Boolean = false,
)
