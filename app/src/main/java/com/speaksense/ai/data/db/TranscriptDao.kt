package com.speaksense.ai.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface TranscriptDao {
    @Insert
    suspend fun insertSession(session: SessionEntity): Long

    @Insert
    suspend fun insertLine(line: LineEntity): Long

    @Query("UPDATE transcript_sessions SET endedAt = :endedAt WHERE id = :id")
    suspend fun endSession(id: Long, endedAt: Long)

    @Query("DELETE FROM transcript_sessions WHERE endedAt IS NOT NULL AND id NOT IN (SELECT DISTINCT sessionId FROM transcript_lines)")
    suspend fun deleteEmptyEndedSessions()

    @Query("SELECT * FROM transcript_sessions ORDER BY startedAt DESC")
    suspend fun getSessions(): List<SessionEntity>

    @Query("SELECT * FROM transcript_lines ORDER BY timestamp ASC")
    suspend fun getAllLines(): List<LineEntity>

    @Query("DELETE FROM transcript_sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)

    @Query("DELETE FROM transcript_sessions")
    suspend fun deleteAllSessions()
}
