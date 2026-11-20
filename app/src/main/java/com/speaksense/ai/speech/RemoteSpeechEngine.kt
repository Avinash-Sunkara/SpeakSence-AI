package com.speaksense.ai.speech

import android.os.Handler
import android.os.Looper
import com.speaksense.ai.audio.AudioCaptureManager
import com.speaksense.ai.data.AppSettings
import com.speaksense.ai.data.PreferredLanguage
import com.speaksense.ai.service.NetworkMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Engine B (optional, OFF by default): sends short phrases (WAV, in memory only) to a backend the user
 * configures. HTTPS only. No API key is ever built into the app; an optional bearer token is entered
 * by the user and stored encrypted. Expected response: JSON {"text": "..."}.
 */
class RemoteSpeechEngine(
    private val audio: AudioCaptureManager,
    private val network: NetworkMonitor,
) : SpeechEngine {

    override val displayName: String = "Custom backend"

    private val main = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private var captureJob: Job? = null
    private var segments: Channel<ByteArray>? = null
    private var callback: EngineCallback? = null
    private var settings = AppSettings()

    override fun start(settings: AppSettings, callback: EngineCallback) {
        stop()
        val url = settings.backendUrl.trim()
        if (!url.startsWith("https://", ignoreCase = true)) {
            callback.onFatal("The backend address must start with https://. Set it in Settings.")
            return
        }
        this.settings = settings
        this.callback = callback
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        val channel = Channel<ByteArray>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        segments = channel
        s.launch {
            for (pcm in channel) transcribe(pcm, url)
        }
        startCapture()
    }

    override fun pause() {
        captureJob?.cancel()
        captureJob = null
    }

    override fun resume() {
        if (scope != null && captureJob == null) startCapture()
    }

    override fun stop() {
        captureJob?.cancel()
        captureJob = null
        segments?.close()
        segments = null
        scope?.cancel()
        scope = null
        callback = null
    }

    private fun startCapture() {
        val s = scope ?: return
        val channel = segments ?: return
        captureJob?.cancel()
        captureJob = s.launch {
            val collector = SegmentCollector(AudioCaptureManager.FRAME_MS)
            try {
                postMain { callback?.onListening() }
                audio.frames(settings).collect { frame ->
                    val segment = collector.push(frame)
                    val level = collector.lastLevel
                    postMain { callback?.onAudioLevel(level) }
                    if (segment != null) channel.trySend(segment)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                postMain { callback?.onFatal("Microphone error: ${e.message ?: "unknown"}") }
            }
        }
    }

    private suspend fun transcribe(pcm: ByteArray, url: String) {
        if (!network.isOnline()) {
            postMain { callback?.onStatus("No network connection, speech skipped") }
            return
        }
        val wav = WavEncoder.wrap(pcm, AudioCaptureManager.SAMPLE_RATE)
        val text = try {
            withContext(Dispatchers.IO) { post(url, wav) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            postMain { callback?.onStatus("Backend unreachable: ${e.message ?: "error"}") }
            return
        }
        if (text.isNotBlank()) postMain { callback?.onFinal(text.trim(), null) }
    }

    private fun post(url: String, wav: ByteArray): String {
        val hint = when (settings.language) {
            PreferredLanguage.TELUGU -> "te"
            PreferredLanguage.ENGLISH -> "en"
            PreferredLanguage.TELUGU_ENGLISH_MIXED -> "auto"
        }
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 8_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Content-Type", "audio/wav")
            connection.setRequestProperty("X-Language-Hint", hint)
            if (settings.backendToken.isNotEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer ${settings.backendToken}")
            }
            connection.outputStream.use { it.write(wav) }
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return JSONObject(body).optString("text", "")
        } finally {
            connection.disconnect()
        }
    }

    private fun postMain(block: () -> Unit) {
        main.post(block)
    }
}
