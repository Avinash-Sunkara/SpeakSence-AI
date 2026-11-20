package com.speaksense.ai.ui.screens

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.speaksense.ai.SpeakSenseApp
import com.speaksense.ai.audio.AudioCaptureManager
import com.speaksense.ai.permissions.PermissionSnapshot
import com.speaksense.ai.speech.EngineAvailability
import com.speaksense.ai.speech.OfflineModelStatus
import com.speaksense.ai.system.SystemCaptionReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MicTestUiState(val running: Boolean = false, val result: AudioCaptureManager.MicTestResult? = null)

class DashboardViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as SpeakSenseApp).container

    val settings = container.settings.settings
    val session = container.sessionState.state
    val call = container.callMonitor.callInfo

    private val _permissions = MutableStateFlow(container.permissions.snapshot())
    val permissions: StateFlow<PermissionSnapshot> = _permissions.asStateFlow()

    private val _batteryExempt = MutableStateFlow(container.battery.isIgnoringBatteryOptimizations())
    val batteryExempt: StateFlow<Boolean> = _batteryExempt.asStateFlow()

    private val _powerSave = MutableStateFlow(container.battery.isPowerSaveMode())
    val powerSave: StateFlow<Boolean> = _powerSave.asStateFlow()

    private val _offline = MutableStateFlow<OfflineModelStatus?>(container.offlineModels.lastStatus)
    val offlineStatus: StateFlow<OfflineModelStatus?> = _offline.asStateFlow()

    private val _micTest = MutableStateFlow(MicTestUiState())
    val micTest: StateFlow<MicTestUiState> = _micTest.asStateFlow()

    private val _checkingModels = MutableStateFlow(false)
    val checkingModels: StateFlow<Boolean> = _checkingModels.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    fun systemReport(): SystemCaptionReport = container.systemCaptions.report()

    fun engineAvailability(): EngineAvailability =
        container.speech.describe(container.settings.current, _offline.value)

    fun refresh() {
        _permissions.value = container.permissions.snapshot()
        _batteryExempt.value = container.battery.isIgnoringBatteryOptimizations()
        _powerSave.value = container.battery.isPowerSaveMode()
        container.callMonitor.ensureStarted()
        checkModels()
    }

    fun checkModels() {
        if (_checkingModels.value) return
        viewModelScope.launch {
            _checkingModels.value = true
            try {
                _offline.value = container.offlineModels.check()
            } finally {
                _checkingModels.value = false
            }
        }
    }

    fun runMicTest() {
        if (_micTest.value.running) return
        viewModelScope.launch {
            _micTest.value = MicTestUiState(running = true)
            val result = container.audio.runMicTest(container.settings.current)
            _micTest.value = MicTestUiState(running = false, result = result)
        }
    }

    fun downloadModel(languageTag: String) {
        val started = container.offlineModels.requestModelDownload(languageTag)
        _toast.value = if (started) {
            "Download requested for $languageTag. It may take a few minutes. Tap 'Check again' afterwards."
        } else {
            "This phone cannot start a model download from here (needs Android 13+ and an on-device recogniser)."
        }
    }

    fun openVoiceInputSettings(): Boolean = try {
        getApplication<Application>().startActivity(
            Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (e: Exception) {
        false
    }

    fun consumeToast() {
        _toast.value = null
    }

    fun overlayIntent(): Intent = container.permissions.overlaySettingsIntent()
    fun appDetailsIntent(): Intent = container.permissions.appDetailsIntent()
    fun openBatterySettings() = container.battery.openBatteryOptimizationSettings()
    fun openAutoStartSettings() = container.battery.openAutoStartSettings()
    fun openCaptionSettings() = container.systemCaptions.openCaptionSettings()

    fun markSetupCompleted() {
        container.settings.update { it.copy(setupCompleted = true) }
    }
}
