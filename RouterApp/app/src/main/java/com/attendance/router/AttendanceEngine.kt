package com.attendance.router

import android.util.Log
import com.attendance.router.db.AppDatabase
import com.attendance.router.db.AttendanceResult
import com.attendance.router.db.PresenceDao

/**
 * Core attendance business logic.
 *
 * ## Report model (check-in driven)
 *
 * Every student in the **roster** gets exactly one row in the report.
 * Status for each student:
 *
 *   - PRESENT — student submitted their roll number on the check-in page
 *     AND their phone stayed on the classroom Wi-Fi for ≥75% of the class.
 *   - PARTIAL — checked in, but only stayed 50%–75% of the class.
 *   - ABSENT  — no check-in (even if their phone happened to be on the Wi-Fi).
 *
 * Devices that connect to the hotspot but never check in do **not** appear
 * in the report — the roster is the source of truth.
 *
 * ## Live occupancy
 *
 * The dashboard shows "students present" driven by the number of successful
 * check-ins in the current session. Legacy heartbeat-based occupancy is
 * retained for backward compatibility but no longer drives the UI.
 */
object AttendanceEngine {

    private const val TAG = "AttendanceEngine"

    // Presence window boundaries (milliseconds) — used by legacy heartbeat
    // helpers only. The modern flow uses the check-in table.
    private const val ACTIVE_THRESHOLD_MS = 90_000L        // 90 seconds
    private const val DISCONNECT_THRESHOLD_MS = 300_000L   // 5 minutes

    // Attendance percentage thresholds
    private const val PRESENT_THRESHOLD = 0.75f
    private const val PARTIAL_THRESHOLD = 0.50f

    /**
     * Returns the count of tokens that have sent a heartbeat within the last 90 seconds.
     * Kept for any legacy callers; the dashboard now uses check-in counts.
     */
    suspend fun computeOccupancy(dao: PresenceDao): Int {
        val nowMs = System.currentTimeMillis()
        val activeSince = nowMs - ACTIVE_THRESHOLD_MS
        return dao.getActiveTokens(since = activeSince).size
    }

    /**
     * Returns the count of tokens seen within the last 5 minutes. Legacy helper.
     */
    suspend fun computeEffectiveOccupancy(dao: PresenceDao): Int {
        val nowMs = System.currentTimeMillis()
        val effectiveSince = nowMs - DISCONNECT_THRESHOLD_MS
        return dao.getActiveTokens(since = effectiveSince).size
    }

    /**
     * Determines the real-time status of a single token.
     *
     * @param lastSeenMs  epoch ms of last received heartbeat
     * @return "PRESENT", "TEMPORARILY_DISCONNECTED", or "ABSENT"
     */
    fun getPresenceStatus(lastSeenMs: Long): String {
        val elapsedMs = System.currentTimeMillis() - lastSeenMs
        return when {
            elapsedMs <= ACTIVE_THRESHOLD_MS    -> "PRESENT"
            elapsedMs <= DISCONNECT_THRESHOLD_MS -> "TEMPORARILY_DISCONNECTED"
            else                                 -> "ABSENT"
        }
    }

    /**
     * Generates the attendance report for a session. Iterates the **roster**
     * and resolves each student to a status using their check-in record
     * cross-referenced with the presence_log.
     *
     * @param sessionId            the session to report on
     * @param classDurationMinutes total scheduled class length in minutes
     * @param db                   Room database instance
     * @return one [AttendanceResult] per roster entry
     */
    suspend fun computeAttendance(
        sessionId: String,
        classDurationMinutes: Int,
        db: AppDatabase
    ): List<AttendanceResult> {
        val rosterDao   = db.rosterDao()
        val checkInDao  = db.checkInDao()
        val presenceDao = db.presenceDao()

        val roster       = rosterDao.getAll()
        val checkIns     = checkInDao.getBySession(sessionId)
        val presenceLogs = presenceDao.getSessionPresence(sessionId)

        // Fast lookups
        val checkInByRoll  = checkIns.associateBy { it.rollNo }
        val presenceByToken = presenceLogs.associateBy { it.token }

        // If the class duration wasn't provided, fall back to a sensible default
        // so we don't classify everyone as ABSENT with divide-by-zero.
        val effectiveClassMinutes = if (classDurationMinutes > 0) {
            classDurationMinutes
        } else 60
        val classDurationMs = effectiveClassMinutes * 60_000L

        val results = mutableListOf<AttendanceResult>()

        for (student in roster) {
            val checkIn = checkInByRoll[student.rollNo]
            if (checkIn == null) {
                // No check-in → ABSENT, regardless of whether a device was seen.
                results += AttendanceResult(
                    studentId       = student.rollNo,
                    name            = student.name,
                    status          = "ABSENT",
                    durationMinutes = 0,
                    percentage      = 0f
                )
                continue
            }

            // Stay time = how long the device they checked in from was seen.
            // firstSeen is clamped to the check-in time so a phone that joined
            // the hotspot long before the class starts doesn't get extra credit.
            val presence = presenceByToken[checkIn.token]
            val effectiveFirst = maxOf(
                checkIn.checkedInAt,
                presence?.firstSeen ?: checkIn.checkedInAt
            )
            val effectiveLast = presence?.lastSeen ?: checkIn.checkedInAt
            val stayMs = (effectiveLast - effectiveFirst).coerceAtLeast(0L)

            // Cap at class duration so a forgotten phone can't produce >100%.
            val cappedStayMs = minOf(stayMs, classDurationMs)
            val stayMinutes  = (cappedStayMs / 60_000L).toInt()
            val percentage   = cappedStayMs.toFloat() / classDurationMs.toFloat()

            val status = when {
                percentage >= PRESENT_THRESHOLD -> "PRESENT"
                percentage >= PARTIAL_THRESHOLD -> "PARTIAL"
                else                            -> "ABSENT"
            }

            results += AttendanceResult(
                studentId       = student.rollNo,
                name            = student.name,
                status          = status,
                durationMinutes = stayMinutes,
                percentage      = percentage
            )
        }

        Log.i(TAG, "Attendance report session=$sessionId: rosterSize=${roster.size}, " +
                "checkIns=${checkIns.size}, presentRows=${results.count { it.status == "PRESENT" }}")

        return results.sortedWith(
            compareBy({ it.status != "PRESENT" }, { it.name })
        )
    }

    /**
     * Serializes a list of [AttendanceResult] to a CSV string.
     *
     * Format:
     *   Roll No,Name,Status,Duration (min),Percentage
     *   CS001,Aarav Patel,PRESENT,45,90.0
     */
    fun generateReport(results: List<AttendanceResult>): String {
        val sb = StringBuilder()
        sb.appendLine("Roll No,Name,Status,Duration (min),Percentage,Signal (ms RTT)")

        for (result in results) {
            val escapedName = result.name.replace("\"", "\"\"")
            val percentStr  = "%.1f".format(result.percentage * 100f)
            val rttStr      = result.medianRttMs?.toString() ?: "-"
            sb.appendLine(
                "${result.studentId},\"$escapedName\",${result.status}," +
                "${result.durationMinutes},$percentStr,$rttStr"
            )
        }

        return sb.toString()
    }

    /**
     * Generates a human-readable summary line for a session report.
     */
    fun generateSummary(results: List<AttendanceResult>): String {
        val present = results.count { it.status == "PRESENT" }
        val partial = results.count { it.status == "PARTIAL" }
        val absent  = results.count { it.status == "ABSENT" }
        val total   = results.size
        return "Total: $total | Present: $present | Partial: $partial | Absent: $absent"
    }
}
