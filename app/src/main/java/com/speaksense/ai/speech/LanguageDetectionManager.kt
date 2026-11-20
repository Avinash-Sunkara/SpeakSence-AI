package com.speaksense.ai.speech

import android.os.Build
import com.speaksense.ai.data.AppSettings
import com.speaksense.ai.data.PreferredLanguage
import com.speaksense.ai.model.Script

data class LanguagePlan(
    val primaryTag: String,
    val alternateTag: String?,
    /** Ask the recogniser to detect the language itself (Android 13+, only where supported). */
    val useNativeDetection: Boolean,
    /** Ask the recogniser to switch language mid-session (Android 14+, only where supported). */
    val useNativeSwitch: Boolean,
    /** Fallback: swap languages after repeated empty results while sound is present. */
    val adaptiveToggle: Boolean,
) {
    val allowedTags: List<String> get() = listOfNotNull(primaryTag, alternateTag)
}

/** Writing-system classifier (pure Kotlin, unit-tested). */
object ScriptClassifier {
    fun classify(text: String): Script {
        var telugu = 0
        var latin = 0
        var other = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp in 0x0C00..0x0C7F -> telugu++
                Character.isLetter(cp) && cp < 0x250 -> latin++
                Character.isLetter(cp) -> other++
            }
        }
        return when {
            telugu > 0 && latin > 0 -> Script.MIXED
            telugu > 0 -> Script.TELUGU
            latin > 0 -> Script.LATIN
            other > 0 -> Script.OTHER
            else -> Script.NONE
        }
    }
}

/**
 * Decides which recogniser languages to use. Honest limits:
 *  - Android SpeechRecognizer runs ONE language at a time.
 *  - Native detection / switching exists only on some recognisers (Android 13 / 14+).
 *  - Where it is missing we fall back to adaptive toggling; code-mixed captions may then be imperfect.
 */
class LanguageDetectionManager {

    fun buildPlan(settings: AppSettings, status: OfflineModelStatus?): LanguagePlan {
        val telugu = pick(listOf("te-IN"), status)
        val english = pick(listOf("en-IN", "en-US", "en-GB"), status)
        val (primary, alternate) = when (settings.language) {
            PreferredLanguage.TELUGU -> telugu to english
            PreferredLanguage.ENGLISH -> english to telugu
            PreferredLanguage.TELUGU_ENGLISH_MIXED -> telugu to english
        }
        val detect = settings.autoLanguageDetection || settings.language == PreferredLanguage.TELUGU_ENGLISH_MIXED
        val sdk = Build.VERSION.SDK_INT
        return LanguagePlan(
            primaryTag = primary,
            alternateTag = if (detect) alternate else null,
            useNativeDetection = detect && sdk >= 33,
            useNativeSwitch = detect && sdk >= 34,
            adaptiveToggle = detect,
        )
    }

    fun swap(plan: LanguagePlan): LanguagePlan {
        val alt = plan.alternateTag ?: return plan
        return plan.copy(primaryTag = alt, alternateTag = plan.primaryTag)
    }

    private fun pick(candidates: List<String>, status: OfflineModelStatus?): String {
        if (status == null) return candidates.first()
        candidates.firstOrNull { c -> status.installedLanguages.any { it.equals(c, ignoreCase = true) } }?.let { return it }
        candidates.firstOrNull { c -> status.supportedOnDevice.any { it.equals(c, ignoreCase = true) } }?.let { return it }
        return candidates.first()
    }
}
