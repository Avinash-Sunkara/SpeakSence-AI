package com.speaksense.ai.model

enum class CallPhase { IDLE, RINGING, ACTIVE }

enum class CallDirection { UNKNOWN, INCOMING, OUTGOING }

data class CallInfo(
    val phase: CallPhase = CallPhase.IDLE,
    val direction: CallDirection = CallDirection.UNKNOWN,
)

/** Writing system of a caption line (used for display and history only). */
enum class Script { NONE, TELUGU, LATIN, MIXED, OTHER }

data class CaptionLine(
    val text: String,
    val script: Script,
    val timestamp: Long,
)

enum class SessionPhase { IDLE, AWAITING_START, STARTING, LISTENING, PAUSED, ERROR }

enum class MicHealth { UNKNOWN, RECEIVING_AUDIO, SILENT }

/** Everything the UI, overlay and notification need to know about the current caption session. */
data class SessionUiState(
    val phase: SessionPhase = SessionPhase.IDLE,
    val direction: CallDirection = CallDirection.UNKNOWN,
    val engineName: String = "",
    val micHealth: MicHealth = MicHealth.UNKNOWN,
    val speakerphoneOn: Boolean? = null,
    val lines: List<CaptionLine> = emptyList(),
    val partial: String = "",
    val message: String? = null,
    val recognizedAnyText: Boolean = false,
    val networkAvailable: Boolean = true,
    val batterySaver: Boolean = false,
) {
    val isActive: Boolean get() = phase != SessionPhase.IDLE

    /** Plain-language answer to "which mode is actually working right now?". */
    fun modeSummary(): String = when (phase) {
        SessionPhase.IDLE -> "No call in progress."
        SessionPhase.AWAITING_START ->
            "Mode B (speakerphone microphone) is ready. Tap Start to begin captions."
        SessionPhase.STARTING -> "Mode B: starting the speech engine..."
        SessionPhase.PAUSED -> "Captions are paused and the microphone is released."
        SessionPhase.ERROR ->
            "Mode B is not working: ${message ?: "unknown error"}. Try Mode A (Android Live Caption)."
        SessionPhase.LISTENING -> when {
            recognizedAnyText -> "Mode B is WORKING: speech from the speaker is being recognised."
            micHealth == MicHealth.SILENT ->
                "Mode B is listening, but the microphone looks silent. Android may be blocking the " +
                    "microphone during this call. Try Mode A (Live Caption)."
            else -> "Mode B is listening. Waiting for speech..."
        }
    }
}
