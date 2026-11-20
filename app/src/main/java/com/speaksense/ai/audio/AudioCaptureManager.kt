package com.speaksense.ai.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.speaksense.ai.data.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Microphone access for (a) the in-app microphone test and (b) the optional backend engine.
 * The Android SpeechRecognizer engine records through the recognition service instead.
 *
 * Audio is only ever held in memory. Nothing is written to disk.
 */
class AudioCaptureManager(private val context: Context) {

    data class MicTestResult(
        val success: Boolean,
        val peakPercent: Int,
        val averagePercent: Int,
        val message: String,
    )

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Suppress("DEPRECATION")
    fun isSpeakerphoneOn(): Boolean = try {
        audioManager.isSpeakerphoneOn
    } catch (e: Exception) {
        false
    }

    /** Reports what the capture layer is really doing, so the UI never claims audio that is not there. */
    interface CaptureListener {
        /** A microphone source was opened (name is for diagnostics, e.g. "VOICE_RECOGNITION"). */
        fun onSourceOpened(name: String)

        /** true = Android is delivering digital silence on every source we tried (call audio blocked). */
        fun onBlocked(blocked: Boolean)
    }

    private class Source(val name: String, val id: Int)

    /**
     * Sources worth trying during a call, in order. VOICE_COMMUNICATION is deliberately NOT here: it is
     * privacy-sensitive by default (silenced during calls) and enables echo cancellation, which would
     * remove the caller's voice coming out of the speaker.
     */
    private val sources = listOf(
        Source("VOICE_RECOGNITION", MediaRecorder.AudioSource.VOICE_RECOGNITION),
        Source("MIC", MediaRecorder.AudioSource.MIC),
        Source("UNPROCESSED", MediaRecorder.AudioSource.UNPROCESSED),
    )

    @SuppressLint("MissingPermission")
    private fun createRecord(sourceId: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION): AudioRecord? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) return null
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return null
        // At least 1 s of ring buffer so a slow consumer never makes the driver overrun and drop words.
        val bufferBytes = maxOf(minBuffer * 2, SAMPLE_RATE * 2)
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(sourceId)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferBytes)
                // Not privacy-sensitive: lets Android keep delivering this capture while a call owns the
                // telephony audio path on devices that allow it. Some OEMs still silence it; we detect that.
                .setPrivacySensitive(false)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord construction failed for source $sourceId", e)
            return null
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }
        preferBuiltInMic(record)
        return record
    }

    /** With a Bluetooth headset connected Android may route capture there; the speakerphone needs the phone mic. */
    private fun preferBuiltInMic(record: AudioRecord) {
        try {
            val mic = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            if (mic != null) record.setPreferredDevice(mic)
        } catch (e: Exception) {
            Log.w(TAG, "setPreferredDevice failed", e)
        }
    }

    private class Effects(private val ns: NoiseSuppressor?, private val aec: AcousticEchoCanceler?) {
        fun release() {
            try { ns?.release() } catch (e: Exception) { /* ignore */ }
            try { aec?.release() } catch (e: Exception) { /* ignore */ }
        }
    }

    private fun attachEffects(record: AudioRecord, settings: AppSettings): Effects {
        var ns: NoiseSuppressor? = null
        var aec: AcousticEchoCanceler? = null
        try {
            if (settings.noiseSuppression && NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(record.audioSessionId)
                ns?.setEnabled(true)
            }
            if (settings.echoCancellation && AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(record.audioSessionId)
                aec?.setEnabled(true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio effects unavailable", e)
        }
        return Effects(ns, aec)
    }

    /** Records for a few seconds and reports how loud the microphone signal was. */
    suspend fun runMicTest(settings: AppSettings, durationMs: Long = 4000L): MicTestResult =
        withContext(Dispatchers.IO) {
            val record = createRecord() ?: return@withContext MicTestResult(
                false, 0, 0,
                "Could not open the microphone. Check the microphone permission and close other apps that use it.",
            )
            val effects = attachEffects(record, settings)
            try {
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    return@withContext MicTestResult(
                        false, 0, 0,
                        "The microphone is busy or blocked by Android (this can happen during a call).",
                    )
                }
                val buffer = ShortArray(SAMPLE_RATE / 10)
                val end = SystemClock.elapsedRealtime() + durationMs
                var peak = 0
                var sumSquares = 0.0
                var count = 0L
                while (SystemClock.elapsedRealtime() < end && isActive) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n < 0) break
                    for (i in 0 until n) {
                        val v = abs(buffer[i].toInt())
                        if (v > peak) peak = v
                        sumSquares += v.toDouble() * v
                    }
                    count += n
                }
                val peakPercent = (peak * 100 / 32767)
                val avgPercent = if (count > 0) (sqrt(sumSquares / count) * 100 / 32767).toInt() else 0
                val message = when {
                    peakPercent < 1 ->
                        "No sound was detected. The microphone may be muted or blocked by Android. Speak close to the phone and try again."
                    peakPercent < 5 ->
                        "The microphone works but the signal is very quiet. Speak louder or move closer."
                    else ->
                        "The microphone works. During a real call Android may still silence it, so the dashboard will tell you which mode is working."
                }
                MicTestResult(peakPercent >= 1, peakPercent, avgPercent, message)
            } catch (e: Exception) {
                MicTestResult(false, 0, 0, "Microphone test failed: ${e.message}")
            } finally {
                try { record.stop() } catch (e: Exception) { /* ignore */ }
                record.release()
                effects.release()
            }
        }

    /**
     * Cold flow of 40 ms PCM16 mono frames at 16 kHz (640 samples). Cancel the collector to release the microphone.
     *
     * If a source delivers only digital silence for [SILENT_SWITCH_MS], the next source is tried. If every source
     * is silent, [CaptureListener.onBlocked] reports it (Android is blocking the microphone during the call) and
     * capture keeps running on the first source, so audio resumes by itself if the block lifts.
     */
    fun frames(settings: AppSettings, listener: CaptureListener? = null): Flow<ShortArray> = callbackFlow {
        val current = AtomicReference<AudioRecord?>(null)
        val job = launch(Dispatchers.IO) {
            try {
                var index = 0
                var exhausted = false
                var blocked = false
                var openedAny = false
                while (isActive) {
                    val source = sources[index]
                    val record = createRecord(source.id)
                    if (record == null) {
                        index++
                        if (index >= sources.size) {
                            if (!openedAny) throw IllegalStateException("The microphone could not be opened.")
                            index = 0; exhausted = true
                        }
                        continue
                    }
                    val effects = attachEffects(record, settings)
                    current.set(record)
                    var switchSource = false
                    try {
                        try {
                            record.startRecording()
                        } catch (e: IllegalStateException) {
                            Log.w(TAG, "startRecording failed on ${source.name}", e)
                        }
                        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                            index++
                            if (index >= sources.size) {
                                if (!openedAny) throw IllegalStateException("The microphone is busy or blocked by Android.")
                                index = 0; exhausted = true
                            }
                            continue
                        }
                        openedAny = true
                        listener?.onSourceOpened(source.name)
                        val buffer = ShortArray(FRAME_SAMPLES)
                        var silentFrames = 0
                        while (isActive && !switchSource) {
                            val n = record.read(buffer, 0, buffer.size)
                            if (n < 0) throw IllegalStateException("Audio read error $n")
                            if (n == 0) continue
                            var peak = 0
                            for (i in 0 until n) {
                                val v = abs(buffer[i].toInt())
                                if (v > peak) peak = v
                            }
                            if (peak <= DIGITAL_SILENCE_PEAK) {
                                silentFrames++
                                if (silentFrames * FRAME_MS >= SILENT_SWITCH_MS) {
                                    if (!exhausted && index + 1 < sources.size) {
                                        index++
                                        switchSource = true
                                    } else if (!blocked) {
                                        blocked = true
                                        exhausted = true
                                        listener?.onBlocked(true)
                                    }
                                }
                            } else {
                                silentFrames = 0
                                if (blocked) {
                                    blocked = false
                                    listener?.onBlocked(false)
                                }
                            }
                            // Always forward every frame (including silence): streaming recognisers need
                            // continuous audio for their own end-pointing.
                            trySend(buffer.copyOf(n))
                        }
                    } finally {
                        current.set(null)
                        try { record.stop() } catch (e: Exception) { /* ignore */ }
                        record.release()
                        effects.release()
                    }
                    if (switchSource) continue
                    break
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                close(e)
            }
        }
        awaitClose {
            try { current.get()?.stop() } catch (e: Exception) { /* unblocks a pending read() */ }
            job.cancel()
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 40
        const val FRAME_SAMPLES = SAMPLE_RATE / 1000 * FRAME_MS
        private const val DIGITAL_SILENCE_PEAK = 1
        private const val SILENT_SWITCH_MS = 3_000
        private const val TAG = "AudioCapture"
    }
}
