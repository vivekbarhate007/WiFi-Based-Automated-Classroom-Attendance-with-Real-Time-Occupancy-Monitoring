package com.attendance.router.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CheckInDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(checkIn: CheckIn)

    @Query("SELECT * FROM check_in WHERE sessionId = :sessionId AND rollNo = :rollNo LIMIT 1")
    suspend fun getBySessionAndRollNo(sessionId: String, rollNo: String): CheckIn?

    @Query("SELECT * FROM check_in WHERE sessionId = :sessionId AND token = :token LIMIT 1")
    suspend fun getBySessionAndToken(sessionId: String, token: String): CheckIn?

    @Query("SELECT * FROM check_in WHERE sessionId = :sessionId ORDER BY checkedInAt ASC")
    suspend fun getBySession(sessionId: String): List<CheckIn>

    /**
     * Returns the NAMES (from the roster) of every student checked in
     * for the given session, in check-in order. Useful for the dashboard
     * live list.
     */
    @Query("""
        SELECT r.name FROM check_in c
        INNER JOIN roster r ON c.rollNo = r.rollNo
        WHERE c.sessionId = :sessionId
        ORDER BY c.checkedInAt ASC
    """)
    suspend fun getNamesBySession(sessionId: String): List<String>

    @Query("SELECT COUNT(*) FROM check_in WHERE sessionId = :sessionId")
    suspend fun countBySession(sessionId: String): Int

    @Query("DELETE FROM check_in WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: String)
}
