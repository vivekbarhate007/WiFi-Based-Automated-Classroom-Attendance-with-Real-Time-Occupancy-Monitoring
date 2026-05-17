package com.attendance.router.db

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface SessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: Session)

    @Update
    suspend fun update(session: Session)

    @Query("SELECT * FROM session WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getById(sessionId: String): Session?

    @Query("SELECT * FROM session ORDER BY startTime DESC")
    suspend fun getAll(): List<Session>

    @Query("SELECT * FROM session ORDER BY startTime DESC")
    fun getAllLive(): LiveData<List<Session>>

    @Query("SELECT * FROM session WHERE endTime IS NULL ORDER BY startTime DESC LIMIT 1")
    suspend fun getActiveSession(): Session?

    @Query("UPDATE session SET endTime = :endTime WHERE sessionId = :sessionId")
    suspend fun closeSession(sessionId: String, endTime: Long)

    @Query("DELETE FROM session WHERE sessionId = :sessionId")
    suspend fun delete(sessionId: String)
}
