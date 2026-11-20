package com.speaksense.ai.speech

/**
 * Pure-Kotlin guard against repeated final captions (unit-tested, no Android classes).
 *
 * Repeats appear after a streaming reconnect, when the last few seconds of buffered audio are sent again.
 * A final is dropped only when it arrives within [windowMs] of the previous accepted final AND is identical
 * to, or entirely contained at the end of, a recent final. A speaker who really repeats a phrase later
 * (outside the window) is never suppressed: dropping real words is worse than showing a repeat.
 */
class FinalDeduper(
    private val windowMs: Long = 4_000L,
    private val memory: Int = 3,
    private val minContainedChars: Int = 6,
) {
    private val recent = ArrayDeque<String>()
    private var lastAcceptedAt = Long.MIN_VALUE

    /** Returns the cleaned text to display, or null when it is a repeat or has no content. */
    fun accept(text: String, nowMs: Long): String? {
        val clean = text.trim().replace(WHITESPACE, " ")
        val key = normalize(clean)
        if (key.isEmpty()) return null
        val inWindow = lastAcceptedAt != Long.MIN_VALUE && nowMs - lastAcceptedAt <= windowMs
        if (inWindow) {
            for (old in recent) {
                if (old == key) return null
                if (key.length >= minContainedChars && old.endsWith(key)) return null
            }
        }
        recent.addLast(key)
        while (recent.size > memory) recent.removeFirst()
        lastAcceptedAt = nowMs
        return clean
    }

    fun reset() {
        recent.clear()
        lastAcceptedAt = Long.MIN_VALUE
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")

        /**
         * Lower-cases and strips whitespace and punctuation only. Telugu vowel signs are combining marks
         * (not "letters"), so they must be kept; stripping them would merge different words.
         */
        fun normalize(text: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                i += Character.charCount(cp)
                if (Character.isWhitespace(cp) || isPunctuation(cp)) continue
                sb.appendCodePoint(Character.toLowerCase(cp))
            }
            return sb.toString()
        }

        private fun isPunctuation(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
            Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION -> true
            else -> false
        }
    }
}
