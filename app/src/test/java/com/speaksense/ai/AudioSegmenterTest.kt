package com.speaksense.ai

import com.speaksense.ai.speech.SegmentCollector
import com.speaksense.ai.speech.WavEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSegmenterTest {
    private fun frame(amplitude: Int) = ShortArray(1600) { if (it % 2 == 0) amplitude.toShort() else (-amplitude).toShort() }

    @Test
    fun wavHeaderIsCorrect() {
        val pcm = ByteArray(3200)
        val wav = WavEncoder.wrap(pcm, 16000)
        assertEquals(44 + 3200, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
    }

    @Test
    fun silenceNeverProducesSegments() {
        val c = SegmentCollector()
        repeat(50) { assertNull(c.push(frame(10))) }
    }

    @Test
    fun phraseEndsAfterSilence() {
        val c = SegmentCollector()
        repeat(5) { c.push(frame(10)) }
        repeat(6) { assertNull(c.push(frame(8000))) }
        var segment: ByteArray? = null
        repeat(8) { if (segment == null) segment = c.push(frame(10)) }
        assertNotNull(segment)
        assertTrue(segment!!.size > 3200)
    }

    @Test
    fun veryShortBlipIsDropped() {
        val c = SegmentCollector()
        repeat(5) { c.push(frame(10)) }
        c.push(frame(8000))
        var segment: ByteArray? = null
        repeat(10) { if (segment == null) segment = c.push(frame(10)) }
        assertNull(segment)
    }
}
