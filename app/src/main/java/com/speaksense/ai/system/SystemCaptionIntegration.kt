package com.speaksense.ai.system

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.view.accessibility.CaptioningManager

data class SystemCaptionReport(
    val deviceLine: String,
    val systemCaptioningEnabled: Boolean,
    val onDeviceRecognizerAvailable: Boolean,
    val modeAGuidance: String,
    val modeCVerdict: String,
)

/**
 * Mode A: guide the user to Android's own Live Caption (we cannot control it).
 * Mode C: report honestly whether any public API exposes system call captions to apps.
 */
class SystemCaptionIntegration(private val context: Context) {

    fun report(): SystemCaptionReport {
        val captioning = context.getSystemService(Context.CAPTIONING_SERVICE) as? CaptioningManager
        return SystemCaptionReport(
            deviceLine = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            systemCaptioningEnabled = captioning?.isEnabled == true,
            onDeviceRecognizerAvailable = SpeechRecognizer.isOnDeviceRecognitionAvailable(context),
            modeAGuidance = "Mode A uses Android's built-in Live Caption. Open Settings > Sound (or Accessibility) > Live Caption " +
                "and switch it on. If your phone offers 'Live Caption for calls', enable that too. Availability and " +
                "languages (including Telugu) depend on your phone model and Android version. SpeakSense AI cannot " +
                "control or read Live Caption; it can only take you to the settings.",
            modeCVerdict = "Mode C: Android has no public API that lets a third-party app receive the captions or audio " +
                "of a phone call. Only system apps signed by the phone maker can. So Mode C is not available, and " +
                "SpeakSense AI will not pretend otherwise.",
        )
    }

    fun openCaptionSettings(): Boolean =
        tryOpen(Settings.ACTION_CAPTIONING_SETTINGS) ||
            tryOpen(Settings.ACTION_SOUND_SETTINGS) ||
            tryOpen(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    private fun tryOpen(action: String): Boolean = try {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }
}
