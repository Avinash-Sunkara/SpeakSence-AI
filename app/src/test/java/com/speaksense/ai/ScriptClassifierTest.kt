package com.speaksense.ai

import com.speaksense.ai.model.Script
import com.speaksense.ai.speech.ScriptClassifier
import org.junit.Assert.assertEquals
import org.junit.Test

class ScriptClassifierTest {
    @Test
    fun teluguOnly() {
        assertEquals(Script.TELUGU, ScriptClassifier.classify("\u0C2E\u0C40\u0C30\u0C41 \u0C30\u0C47\u0C2A\u0C41 \u0C35\u0C38\u0C4D\u0C24\u0C3E\u0C30\u0C3E?"))
    }

    @Test
    fun englishOnly() {
        assertEquals(Script.LATIN, ScriptClassifier.classify("I will call you tomorrow."))
    }

    @Test
    fun mixedTeluguAndEnglish() {
        assertEquals(Script.MIXED, ScriptClassifier.classify("\u0C30\u0C47\u0C2A\u0C41 morning 10 AM \u0C15\u0C3F college"))
    }

    @Test
    fun digitsAndPunctuationOnly() {
        assertEquals(Script.NONE, ScriptClassifier.classify("10 : 30 ?!"))
    }
}
