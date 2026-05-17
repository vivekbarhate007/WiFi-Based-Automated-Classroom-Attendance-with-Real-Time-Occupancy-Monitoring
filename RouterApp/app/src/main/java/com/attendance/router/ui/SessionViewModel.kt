package com.attendance.router.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.attendance.router.AttendanceEngine
import com.attendance.router.db.AppDatabase
import com.attendance.router.db.AttendanceResult
import com.attendance.router.db.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * ViewModel that bridges the UI and the database/engine layer.
 * Manages session state and exposes LiveData for occupancy, enrolled count, etc.
 */
class SessionViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application.applicationContext)

    // -------------------------------------------------------------------------
    // Occupancy — updated from service broadcasts
    // -------------------------------------------------------------------------

    private val _occupancy = MutableLiveData(0)
    val occupancy: LiveData<Int> get() = _occupancy

    private val _effectiveOccupancy = MutableLiveData(0)
    val effectiveOccupancy: LiveData<Int> get() = _effectiveOccupancy

    private val _activeTokens = MutableLiveData<List<String>>(emptyList())
    val activeTokens: LiveData<List<String>> get() = _activeTokens

    // -------------------------------------------------------------------------
    // Enrollment count
    // -------------------------------------------------------------------------

    val enrolledCount: LiveData<Int> = db.enrollmentDao().getCountLive()

    // -------------------------------------------------------------------------
    // Session state
    // -------------------------------------------------------------------------

    private val _activeSession = MutableLiveData<Session?>(null)
    val activeSession: LiveData<Session?> get() = _activeSession

    private val _sessionList = MutableLiveData<List<Session>>(emptyList())
    val sessionList: LiveData<List<Session>> get() = _sessionList

    // -------------------------------------------------------------------------
    // Hotspot info
    // -------------------------------------------------------------------------

    private val _ssid = MutableLiveData("—")
    val ssid: LiveData<String> get() = _ssid

    private val _passphrase = MutableLiveData("—")
    val passphrase: LiveData<String> get() = _passphrase

    // -------------------------------------------------------------------------
    // Report data
    // -------------------------------------------------------------------------

    private val _reportResults = MutableLiveData<List<AttendanceResult>>(emptyList())
    val reportResults: LiveData<List<AttendanceResult>> get() = _reportResults

    private val _reportCsv = MutableLiveData<String>("")
    val reportCsv: LiveData<String> get() = _reportCsv

    private val _reportSummary = MutableLiveData<String>("")
    val reportSummary: LiveData<String> get() = _reportSummary

    // -------------------------------------------------------------------------
    // Status / error messages
    // -------------------------------------------------------------------------

    private val _statusMessage = MutableLiveData<String>("")
    val statusMessage: LiveData<String> get() = _statusMessage

    // -------------------------------------------------------------------------
    // Public update methods (called from DashboardActivity broadcast handlers)
    // -------------------------------------------------------------------------

    fun updateOccupancy(occupancy: Int, effectiveOccupancy: Int, tokens: List<String>) {
        _occupancy.postValue(occupancy)
        _effectiveOccupancy.postValue(effectiveOccupancy)
        _activeTokens.postValue(tokens)
    }

    fun updateHotspotInfo(ssid: String, passphrase: String) {
        _ssid.postValue(ssid)
        _passphrase.postValue(passphrase)
    }

    fun updateActiveSession(session: Session?) {
        _activeSession.postValue(session)
    }

    fun setStatusMessage(message: String) {
        _statusMessage.postValue(message)
    }

    // -------------------------------------------------------------------------
    // Database operations
    // -------------------------------------------------------------------------

    fun refreshActiveSession() {
        viewModelScope.launch(Dispatchers.IO) {
            val session = db.sessionDao().getActiveSession()
            _activeSession.postValue(session)
        }
    }

    fun refreshSessionList() {
        viewModelScope.launch(Dispatchers.IO) {
            val sessions = db.sessionDao().getAll()
            _sessionList.postValue(sessions)
        }
    }

    /**
     * Generates the attendance report for a given session.
     * Joins token ↔ student identity (the only place this happens).
     *
     * @param sessionId            the session to report on
     * @param classDurationMinutes total class length in minutes
     */
    fun generateReport(sessionId: String, classDurationMinutes: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val results = AttendanceEngine.computeAttendance(sessionId, classDurationMinutes, db)
                val csv = AttendanceEngine.generateReport(results)
                val summary = AttendanceEngine.generateSummary(results)

                _reportResults.postValue(results)
                _reportCsv.postValue(csv)
                _reportSummary.postValue(summary)
                _statusMessage.postValue("Report generated for session $sessionId")
            } catch (e: Exception) {
                _statusMessage.postValue("Error generating report: ${e.message}")
            }
        }
    }

    /**
     * Adds a test enrollment entry. In a real app this would come from a QR scan
     * or an enrollment import flow.
     */
    fun enrollStudent(token: String, studentId: String, name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            db.enrollmentDao().insert(
                com.attendance.router.db.Enrollment(
                    token = token,
                    studentId = studentId,
                    name = name
                )
            )
            _statusMessage.postValue("Enrolled: $name")
        }
    }
}
