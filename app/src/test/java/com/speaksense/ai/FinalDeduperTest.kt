package com.speaksense.ai

import com.speaksense.ai.speech.FinalDeduper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FinalDeduperTest {
    private val telugu = "\u0C28\u0C47\u0C28\u0C41 College \u0C15\u0C3F 10 PM \u0C15\u0C3F \u0C35\u0C38\u0C4D\u0C24\u0C3E\u0C28\u0C41."

    @Test
    fun firstFinalIsAcceptedAndCleaned() {
        assertEquals("hello world", FinalDeduper().accept("  hello   world ", 0))
    }

    @Test
    fun identicalFinalWithinWindowIsDropped() {
        val d = FinalDeduper()
        assertNotNull(d.accept(telugu, 0))
        assertNull(d.accept(telugu.removeSuffix("."), 1_500)) // punctuation differs only
    }

    @Test
    fun sameSentenceLaterIsKeptBecauseTheSpeakerMayRepeatThemselves() {
        val d = FinalDeduper()
        assertNotNull(d.accept(telugu, 0))
        assertNotNull(d.accept(telugu, 20_000))
    }

    @Test
    fun tailOfPreviousFinalIsDroppedWhenResentAfterReconnect() {
        val d = FinalDeduper()
        assertNotNull(d.accept("see you at the college tomorrow", 0))
        assertNull(d.accept("at the college tomorrow", 2_000))
    }

    @Test
    fun shortDifferentWordsAreNeverDropped() {
        val d = FinalDeduper()
        assertNotNull(d.accept("\u0C05\u0C35\u0C41\u0C28\u0C41", 0))
        assertNotNull(d.accept("ok", 300))
    }

    @Test
    fun teluguVowelSignsAreKeptWhenComparing() {
        // Different words that differ only by a vowel sign must not be treated as equal.
        val d = FinalDeduper()
        assertNotNull(d.accept("\u0C15\u0C3F", 0))
        assertNotNull(d.accept("\u0C15\u0C41", 100))
    }

    @Test
    fun punctuationOnlyIsIgnored() {
        assertNull(FinalDeduper().accept(" . ", 0))
    }
}
