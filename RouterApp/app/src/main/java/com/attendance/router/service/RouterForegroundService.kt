package com.attendance.router.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.attendance.router.AttendanceEngine
import com.attendance.router.CheckInHttpServer
import com.attendance.router.HeartbeatServer
import com.attendance.router.HotspotManager
import com.attendance.router.R
import com.attendance.router.db.AppDatabase
import com.attendance.router.db.PresenceLog
import com.attendance.router.db.Session
import com.attendance.router.ui.DashboardActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Long-running foreground service that orchestrates:
 *   - WiFi hotspot creation via HotspotManager
 *   - UDP heartbeat reception via HeartbeatServer
 *   - Periodic occupancy computation and broadcast
 *   - Session lifecycle management
 *
 * ANR-safe design:
 *   1. startForeground() is called immediately in onCreate() — within 5 seconds as required.
 *   2. All heavy work (DB init, hotspot, UDP server) runs on Dispatchers.IO.
 *   3. The main thread is never blocked.
 */
class RouterForegroundService : Service() {

    companion object {
        private const val TAG = "RouterForegroundService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "router_service_channel"
        private const val OCCUPANCY_UPDATE_INTERVAL_MS = 10_000L
        private const val CHECKIN_HTTP_PORT = 8080

        // Intent actions for controlling the service
        const val ACTION_START_SESSION  = "com.attendance.router.START_SESSION"
        const val ACTION_END_SESSION    = "com.attendance.router.END_SESSION"
        const val ACTION_STOP_SERVICE   = "com.attendance.router.STOP_SERVICE"
        // Dashboard sends this after registering its receiver to get the current hotspot state
        const val ACTION_REQUEST_STATE  = "com.attendance.router.REQUEST_STATE"

        // SharedPreferences keys — hotspot info is persisted so it survives the
        // race between service startup and DashboardActivity registration
        const val PREFS_NAME       = "router_prefs"
        const val PREF_SSID        = "hotspot_ssid"
        const val PREF_PASSPHRASE  = "hotspot_passphrase"
        const val PREF_CHECKIN_URL = "checkin_url"

        // Extra keys
        const val EXTRA_COURSE_NAME = "extra_course_name"
        const val EXTRA_SESSION_ID  = "extra_session_id"

        // Broadcast actions (sent to DashboardActivity via LocalBroadcastManager)
        const val BROADCAST_OCCUPANCY_UPDATE = "com.attendance.router.OCCUPANCY_UPDATE"
        const val BROADCAST_HOTSPOT_STARTED  = "com.attendance.router.HOTSPOT_STARTED"
        const val BROADCAST_HOTSPOT_FAILED   = "com.attendance.router.HOTSPOT_FAILED"
        const val BROADCAST_SESSION_STARTED  = "com.attendance.router.SESSION_STARTED"
        const val BROADCAST_SESSION_ENDED    = "com.attendance.router.SESSION_ENDED"
        // Cumulative list of WiFi-connected devices (ARP-detected, anonymized)
        const val BROADCAST_DEVICES_UPDATE   = "com.attendance.router.DEVICES_UPDATE"

        // Hotspot failure reason extra
        const val EXTRA_FAILURE_REASON = "failure_reason"

        // Broadcast extra keys
        const val EXTRA_OCCUPANCY           = "occupancy"
        const val EXTRA_EFFECTIVE_OCCUPANCY = "effective_occupancy"
        const val EXTRA_ACTIVE_TOKENS       = "active_tokens"
        const val EXTRA_SSID                = "ssid"
        const val EXTRA_PASSPHRASE          = "passphrase"
        const val EXTRA_CHECKIN_URL         = "checkin_url"
        // Parallel arrays so we don't need Parcelable. Indexed by device position.
        // NOTE: IP/MAC intentionally NOT broadcast — they stay inside
        // HotspotManager for token generation and never reach the UI layer.
        const val EXTRA_DEVICE_TOKENS       = "device_tokens"       // ArrayList<String>
        const val EXTRA_DEVICE_NAMES        = "device_names"        // ArrayList<String>
        const val EXTRA_DEVICE_FIRST_SEEN   = "device_first_seen"   // LongArray
        const val EXTRA_DEVICE_LAST_SEEN    = "device_last_seen"    // LongArray
        const val EXTRA_DEVICE_CONNECTED    = "device_connected"    // BooleanArray
        const val EXTRA_DEVICE_TOTAL        = "device_total"        // Int — size of list
        const val EXTRA_DEVICE_CURRENT      = "device_current"      // Int — currently-connected count
        // Check-in counts
        const val EXTRA_CHECKED_IN_COUNT    = "checked_in_count"    // Int
        const val EXTRA_ROSTER_SIZE         = "roster_size"         // Int
        // Broadcast for check-in events
        const val BROADCAST_CHECKIN_UPDATE  = "com.attendance.router.CHECKIN_UPDATE"
        const val EXTRA_CHECKIN_NAMES       = "checkin_names"       // ArrayList<String>
    }

    // SupervisorJob so one child crash doesn't cancel siblings
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // db is initialised synchronously in onCreate() — AppDatabase.getInstance()
    // just builds the Room object (fast, no disk I/O). Actual file open happens
    // on the first query, which always runs on Dispatchers.IO.
    private lateinit var db: AppDatabase
    private var hotspotManager: HotspotManager? = null
    private var heartbeatServer: HeartbeatServer? = null
    private var checkInServer: com.attendance.router.CheckInHttpServer? = null

    private var occupancyJob: Job? = null
    private var activeSessionId: String? = null

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "RouterForegroundService.onCreate()")

        // ── STEP 1: startForeground() — MUST happen immediately (within 5 s) ──
        createNotificationChannel()
        callStartForeground()

        // ── STEP 2: DB init — safe on main thread (no disk I/O yet) ───────────
        // Room.databaseBuilder().build() only creates the Room wrapper object.
        // The SQLite file is opened lazily on the first actual query (on IO thread).
        db = AppDatabase.getInstance(applicationContext)
        Log.i(TAG, "AppDatabase instance obtained")

        // ── STEP 3: Restore in-memory session ID from DB (handles OS-triggered restarts) ──
        // Then kick off network init on the same IO coroutine so order is guaranteed.
        serviceScope.launch(Dispatchers.IO) {
            val existingSession = db.sessionDao().getActiveSession()
            if (existingSession != null) {
                activeSessionId = existingSession.sessionId
                Log.i(TAG, "Restored active session from DB: ${existingSession.sessionId}")
            }
            initNetworkComponents()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SESSION -> {
                val courseName = intent.getStringExtra(EXTRA_COURSE_NAME) ?: "Unknown Course"
                startNewSession(courseName)
            }
            ACTION_END_SESSION  -> endCurrentSession()
            ACTION_STOP_SERVICE -> stopSelf()
            ACTION_REQUEST_STATE -> broadcastCurrentState()  // Dashboard asking for a refresh
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "RouterForegroundService.onDestroy()")
        stopCheckInServer()
        heartbeatServer?.stop()
        hotspotManager?.stopHotspot()
        occupancyJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // startForeground — called immediately, before any heavy work
    // -------------------------------------------------------------------------

    private fun callStartForeground() {
        val notification = buildNotification("Starting classroom monitor…")
        // API 29+ requires the service type to match the manifest declaration.
        // We use DATA_SYNC — correct for WiFi-based data collection.
        // CONNECTED_DEVICE would require active Bluetooth/NFC/USB and crashes on Android 14.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Log.i(TAG, "startForeground() called — service type: dataSync")
    }

    // -------------------------------------------------------------------------
    // Network component initialisation (runs on Dispatchers.IO)
    // -------------------------------------------------------------------------

    private suspend fun initNetworkComponents() {
        try {
            // 1. Hotspot API must be called from Main thread
            withContext(Dispatchers.Main) {
                val hm = HotspotManager(applicationContext)
                hm.onArpTokenDetected = { anonymizedToken ->
                    Log.d(TAG, "ARP device detected (anonymized: ${anonymizedToken.take(8)}...)")
                }
                // Every ARP poll publishes the cumulative device list to the UI.
                hm.onDevicesUpdated = { devices ->
                    broadcastDevices(devices)
                }
                hotspotManager = hm
                startHotspot(hm)
            }

            // 2. UDP heartbeat server — socket bind is blocking, stays on IO
            val server = HeartbeatServer(
                presenceDao        = db.presenceDao(),   // db is guaranteed non-null here
                getActiveSessionId = { activeSessionId }
            )
            server.onHeartbeatReceived = { token, sessionId ->
                Log.d(TAG, "Heartbeat: token=${token.take(8)}…, session=$sessionId")
            }
            heartbeatServer = server
            server.start(serviceScope)
            Log.i(TAG, "HeartbeatServer started on port 9876")

            // 3. Start occupancy polling
            withContext(Dispatchers.Main) {
                startOccupancyPolling()
                updateNotification("Classroom monitoring active")
            }

        } catch (e: Exception) {
            Log.e(TAG, "initNetworkComponents error: ${e.message}", e)
            withContext(Dispatchers.Main) {
                updateNotification("Init error: ${e.message}")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Session management
    // -------------------------------------------------------------------------

    private fun startNewSession(courseName: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                // Close any previously open session
                db.sessionDao().getActiveSession()?.let { existing ->
                    db.sessionDao().closeSession(existing.sessionId, System.currentTimeMillis())
                    Log.i(TAG, "Closed previous session: ${existing.sessionId}")
                }

                val sessionId = UUID.randomUUID().toString().take(8)
                db.sessionDao().insert(
                    Session(
                        sessionId  = sessionId,
                        startTime  = System.currentTimeMillis(),
                        endTime    = null,
                        courseName = courseName
                    )
                )

                hotspotManager?.resetSessionSalt()
                hotspotManager?.setSessionActive(true)
                activeSessionId = sessionId

                withContext(Dispatchers.Main) {
                    updateNotification("Session active: $courseName")
                    broadcast(BROADCAST_SESSION_STARTED) {
                        putExtra(EXTRA_SESSION_ID, sessionId)
                        putExtra(EXTRA_COURSE_NAME, courseName)
                    }
                }

                // New session → no check-ins yet. Push fresh counts to the UI.
                broadcastCheckIns()

                Log.i(TAG, "Session started: id=$sessionId, course=$courseName")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start session: ${e.message}", e)
            }
        }
    }

    private fun endCurrentSession() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                // Use the in-memory ID first; fall back to a DB lookup if the service
                // was restarted by Android (START_STICKY) and lost its in-memory state.
                val sessionId = activeSessionId
                    ?: db.sessionDao().getActiveSession()?.sessionId
                    ?: run {
                        Log.w(TAG, "endCurrentSession: no active session in memory or DB")
                        return@launch
                    }

                db.sessionDao().closeSession(sessionId, System.currentTimeMillis())
                hotspotManager?.setSessionActive(false)
                activeSessionId = null

                withContext(Dispatchers.Main) {
                    updateNotification("Session ended — monitoring active")
                    broadcast(BROADCAST_SESSION_ENDED) {
                        putExtra(EXTRA_SESSION_ID, sessionId)
                    }
                }

                // Clear the dashboard check-in strip now that no session is active.
                broadcastCheckIns()

                Log.i(TAG, "Session ended: id=$sessionId")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to end session: ${e.message}", e)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Hotspot (must be called from Main thread)
    // -------------------------------------------------------------------------

    private fun startHotspot(hm: HotspotManager) {
        hm.startHotspot(
            onStarted = { ssid, passphrase ->
                Log.i(TAG, "Hotspot up: SSID=$ssid")
                updateNotification("Hotspot: $ssid | Monitoring active")

                // Persist so DashboardActivity can read it even if it missed the broadcast
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(PREF_SSID, ssid)
                    .putString(PREF_PASSPHRASE, passphrase)
                    .apply()

                broadcast(BROADCAST_HOTSPOT_STARTED) {
                    putExtra(EXTRA_SSID, ssid)
                    putExtra(EXTRA_PASSPHRASE, passphrase)
                }

                // Start the check-in HTTP server now that the hotspot interface is up.
                // Students scan the QR on the Dashboard, their browser opens
                // http://<hotspot-ip>:8080, and the form posts back here.
                startCheckInServer()
            },
            onFailed = { reason ->
                Log.e(TAG, "Hotspot failed: reason=$reason")
                updateNotification("Hotspot unavailable — trying shared-network mode")
                // Tell the Dashboard so it can show an error instead of "Starting…"
                broadcast(BROADCAST_HOTSPOT_FAILED) {
                    putExtra(EXTRA_FAILURE_REASON, reason)
                }
                // Shared-network fallback: if the phone is already connected
                // to a WiFi as a client, the HTTP server can still advertise
                // on that IP and students on the same SSID can check in.
                // This is how the app was originally working at 10.x.x.x:8080.
                startCheckInServer()
            }
        )
    }

    // -------------------------------------------------------------------------
    // Check-in HTTP server (NanoHTTPD on port 8080, bound to the hotspot iface)
    // -------------------------------------------------------------------------

    /**
     * Spins up [CheckInHttpServer] on port 8080 of the hotspot interface.
     * Safe to call repeatedly — no-ops if the server is already running.
     * Runs on IO so start() (which binds a socket) doesn't touch the main thread.
     */
    private fun startCheckInServer() {
        if (checkInServer != null) {
            Log.d(TAG, "CheckInHttpServer already running")
            return
        }

        val hm = hotspotManager
        if (hm == null) {
            Log.w(TAG, "startCheckInServer: HotspotManager not ready")
            return
        }

        serviceScope.launch(Dispatchers.IO) {
            try {
                val ip = hm.getHotspotIp()

                // Try to bind specifically to the hotspot IP first — on some
                // Android builds the tether namespace rejects inbound packets
                // on a 0.0.0.0-bound socket. If that fails (e.g. interface
                // isn't up yet), fall back to all-interfaces.
                val server = try {
                    val s = CheckInHttpServer(
                        bindHost            = ip,
                        port                = CHECKIN_HTTP_PORT,
                        rosterDao           = db.rosterDao(),
                        checkInDao          = db.checkInDao(),
                        presenceDao         = db.presenceDao(),
                        sessionDao          = db.sessionDao(),
                        getActiveSessionId  = { activeSessionId },
                        getSessionSalt     = { hm.sessionSalt },
                        onCheckIn           = { broadcastCheckIns() }
                    )
                    s.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, true)
                    Log.i(TAG, "CheckInHttpServer bound to $ip:$CHECKIN_HTTP_PORT")
                    s
                } catch (bindEx: Exception) {
                    Log.w(TAG, "Explicit bind to $ip failed (${bindEx.message}); falling back to 0.0.0.0")
                    val s = CheckInHttpServer(
                        port                = CHECKIN_HTTP_PORT,
                        rosterDao           = db.rosterDao(),
                        checkInDao          = db.checkInDao(),
                        presenceDao         = db.presenceDao(),
                        sessionDao          = db.sessionDao(),
                        getActiveSessionId  = { activeSessionId },
                        getSessionSalt     = { hm.sessionSalt },
                        onCheckIn           = { broadcastCheckIns() }
                    )
                    s.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, true)
                    Log.i(TAG, "CheckInHttpServer bound to 0.0.0.0:$CHECKIN_HTTP_PORT")
                    s
                }
                checkInServer = server

                val checkInUrl = if (ip != null) "http://$ip:$CHECKIN_HTTP_PORT" else null
                Log.i(TAG, "CheckInHttpServer listening — check-in URL: ${checkInUrl ?: "<pending IP>"}")

                if (checkInUrl != null) {
                    // Persist + broadcast so the Dashboard can render a QR code for it.
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putString(PREF_CHECKIN_URL, checkInUrl)
                        .apply()

                    withContext(Dispatchers.Main) {
                        broadcast(BROADCAST_HOTSPOT_STARTED) {
                            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            putExtra(EXTRA_SSID, prefs.getString(PREF_SSID, ""))
                            putExtra(EXTRA_PASSPHRASE, prefs.getString(PREF_PASSPHRASE, ""))
                            putExtra(EXTRA_CHECKIN_URL, checkInUrl)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start CheckInHttpServer: ${e.message}", e)
                checkInServer = null
            }
        }
    }

    private fun stopCheckInServer() {
        checkInServer?.let { srv ->
            try {
                srv.stop()
                Log.i(TAG, "CheckInHttpServer stopped")
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping CheckInHttpServer: ${e.message}")
            }
        }
        checkInServer = null
    }

    /**
     * Re-broadcasts the current hotspot state when DashboardActivity requests it.
     * Called when ACTION_REQUEST_STATE is received — handles the race condition where
     * the hotspot started before the Activity registered its broadcast receiver.
     */
    private fun broadcastCurrentState() {
        // Re-send hotspot info if available
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val ssid       = prefs.getString(PREF_SSID, null)
        val passphrase = prefs.getString(PREF_PASSPHRASE, null)

        if (ssid != null && passphrase != null) {
            val checkInUrl = prefs.getString(PREF_CHECKIN_URL, null)
            broadcast(BROADCAST_HOTSPOT_STARTED) {
                putExtra(EXTRA_SSID, ssid)
                putExtra(EXTRA_PASSPHRASE, passphrase)
                if (checkInUrl != null) putExtra(EXTRA_CHECKIN_URL, checkInUrl)
            }
            Log.d(TAG, "Re-broadcast hotspot state to Dashboard: SSID=$ssid")
        } else {
            Log.d(TAG, "REQUEST_STATE: hotspot not ready yet")
        }

        // Re-send active session info if any
        activeSessionId?.let { sid ->
            serviceScope.launch(Dispatchers.IO) {
                val session = db.sessionDao().getById(sid)
                if (session != null) {
                    withContext(Dispatchers.Main) {
                        broadcast(BROADCAST_SESSION_STARTED) {
                            putExtra(EXTRA_SESSION_ID, session.sessionId)
                            putExtra(EXTRA_COURSE_NAME, session.courseName)
                        }
                    }
                }
            }
        }

        // Re-send the cumulative device list so a just-opened Dashboard shows
        // everyone who has already connected in this session.
        hotspotManager?.snapshotDevices()?.let { broadcastDevices(it) }

        // Re-send check-in counts (names + count + roster size) so the dashboard
        // can render the check-in strip correctly on cold start.
        broadcastCheckIns()
    }

    // -------------------------------------------------------------------------
    // Occupancy polling
    // -------------------------------------------------------------------------

    private fun startOccupancyPolling() {
        occupancyJob?.cancel()
        occupancyJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val dao = db.presenceDao()
                    val occupancy          = AttendanceEngine.computeOccupancy(dao)
                    val effectiveOccupancy = AttendanceEngine.computeEffectiveOccupancy(dao)

                    val sessionId = activeSessionId
                    val activeTokens: ArrayList<String> = if (sessionId != null) {
                        val since = System.currentTimeMillis() - 90_000L
                        ArrayList(dao.getActiveTokens(since = since))
                    } else {
                        arrayListOf()
                    }

                    withContext(Dispatchers.Main) {
                        broadcast(BROADCAST_OCCUPANCY_UPDATE) {
                            putExtra(EXTRA_OCCUPANCY, occupancy)
                            putExtra(EXTRA_EFFECTIVE_OCCUPANCY, effectiveOccupancy)
                            putStringArrayListExtra(EXTRA_ACTIVE_TOKENS, activeTokens)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Occupancy poll error: ${e.message}")
                }

                delay(OCCUPANCY_UPDATE_INTERVAL_MS)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Notification helpers
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Classroom Attendance Monitor",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows while attendance monitoring is running"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, DashboardActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Classroom monitoring active")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(contentText: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    // -------------------------------------------------------------------------
    // Broadcast helper
    // -------------------------------------------------------------------------

    private fun broadcast(action: String, extras: Intent.() -> Unit = {}) {
        val intent = Intent(action).apply(extras)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    /**
     * Serialises a cumulative device list into parallel arrays and broadcasts it.
     * The UI joins them back into a list in DashboardActivity.
     */
    private fun broadcastDevices(devices: List<HotspotManager.ConnectedDevice>) {
        val tokens     = ArrayList<String>(devices.size)
        val names      = ArrayList<String>(devices.size)
        val firstSeen  = LongArray(devices.size)
        val lastSeen   = LongArray(devices.size)
        val connected  = BooleanArray(devices.size)
        var currentlyConnectedCount = 0

        devices.forEachIndexed { i, d ->
            tokens.add(d.token)
            names.add(d.displayName)
            firstSeen[i] = d.firstSeenMs
            lastSeen[i]  = d.lastSeenMs
            connected[i] = d.currentlyConnected
            if (d.currentlyConnected) currentlyConnectedCount++
        }

        broadcast(BROADCAST_DEVICES_UPDATE) {
            putStringArrayListExtra(EXTRA_DEVICE_TOKENS, tokens)
            putStringArrayListExtra(EXTRA_DEVICE_NAMES, names)
            putExtra(EXTRA_DEVICE_FIRST_SEEN, firstSeen)
            putExtra(EXTRA_DEVICE_LAST_SEEN, lastSeen)
            putExtra(EXTRA_DEVICE_CONNECTED, connected)
            putExtra(EXTRA_DEVICE_TOTAL, devices.size)
            putExtra(EXTRA_DEVICE_CURRENT, currentlyConnectedCount)
        }

        // Persist to Room so the Attendance Report has data to compute from.
        persistDeviceActivity(devices)
    }

    /**
     * Upserts each detected device into [presence_log] under the active session,
     * and auto-enrolls the token with its display name so the report can show
     * a friendly name instead of an opaque token prefix.
     *
     * Safe to call every scan — all writes are upserts.
     */
    private fun persistDeviceActivity(devices: List<HotspotManager.ConnectedDevice>) {
        val sessionId = activeSessionId ?: return          // no session → nothing to record
        if (devices.isEmpty()) return

        serviceScope.launch(Dispatchers.IO) {
            try {
                val presenceDao = db.presenceDao()
                for (d in devices) {
                    // presence_log — upsert firstSeen/lastSeen for this token+session.
                    // Attendance relies on this to measure how long each checked-in
                    // student's phone stayed on Wi-Fi during the class.
                    val existing = presenceDao.getByTokenAndSession(d.token, sessionId)
                    if (existing == null) {
                        presenceDao.insertPresence(
                            PresenceLog(
                                token     = d.token,
                                sessionId = sessionId,
                                firstSeen = d.firstSeenMs,
                                lastSeen  = d.lastSeenMs
                            )
                        )
                    } else {
                        presenceDao.updateLastSeen(existing.id, d.lastSeenMs)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "persistDeviceActivity failed: ${e.message}", e)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Check-in broadcast helper
    // -------------------------------------------------------------------------

    /**
     * Broadcasts the current list of checked-in student names and counts.
     * Called on every successful check-in and on dashboard state refresh.
     */
    private fun broadcastCheckIns() {
        val sid = activeSessionId
        serviceScope.launch(Dispatchers.IO) {
            try {
                val names = if (sid != null) {
                    db.checkInDao().getNamesBySession(sid)
                } else emptyList()
                val rosterSize = db.rosterDao().getCount()
                withContext(Dispatchers.Main) {
                    broadcast(BROADCAST_CHECKIN_UPDATE) {
                        putStringArrayListExtra(EXTRA_CHECKIN_NAMES, ArrayList(names))
                        putExtra(EXTRA_CHECKED_IN_COUNT, names.size)
                        putExtra(EXTRA_ROSTER_SIZE, rosterSize)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "broadcastCheckIns failed: ${e.message}", e)
            }
        }
    }
}
