package com.speaksense.ai.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.speaksense.ai.SpeakSenseApp
import com.speaksense.ai.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as SpeakSenseApp).container

    val settings: StateFlow<AppSettings> = container.settings.settings

    private val _powerSave = MutableStateFlow(container.battery.isPowerSaveMode())
    val powerSave: StateFlow<Boolean> = _powerSave.asStateFlow()

    private val _batteryExempt = MutableStateFlow(container.battery.isIgnoringBatteryOptimizations())
    val batteryExempt: StateFlow<Boolean> = _batteryExempt.asStateFlow()

    fun refresh() {
        _powerSave.value = container.battery.isPowerSaveMode()
        _batteryExempt.value = container.battery.isIgnoringBatteryOptimizations()
    }

    fun update(transform: (AppSettings) -> AppSettings) = container.settings.update(transform)

    fun clearHistory() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.captions.clearAll() }
        }
    }

    fun guidance(): List<String> = container.battery.manufacturerGuidance()
    fun openBatterySettings() = container.battery.openBatteryOptimizationSettings()
    fun openAutoStartSettings() = container.battery.openAutoStartSettings()
    fun openAppDetails() = container.battery.openAppDetails()
}
