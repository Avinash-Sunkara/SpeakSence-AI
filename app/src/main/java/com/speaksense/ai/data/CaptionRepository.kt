package com.speaksense.ai.data

import com.speaksense.ai.data.db.LineEntity
import com.speaksense.ai.data.db.SessionEntity
import com.speaksense.ai.data.db.TranscriptDao
import com.speaksense.ai.model.CaptionLine
import com.speaksense.ai.model.Script

data class TranscriptLine(val timestamp: Long, val text: String, val script: Script)

data class TranscriptItem(
    val id: Long,
    val startedAt: Long,
    val endedAt: Long?,
    val direction: String,
    val lines: List<TranscriptLine>,
) {
    val fullText: String get() = lines.joinToString("\n") { it.text }
}

/** Stores recognised text only (encrypted). Nothing is written unless the user enabled saving. */
class CaptionRepository(
    private val dao: TranscriptDao,
    private val crypto: CryptoManager,
) {
    suspend fun startSession(direction: String, engine: String): Long =
        dao.insertSession(
            SessionEntity(startedAt = System.currentTimeMillis(), endedAt = null, direction = direction, engine = engine),
        )

    suspend fun appendLine(sessionId: Long, line: CaptionLine) {
        val encrypted = crypto.encrypt(line.text) ?: return // never fall back to plain text
        dao.insertLine(
            LineEntity(sessionId = sessionId, timestamp = line.timestamp, textEnc = encrypted, script = line.script.name),
        )
    }

    suspend fun endSession(sessionId: Long) {
        dao.endSession(sessionId, System.currentTimeMillis())
        dao.deleteEmptyEndedSessions()
    }

    suspend fun loadHistory(): List<TranscriptItem> {
        val linesBySession = dao.getAllLines().groupBy { it.sessionId }
        return dao.getSessions().mapNotNull { session ->
            val lines = linesBySession[session.id].orEmpty().mapNotNull { entity ->
                val text = crypto.decrypt(entity.textEnc) ?: return@mapNotNull null
                val script = try {
                    Script.valueOf(entity.script)
                } catch (e: IllegalArgumentException) {
                    Script.NONE
                }
                TranscriptLine(entity.timestamp, text, script)
            }
            if (lines.isEmpty()) null
            else TranscriptItem(session.id, session.startedAt, session.endedAt, session.direction, lines)
        }
    }

    suspend fun delete(sessionId: Long) = dao.deleteSession(sessionId)

    suspend fun clearAll() = dao.deleteAllSessions()
}
