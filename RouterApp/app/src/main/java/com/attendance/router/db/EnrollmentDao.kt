package com.attendance.router.db

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface EnrollmentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(enrollment: Enrollment)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(enrollments: List<Enrollment>)

    @Query("SELECT * FROM enrollment WHERE token = :token LIMIT 1")
    suspend fun getByToken(token: String): Enrollment?

    @Query("SELECT * FROM enrollment WHERE studentId = :studentId LIMIT 1")
    suspend fun getByStudentId(studentId: String): Enrollment?

    @Query("SELECT * FROM enrollment ORDER BY name ASC")
    suspend fun getAll(): List<Enrollment>

    @Query("SELECT * FROM enrollment ORDER BY name ASC")
    fun getAllLive(): LiveData<List<Enrollment>>

    @Query("SELECT COUNT(*) FROM enrollment")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM enrollment")
    fun getCountLive(): LiveData<Int>

    @Query("DELETE FROM enrollment WHERE token = :token")
    suspend fun deleteByToken(token: String)

    @Query("DELETE FROM enrollment")
    suspend fun deleteAll()
}
