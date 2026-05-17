package com.attendance.router.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        Enrollment::class,
        PresenceLog::class,
        Session::class,
        RosterEntry::class,
        CheckIn::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun enrollmentDao(): EnrollmentDao
    abstract fun presenceDao(): PresenceDao
    abstract fun sessionDao(): SessionDao
    abstract fun rosterDao(): RosterDao
    abstract fun checkInDao(): CheckInDao

    companion object {
        private const val DATABASE_NAME = "attendance_db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
                .fallbackToDestructiveMigration()
                .build()
        }

        /**
         * Clears the singleton; used only in tests.
         */
        fun clearInstance() {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}
