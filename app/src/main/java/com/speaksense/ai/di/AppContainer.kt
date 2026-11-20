package com.speaksense.ai.di

import android.content.Context
import com.speaksense.ai.audio.AudioCaptureManager
import com.speaksense.ai.call.CallStateMonitor
import com.speaksense.ai.data.CaptionRepository
import com.speaksense.ai.data.CryptoManager
import com.speaksense.ai.data.SettingsRepository
import com.speaksense.ai.data.db.AppDatabase
import com.speaksense.ai.overlay.FloatingCaptionManager
import com.speaksense.ai.permissions.BatteryOptimizationHelper
import com.speaksense.ai.permissions.PermissionManager
import com.speaksense.ai.service.NetworkMonitor
import com.speaksense.ai.service.NotificationHelper
import com.speaksense.ai.service.SessionController
import com.speaksense.ai.service.SessionStateHolder
import com.speaksense.ai.speech.LanguageDetectionManager
import com.speaksense.ai.speech.OfflineModelChecker
import com.speaksense.ai.speech.SpeechRecognitionManager
import com.speaksense.ai.system.SystemCaptionIntegration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Hand-written dependency container (no DI framework to keep the build simple and fast). */
class AppContainer(context: Context) {
    private val appContext: Context = context.applicationContext

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val crypto = CryptoManager()
    val settings = SettingsRepository(appContext, crypto)
    val permissions = PermissionManager(appContext)
    val battery = BatteryOptimizationHelper(appContext)
    val network = NetworkMonitor(appContext)
    val sessionState = SessionStateHolder()
    val notifications = NotificationHelper(appContext)
    val callMonitor = CallStateMonitor(appContext, permissions)
    val audio = AudioCaptureManager(appContext)
    val languageDetection = LanguageDetectionManager()
    val offlineModels = OfflineModelChecker(appContext)
    val speech = SpeechRecognitionManager(appContext, audio, network, languageDetection, offlineModels)
    val overlay = FloatingCaptionManager(appContext, settings)
    val systemCaptions = SystemCaptionIntegration(appContext)

    private val database: AppDatabase by lazy { AppDatabase.build(appContext) }
    val captions: CaptionRepository by lazy { CaptionRepository(database.transcriptDao(), crypto) }

    val sessionController = SessionController(appContext, this)
}
