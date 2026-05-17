package com.attendance.router.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface PresenceDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPresence(log: PresenceLog): Long

    /**
     * Upsert a heartbeat: if this token+session already exists, update lastSeen.
     * If it's new, insert with firstSeen = lastSeen = now.
     */
    @Transaction
    suspend fun upsertPresence(token: String, sessionId: String, nowMs: Long) {
        val existing = getByTokenAndSession(token, sessionId)
        if (existing != null) {
            updateLastSeen(existing.id, nowMs)
        } else {
            insertPresence(PresenceLog(token = token, sessionId = sessionId, firstSeen = nowMs, lastSeen = nowMs))
        }
    }

    @Query("SELECT * FROM presence_log WHERE token = :token AND sessionId = :sessionId LIMIT 1")
    suspend fun getByTokenAndSession(token: String, sessionId: String): PresenceLog?

    @Query("UPDATE presence_log SET lastSeen = :lastSeen WHERE id = :id")
    suspend fun updateLastSeen(id: Long, lastSeen: Long)

    /**
     * Returns tokens whose lastSeen is more recent than [since] ms epoch.
     * Used to compute current occupancy (tokens active in last N seconds).
     */
    @Query("SELECT DISTINCT token FROM presence_log WHERE lastSeen >= :since")
    suspend fun getActiveTokens(since: Long): List<String>

    /**
     * Returns all presence records for a specific session.
     */
    @Query("SELECT * FROM presence_log WHERE sessionId = :sessionId")
    suspend fun getSessionPresence(sessionId: String): List<PresenceLog>

    /**
     * Returns distinct tokens seen in a session (at least one heartbeat).
     */
    @Query("SELECT DISTINCT token FROM presence_log WHERE sessionId = :sessionId")
    suspend fun getSessionTokens(sessionId: String): List<String>

    @Query("DELETE FROM presence_log WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("DELETE FROM presence_log")
    suspend fun deleteAll()
}
