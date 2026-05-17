package com.attendance.router.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Enrollment maps an anonymous token to a real student identity.
 * This table is the ONLY place where token meets identity.
 */
@Entity(tableName = "enrollment")
data class Enrollment(
    @PrimaryKey
    val token: String,
    val studentId: String,
    val name: String,
    val enrolledAt: Long = System.currentTimeMillis()
)

/**
 * PresenceLog records heartbeat activity per token per session.
 * No raw IP or MAC addresses are stored here — only tokens.
 */
@Entity(tableName = "presence_log")
data class PresenceLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val token: String,
    val sessionId: String,
    val firstSeen: Long,
    val lastSeen: Long
)

/**
 * Session tracks each class meeting with its timing and course info.
 */
@Entity(tableName = "session")
data class Session(
    @PrimaryKey
    val sessionId: String,
    val startTime: Long,
    val endTime: Long? = null,
    val courseName: String
)

/**
 * AttendanceResult is a transient (non-Room) data class used when
 * generating the final report by joining token ↔ identity.
 */
data class AttendanceResult(
    val studentId: String,
    val name: String,
    val status: String,           // "PRESENT", "PARTIAL", "ABSENT"
    val durationMinutes: Int,     // minutes the student was seen
    val percentage: Float,        // percentage of class duration attended
    /** Median ping RTT in ms from the last scan — proxy for signal quality.
     *  null if the student left before any RTT sample was collected. */
    val medianRttMs: Long? = null
)

/**
 * Roster: the class roll. Teacher imports this once via CSV before running
 * a session. The check-in captive-portal validates roll numbers against
 * this table before marking a student present.
 */
@Entity(tableName = "roster")
data class RosterEntry(
    @PrimaryKey
    val rollNo: String,
    val name: String
)

/**
 * CheckIn: one row per student-per-session. Created when a student
 * submits their roll number on the check-in page. The token captures
 * which device they checked in from so the presence_log (indexed by
 * token) can be cross-referenced to measure how long they stayed.
 */
@Entity(tableName = "check_in", primaryKeys = ["sessionId", "rollNo"])
data class CheckIn(
    val sessionId: String,
    val rollNo: String,
    val token: String,
    val checkedInAt: Long
)
