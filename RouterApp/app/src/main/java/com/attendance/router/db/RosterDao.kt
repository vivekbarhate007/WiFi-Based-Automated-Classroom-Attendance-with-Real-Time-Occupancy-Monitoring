package com.attendance.router.db

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RosterDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: RosterEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<RosterEntry>)

    @Query("SELECT * FROM roster WHERE rollNo = :rollNo LIMIT 1")
    suspend fun getByRollNo(rollNo: String): RosterEntry?

    @Query("SELECT * FROM roster ORDER BY rollNo ASC")
    suspend fun getAll(): List<RosterEntry>

    @Query("SELECT * FROM roster ORDER BY rollNo ASC")
    fun getAllLive(): LiveData<List<RosterEntry>>

    @Query("SELECT COUNT(*) FROM roster")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM roster")
    fun getCountLive(): LiveData<Int>

    @Query("DELETE FROM roster")
    suspend fun clear()
}
