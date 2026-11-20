package com.speaksense.ai.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings live in SharedPreferences (not DataStore) on purpose: the call receiver needs a
 * synchronous read at the moment a call starts, and the in-memory StateFlow gives that.
 */
class SettingsRepository(context: Context, private val crypto: CryptoManager) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("speaksense_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    val current: AppSettings get() = _settings.value

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings) {
        val updated = sanitize(transform(_settings.value))
        write(updated)
        _settings.value = updated
    }

    private fun sanitize(s: AppSettings): AppSettings = s.copy(
        fontSizeSp = s.fontSizeSp.coerceIn(14, 48),
        captionWidthPercent = s.captionWidthPercent.coerceIn(50, 100),
        captionOpacityPercent = s.captionOpacityPercent.coerceIn(30, 100),
    )

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
        try {
            if (name == null) default else enumValueOf<T>(name)
        } catch (e: IllegalArgumentException) {
            default
        }

    private fun read(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            autoCallDetection = prefs.getBoolean(K_AUTO_CALL, d.autoCallDetection),
            floatingCaptions = prefs.getBoolean(K_FLOATING, d.floatingCaptions),
            autoStartListening = prefs.getBoolean(K_AUTO_START, d.autoStartListening),
            language = enumOrDefault(prefs.getString(K_LANGUAGE, null), d.language),
            autoLanguageDetection = prefs.getBoolean(K_AUTO_LANG, d.autoLanguageDetection),
            fontSizeSp = prefs.getInt(K_FONT, d.fontSizeSp),
            captionWidthPercent = prefs.getInt(K_WIDTH, d.captionWidthPercent),
            captionOpacityPercent = prefs.getInt(K_OPACITY, d.captionOpacityPercent),
            engine = enumOrDefault(prefs.getString(K_ENGINE, null), d.engine),
            allowOnlineRecognition = prefs.getBoolean(K_ONLINE, d.allowOnlineRecognition),
            streamingUrl = prefs.getString(K_STREAMING_URL, d.streamingUrl).orEmpty(),
            backendUrl = prefs.getString(K_BACKEND_URL, d.backendUrl).orEmpty(),
            backendToken = prefs.getString(K_BACKEND_TOKEN, null)?.let { crypto.decrypt(it) }.orEmpty(),
            noiseSuppression = prefs.getBoolean(K_NS, d.noiseSuppression),
            echoCancellation = prefs.getBoolean(K_AEC, d.echoCancellation),
            saveTranscripts = prefs.getBoolean(K_SAVE, d.saveTranscripts),
            overlayX = prefs.getInt(K_OVERLAY_X, d.overlayX),
            overlayY = prefs.getInt(K_OVERLAY_Y, d.overlayY),
            setupCompleted = prefs.getBoolean(K_SETUP_DONE, d.setupCompleted),
        )
    }

    private fun write(s: AppSettings) {
        val editor = prefs.edit()
            .putBoolean(K_AUTO_CALL, s.autoCallDetection)
            .putBoolean(K_FLOATING, s.floatingCaptions)
            .putBoolean(K_AUTO_START, s.autoStartListening)
            .putString(K_LANGUAGE, s.language.name)
            .putBoolean(K_AUTO_LANG, s.autoLanguageDetection)
            .putInt(K_FONT, s.fontSizeSp)
            .putInt(K_WIDTH, s.captionWidthPercent)
            .putInt(K_OPACITY, s.captionOpacityPercent)
            .putString(K_ENGINE, s.engine.name)
            .putBoolean(K_ONLINE, s.allowOnlineRecognition)
            .putString(K_STREAMING_URL, s.streamingUrl)
            .putString(K_BACKEND_URL, s.backendUrl)
            .putBoolean(K_NS, s.noiseSuppression)
            .putBoolean(K_AEC, s.echoCancellation)
            .putBoolean(K_SAVE, s.saveTranscripts)
            .putInt(K_OVERLAY_X, s.overlayX)
            .putInt(K_OVERLAY_Y, s.overlayY)
            .putBoolean(K_SETUP_DONE, s.setupCompleted)
        if (s.backendToken.isEmpty()) {
            editor.remove(K_BACKEND_TOKEN)
        } else {
            crypto.encrypt(s.backendToken)?.let { editor.putString(K_BACKEND_TOKEN, it) }
        }
        editor.apply()
    }

    private companion object {
        const val K_AUTO_CALL = "auto_call"
        const val K_FLOATING = "floating"
        const val K_AUTO_START = "auto_start"
        const val K_LANGUAGE = "language"
        const val K_AUTO_LANG = "auto_lang"
        const val K_FONT = "font"
        const val K_WIDTH = "width"
        const val K_OPACITY = "opacity"
        const val K_ENGINE = "engine"
        const val K_ONLINE = "online"
        const val K_BACKEND_URL = "backend_url"
        const val K_STREAMING_URL = "streaming_url"
        const val K_BACKEND_TOKEN = "backend_token_enc"
        const val K_NS = "ns"
        const val K_AEC = "aec"
        const val K_SAVE = "save"
        const val K_OVERLAY_X = "overlay_x"
        const val K_OVERLAY_Y = "overlay_y"
        const val K_SETUP_DONE = "setup_done"
    }
}
