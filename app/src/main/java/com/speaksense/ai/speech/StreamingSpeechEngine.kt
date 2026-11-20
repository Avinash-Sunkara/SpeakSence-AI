package com.speaksense.ai.speech

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.speaksense.ai.audio.AudioCaptureManager
import com.speaksense.ai.data.AppSettings
import com.speaksense.ai.data.PreferredLanguage
import com.speaksense.ai.service.NetworkMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Continuous cloud streaming engine.
 *
 * Microphone frames (PCM16, 16 kHz, mono, 40 ms) go out over ONE long-lived WebSocket to the SpeakSense
 * streaming proxy (backend-streaming/), which holds the Google Cloud Speech-to-Text credentials and streams
 * to Google with te-IN and interim results. No cloud key exists in this app; the only secret is the access
 * token you type in Settings (stored encrypted).
 *
 * Design rules:
 *  - Audio is sent continuously, including silence. The server/Google do the end-pointing. There is no
 *    client-side "phrase finished" step, so nothing is held back and no short word is discarded.
 *  - The microphone keeps running while the socket is down. Up to [MAX_BUFFER_MS] of audio is kept and
 *    re-sent after reconnect, so a short network dip does not lose words.
 *  - Reconnect is automatic with exponential back-off; losing the network cancels the socket at once instead
 *    of waiting for a ping timeout. Authorisation failures (401/403) are reported, not retried forever.
 *
 * Callbacks are delivered on the main thread (as [EngineCallback] requires).
 */
class StreamingSpeechEngine(
    private val audio: AudioCaptureManager,
    private val network: NetworkMonitor,
) : SpeechEngine {

    override val displayName: String = "Google Cloud streaming"

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    private var scope: CoroutineScope? = null
    private var captureJob: Job? = null
    private var connectionJob: Job? = null
    private var callback: EngineCallback? = null
    private var settings = AppSettings()
    private var client: OkHttpClient? = null

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var micOpen = false

    private val queue = ConcurrentLinkedDeque<ByteArray>()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val deduper = FinalDeduper()

    override fun start(settings: AppSettings, callback: EngineCallback) {
        stop()
        val url = settings.streamingUrl.trim()
        if (!isAcceptableUrl(url)) {
            callback.onFatal("Set the streaming server address (wss://...) in Settings.")
            return
        }
        synchronized(lock) {
            this.settings = settings
            this.callback = callback
            deduper.reset()
            queue.clear()
            client = OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived stream
                .pingInterval(10, TimeUnit.SECONDS) // detects dead links
                .build()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        }
        startCapture()
        startConnection(url)
    }

    override fun pause() {
        // Releases the microphone and the socket. resume() starts both again.
        synchronized(lock) {
            captureJob?.cancel(); captureJob = null
            connectionJob?.cancel(); connectionJob = null
            socket?.cancel(); socket = null
            micOpen = false
            queue.clear()
        }
    }

    override fun resume() {
        val url = settings.streamingUrl.trim()
        synchronized(lock) {
            if (scope == null || captureJob != null) return
        }
        startCapture()
        startConnection(url)
    }

    override fun stop() {
        synchronized(lock) {
            captureJob?.cancel(); captureJob = null
            connectionJob?.cancel(); connectionJob = null
            socket?.cancel(); socket = null
            scope?.cancel(); scope = null
            client?.let {
                it.dispatcher.executorService.shutdown()
                it.connectionPool.evictAll()
            }
            client = null
            callback = null
            micOpen = false
            queue.clear()
        }
    }

    // ---- microphone -> queue ----------------------------------------------------------------

    private fun startCapture() {
        val s = scope ?: return
        captureJob?.cancel()
        captureJob = s.launch {
            val listener = object : AudioCaptureManager.CaptureListener {
                override fun onSourceOpened(name: String) {
                    Log.i(TAG, "Microphone source opened: $name")
                    micOpen = true
                }

                override fun onBlocked(blocked: Boolean) {
                    postMain { callback?.onAudioBlocked(blocked) }
                }
            }
            var frameCount = 0
            try {
                audio.frames(settings, listener).collect { frame ->
                    queue.addLast(toBytes(frame))
                    while (queue.size > MAX_BUFFER_FRAMES) queue.pollFirst()
                    signal.trySend(Unit)
                    if (++frameCount % LEVEL_EVERY_N_FRAMES == 0) {
                        val lvl = level(frame)
                        postMain { callback?.onAudioLevel(lvl) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                postMain { callback?.onFatal("Microphone error: ${e.message ?: "unknown"}") }
            }
        }
    }

    // ---- socket lifecycle -------------------------------------------------------------------

    private fun startConnection(url: String) {
        val s = scope ?: return
        val http = client ?: return
        connectionJob?.cancel()

        // Losing the network kills the socket immediately; the loop below then waits for it to return.
        s.launch {
            network.online.collect { online ->
                if (!online) socket?.cancel()
            }
        }

        connectionJob = s.launch {
            var attempt = 0
            var handshakeRejections = 0
            while (isActive) {
                if (!network.isOnline()) {
                    postMain { callback?.onStatus("No internet. Captions resume when it returns.") }
                    network.online.first { it }
                    attempt = 0
                }
                val conn = Connection()
                val request = Request.Builder().url(url).apply {
                    val token = settings.backendToken
                    if (token.isNotEmpty()) header("Authorization", "Bearer $token")
                    header("X-Client", "speaksense-android")
                }.build()
                val ws = http.newWebSocket(request, conn)
                socket = ws

                val ready = try {
                    withTimeout(READY_TIMEOUT_MS) { conn.ready.await() }
                    true
                } catch (e: TimeoutCancellationException) {
                    false
                } catch (e: CancellationException) {
                    ws.cancel()
                    throw e
                } catch (e: Exception) {
                    false
                }

                if (ready) {
                    attempt = 0
                    handshakeRejections = 0
                    postMain { callback?.onListening() }
                    pump(ws, conn) // returns when the socket ends or sending fails
                } else {
                    ws.cancel()
                    val code = conn.httpCode
                    if (code == 401 || code == 403) {
                        postMain { callback?.onFatal("The streaming server rejected the access token (HTTP $code). Check it in Settings.") }
                        return@launch
                    }
                    if (code != 0) {
                        handshakeRejections++
                        if (handshakeRejections >= MAX_HANDSHAKE_REJECTIONS) {
                            postMain { callback?.onFatal("The streaming server answered HTTP $code. Check the address in Settings.") }
                            return@launch
                        }
                    }
                    conn.fatalMessage?.let {
                        postMain { callback?.onFatal(it) }
                        return@launch
                    }
                }
                ws.cancel()
                if (socket === ws) socket = null
                if (!isActive) break

                val wait = backoffMs(attempt++)
                postMain { callback?.onStatus("Reconnecting to the speech server...") }
                delay(wait)
            }
        }
    }

    /** Sends queued audio until the socket closes. A frame that could not be sent is put back first. */
    private suspend fun pump(ws: WebSocket, conn: Connection) {
        while (currentCoroutineContext().isActive && !conn.closed.isCompleted) {
            val frame = queue.pollFirst()
            if (frame == null) {
                withTimeoutOrNull(100) { signal.receive() }
                continue
            }
            if (ws.queueSize() > MAX_SOCKET_BACKLOG_BYTES || !ws.send(frame.toByteString())) {
                queue.addFirst(frame)
                return
            }
        }
    }

    private inner class Connection : WebSocketListener() {
        val ready = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()

        @Volatile var httpCode = 0

        @Volatile var fatalMessage: String? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(configJson())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handle(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            closed.complete(Unit)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closed.complete(Unit)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            httpCode = response?.code ?: 0
            Log.w(TAG, "Socket failure: ${t.javaClass.simpleName} ${t.message} http=$httpCode")
            closed.complete(Unit)
            ready.completeExceptionally(t)
        }

        private fun handle(text: String) {
            val json = try { JSONObject(text) } catch (e: Exception) { return }
            when (json.optString("type")) {
                "ready" -> ready.complete(Unit)
                "partial" -> {
                    val t = json.optString("text").trim()
                    if (t.isNotEmpty()) postMain { callback?.onPartial(t) }
                }
                "final" -> {
                    val shown = deduper.accept(json.optString("text"), System.currentTimeMillis())
                    if (shown != null) {
                        val lang = json.optString("language").ifEmpty { null }
                        postMain { callback?.onFinal(shown, lang) }
                    }
                }
                "status" -> postMain { callback?.onStatus(json.optString("message").ifEmpty { null }) }
                "error" -> {
                    val message = json.optString("message", "Speech server error")
                    if (json.optBoolean("fatal", false)) {
                        fatalMessage = message
                        closed.complete(Unit)
                        ready.completeExceptionally(IllegalStateException(message))
                    } else {
                        postMain { callback?.onStatus(message) }
                    }
                }
            }
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun configJson(): String {
        val primary: String
        val alternates = JSONArray()
        when (settings.language) {
            PreferredLanguage.TELUGU -> primary = "te-IN"
            PreferredLanguage.TELUGU_ENGLISH_MIXED -> { primary = "te-IN"; alternates.put("en-IN") }
            PreferredLanguage.ENGLISH -> { primary = "en-IN"; alternates.put("te-IN") }
        }
        return JSONObject()
            .put("type", "config")
            .put("language", primary)
            .put("alternateLanguages", alternates)
            .put("encoding", "LINEAR16")
            .put("sampleRate", AudioCaptureManager.SAMPLE_RATE)
            .put("interimResults", true)
            .toString()
    }

    private fun toBytes(frame: ShortArray): ByteArray {
        val bb = ByteBuffer.allocate(frame.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in frame) bb.putShort(s)
        return bb.array()
    }

    private fun level(frame: ShortArray): Float {
        if (frame.isEmpty()) return 0f
        var sum = 0.0
        for (s in frame) sum += s.toDouble() * s
        val rms = sqrt(sum / frame.size)
        return (rms / 32768.0 * 25.0).coerceIn(0.0, 1.0).toFloat()
    }

    private fun postMain(block: () -> Unit) {
        main.post(block)
    }

    private companion object {
        const val TAG = "StreamingEngine"
        const val MAX_BUFFER_MS = 8_000
        const val MAX_BUFFER_FRAMES = MAX_BUFFER_MS / AudioCaptureManager.FRAME_MS
        const val LEVEL_EVERY_N_FRAMES = 5
        const val READY_TIMEOUT_MS = 10_000L
        const val MAX_SOCKET_BACKLOG_BYTES = 256_000L
        const val MAX_HANDSHAKE_REJECTIONS = 6

        fun backoffMs(attempt: Int): Long = minOf(8_000L, 500L shl minOf(attempt, 4))

        /**
         * Accepts wss:// and ws://. Android blocks cleartext ws:// unless the build's network security config
         * allows it, and only the debug build's config does (for LAN testing); release builds stay TLS-only.
         */
        fun isAcceptableUrl(url: String): Boolean =
            url.startsWith("wss://", ignoreCase = true) || url.startsWith("ws://", ignoreCase = true)
    }
}
