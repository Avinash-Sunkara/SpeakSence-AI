package com.speaksense.ai.speech

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

/** Pure-JVM helpers for the backend engine (unit-tested). */
object WavEncoder {
    /** Wraps little-endian 16-bit mono PCM in a standard 44-byte WAV header. */
    fun wrap(pcm: ByteArray, sampleRate: Int): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(1) // mono
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2) // byte rate
        header.putShort(2) // block align
        header.putShort(16) // bits per sample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size)
        return header.array() + pcm
    }
}

/**
 * Energy-based voice-activity segmenter. Feed 100 ms frames; it returns a PCM segment when a phrase
 * ends (700 ms of silence) or reaches 8 s. Noise floor adapts automatically.
 */
class SegmentCollector(frameMs: Int = 100) {
    private val silenceFrames = (700 / frameMs).coerceAtLeast(1)
    private val maxFrames = (8_000 / frameMs).coerceAtLeast(1)
    // 160 ms: short words such as "అవును" / "ok" must not be thrown away.
    private val minSpeechFrames = (160 / frameMs).coerceAtLeast(1)

    private val buffer = ByteArrayOutputStream()
    private var previous: ShortArray? = null
    private var noiseFloor = 200.0
    private var inSpeech = false
    private var speechFrames = 0
    private var silentFrames = 0
    private var totalFrames = 0

    /** 0..1 level of the most recent frame. */
    var lastLevel = 0f
        private set

    fun push(frame: ShortArray): ByteArray? {
        val rms = rms(frame)
        lastLevel = (rms / 32768.0 * 25.0).coerceIn(0.0, 1.0).toFloat()
        val threshold = max(350.0, noiseFloor * 3.0)
        val isSpeech = rms > threshold
        if (!isSpeech) noiseFloor = noiseFloor * 0.95 + rms * 0.05

        if (isSpeech) {
            if (!inSpeech) {
                inSpeech = true
                previous?.let { write(it); totalFrames++ } // small pre-roll so the first syllable is kept
            }
            speechFrames++
            silentFrames = 0
        } else if (inSpeech) {
            silentFrames++
        }
        if (inSpeech) {
            write(frame)
            totalFrames++
        }
        previous = frame

        val endBySilence = inSpeech && silentFrames >= silenceFrames
        val endByLength = inSpeech && totalFrames >= maxFrames
        if (endBySilence || endByLength) {
            val enough = speechFrames >= minSpeechFrames
            val bytes = buffer.toByteArray()
            reset()
            return if (enough) bytes else null
        }
        return null
    }

    private fun reset() {
        buffer.reset()
        inSpeech = false
        speechFrames = 0
        silentFrames = 0
        totalFrames = 0
    }

    private fun write(frame: ShortArray) {
        val bb = ByteBuffer.allocate(frame.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in frame) bb.putShort(s)
        buffer.write(bb.array())
    }

    private fun rms(frame: ShortArray): Double {
        if (frame.isEmpty()) return 0.0
        var sum = 0.0
        for (s in frame) sum += s.toDouble() * s
        return sqrt(sum / frame.size)
    }
}
