package com.speaksense.ai.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class OfflineModelStatus(
    val checkedAt: Long,
    val onDeviceRecognizerAvailable: Boolean,
    val anyRecognizerAvailable: Boolean,
    /** True only on Android 13+, where installed language packs can be listed. */
    val supportQueryAvailable: Boolean,
    val installedLanguages: Set<String>,
    val supportedOnDevice: Set<String>,
    val onlineLanguages: Set<String>,
    val error: String? = null,
) {
    fun teluguInstalled() = installedLanguages.any { it.startsWith("te", ignoreCase = true) }
    fun englishInstalled() = installedLanguages.any { it.startsWith("en", ignoreCase = true) }
}

/** Reports which offline speech models exist and can ask Android to download one. */
class OfflineModelChecker(private val context: Context) {

    @Volatile
    var lastStatus: OfflineModelStatus? = null
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private var downloadRecognizer: SpeechRecognizer? = null

    suspend fun check(): OfflineModelStatus = withContext(Dispatchers.Main) {
        val status = try {
            doCheck()
        } catch (e: Exception) {
            emptyStatus(error = e.message ?: e.javaClass.simpleName)
        }
        lastStatus = status
        status
    }

    private suspend fun doCheck(): OfflineModelStatus {
        val onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        val any = SpeechRecognizer.isRecognitionAvailable(context)
        if (!onDevice || Build.VERSION.SDK_INT < 33) {
            return emptyStatus(onDevice = onDevice, any = any)
        }
        return queryOnDevice(any)
    }

    @RequiresApi(33)
    private suspend fun queryOnDevice(any: Boolean): OfflineModelStatus {
        val result = withTimeoutOrNull(4_000L) {
            suspendCancellableCoroutine<OfflineModelStatus> { cont ->
                val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                val callback = object : RecognitionSupportCallback {
                    override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                        try { recognizer.destroy() } catch (e: Exception) { /* ignore */ }
                        if (cont.isActive) {
                            cont.resume(
                                OfflineModelStatus(
                                    checkedAt = System.currentTimeMillis(),
                                    onDeviceRecognizerAvailable = true,
                                    anyRecognizerAvailable = any,
                                    supportQueryAvailable = true,
                                    installedLanguages = recognitionSupport.installedOnDeviceLanguages.toSet(),
                                    supportedOnDevice = recognitionSupport.supportedOnDeviceLanguages.toSet(),
                                    onlineLanguages = recognitionSupport.onlineLanguages.toSet(),
                                ),
                            )
                        }
                    }

                    override fun onError(error: Int) {
                        try { recognizer.destroy() } catch (e: Exception) { /* ignore */ }
                        if (cont.isActive) {
                            cont.resume(
                                emptyStatus(onDevice = true, any = any, query = true, error = "Support check failed (code $error)"),
                            )
                        }
                    }
                }
                cont.invokeOnCancellation {
                    try { recognizer.destroy() } catch (e: Exception) { /* ignore */ }
                }
                recognizer.checkRecognitionSupport(intent, ContextCompat.getMainExecutor(context), callback)
            }
        }
        return result ?: emptyStatus(onDevice = true, any = any, query = true, error = "Support check timed out")
    }

    /** Asks Android's on-device recogniser to download a language pack (Android 13+). Main thread. */
    fun requestModelDownload(languageTag: String): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        return try {
            downloadRecognizer?.destroy()
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            downloadRecognizer = recognizer
            recognizer.triggerModelDownload(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag),
            )
            mainHandler.postDelayed({
                try { recognizer.destroy() } catch (e: Exception) { /* ignore */ }
                if (downloadRecognizer === recognizer) downloadRecognizer = null
            }, 120_000L)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun emptyStatus(
        onDevice: Boolean = false,
        any: Boolean = false,
        query: Boolean = false,
        error: String? = null,
    ) = OfflineModelStatus(
        checkedAt = System.currentTimeMillis(),
        onDeviceRecognizerAvailable = onDevice,
        anyRecognizerAvailable = any,
        supportQueryAvailable = query,
        installedLanguages = emptySet(),
        supportedOnDevice = emptySet(),
        onlineLanguages = emptySet(),
        error = error,
    )
}
