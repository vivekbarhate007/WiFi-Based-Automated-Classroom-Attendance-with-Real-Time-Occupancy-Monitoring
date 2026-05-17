package com.attendance.router.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.attendance.router.QrCodeGenerator
import com.attendance.router.R
import com.attendance.router.service.RouterForegroundService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Main dashboard shown to the professor.
 *
 * Displays:
 *  - Hotspot SSID and password (large, for students to see)
 *  - Live occupancy count
 *  - List of active student tokens (anonymized)
 *  - Session controls (Start / End Session)
 *  - ● LIVE badge + elapsed time when session is running
 *  - Navigation to ReportActivity
 */
class DashboardActivity : AppCompatActivity() {

    private val viewModel: SessionViewModel by viewModels()

    private lateinit var tvSsid: TextView
    private lateinit var tvPassphrase: TextView
    private lateinit var tvNetworkStatus: TextView
    private lateinit var tvOccupancy: TextView
    private lateinit var tvEffectiveOccupancy: TextView
    private lateinit var tvEnrolledCount: TextView
    private lateinit var tvSessionStatus: TextView
    private lateinit var tvStatusMessage: TextView
    private lateinit var tvLiveBadge: TextView
    private lateinit var tvElapsedTime: TextView
    private lateinit var tvDeviceCounts: TextView
    private lateinit var tvNoDevices: TextView
    private lateinit var rvConnectedDevices: RecyclerView
    private lateinit var tvCheckedInCount: TextView
    private lateinit var tvCheckedInNames: TextView
    private lateinit var tvCheckInUrl: TextView
    private lateinit var tvCheckInLabel: TextView
    private lateinit var ivQrCode: ImageView
    private lateinit var btnStartSession: com.google.android.material.button.MaterialButton
    private lateinit var btnEndSession: com.google.android.material.button.MaterialButton
    private lateinit var btnViewReport: com.google.android.material.button.MaterialButton
    private lateinit var btnManageRoster: com.google.android.material.button.MaterialButton
    private lateinit var btnTestServer: com.google.android.material.button.MaterialButton

    /** Cache the URL currently rendered into the QR image so we don't re-encode on every broadcast. */
    private var currentQrUrl: String? = null

    private val deviceAdapter = ConnectedDeviceAdapter()

    /** Coroutine job that ticks the elapsed-time counter. Cancelled when session ends. */
    private var elapsedJob: Job? = null

    // -------------------------------------------------------------------------
    // Broadcast receiver for service updates
    // -------------------------------------------------------------------------

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                RouterForegroundService.BROADCAST_OCCUPANCY_UPDATE -> {
                    val occupancy = intent.getIntExtra(RouterForegroundService.EXTRA_OCCUPANCY, 0)
                    val effective = intent.getIntExtra(RouterForegroundService.EXTRA_EFFECTIVE_OCCUPANCY, 0)
                    val tokens = intent.getStringArrayListExtra(RouterForegroundService.EXTRA_ACTIVE_TOKENS) ?: arrayListOf()
                    viewModel.updateOccupancy(occupancy, effective, tokens)
                }
                RouterForegroundService.BROADCAST_DEVICES_UPDATE -> {
                    val tokens      = intent.getStringArrayListExtra(RouterForegroundService.EXTRA_DEVICE_TOKENS) ?: arrayListOf()
                    val names       = intent.getStringArrayListExtra(RouterForegroundService.EXTRA_DEVICE_NAMES) ?: arrayListOf()
                    val firstSeen   = intent.getLongArrayExtra(RouterForegroundService.EXTRA_DEVICE_FIRST_SEEN) ?: LongArray(0)
                    val lastSeen    = intent.getLongArrayExtra(RouterForegroundService.EXTRA_DEVICE_LAST_SEEN) ?: LongArray(0)
                    val connected   = intent.getBooleanArrayExtra(RouterForegroundService.EXTRA_DEVICE_CONNECTED) ?: BooleanArray(0)
                    val total       = intent.getIntExtra(RouterForegroundService.EXTRA_DEVICE_TOTAL, tokens.size)
                    val currentNow  = intent.getIntExtra(RouterForegroundService.EXTRA_DEVICE_CURRENT, 0)
                    updateConnectedDevices(tokens, names, firstSeen, lastSeen, connected, total, currentNow)
                }
                RouterForegroundService.BROADCAST_CHECKIN_UPDATE -> {
                    val names   = intent.getStringArrayListExtra(RouterForegroundService.EXTRA_CHECKIN_NAMES) ?: arrayListOf()
                    val count   = intent.getIntExtra(RouterForegroundService.EXTRA_CHECKED_IN_COUNT, 0)
                    val roster  = intent.getIntExtra(RouterForegroundService.EXTRA_ROSTER_SIZE, 0)
                    updateCheckInStatus(names, count, roster)
                }
                RouterForegroundService.BROADCAST_HOTSPOT_STARTED -> {
                    val ssid = intent.getStringExtra(RouterForegroundService.EXTRA_SSID) ?: "—"
                    val pass = intent.getStringExtra(RouterForegroundService.EXTRA_PASSPHRASE) ?: "—"
                    val url  = intent.getStringExtra(RouterForegroundService.EXTRA_CHECKIN_URL)
                    viewModel.updateHotspotInfo(ssid, pass)
                    tvNetworkStatus.text = "✓ Hotspot ready — share with students"
                    if (!url.isNullOrBlank()) renderCheckInQr(url)
                }
                RouterForegroundService.BROADCAST_HOTSPOT_FAILED -> {
                    val reason = intent.getIntExtra(RouterForegroundService.EXTRA_FAILURE_REASON, -1)
                    val msg = when (reason) {
                        1 -> "❌ Hotspot blocked — another app is using it"
                        2 -> "❌ No WiFi channel available — disable & re-enable WiFi"
                        else -> "❌ Hotspot failed (code $reason) — ensure WiFi is ON"
                    }
                    tvNetworkStatus.text = msg
                    tvSsid.text = "Unavailable"
                    tvPassphrase.text = "—"
                }
                RouterForegroundService.BROADCAST_SESSION_STARTED -> {
                    viewModel.refreshActiveSession()
                    Toast.makeText(this@DashboardActivity, "Session started!", Toast.LENGTH_SHORT).show()
                }
                RouterForegroundService.BROADCAST_SESSION_ENDED -> {
                    viewModel.refreshActiveSession()
                    Toast.makeText(this@DashboardActivity, "Session ended.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        initViews()
        setupRecyclerView()
        observeViewModel()

        // Register receivers BEFORE requesting state so we don't miss the response
        registerReceivers()

        // ── Fix for race condition: hotspot may have started before we registered ──
        // 1. Immediately load any cached hotspot info from SharedPreferences
        loadCachedHotspotInfo()

        // 2. Ask the service to re-broadcast its current state (SSID, session, etc.)
        requestServiceState()

        // 3. Refresh session state from DB
        viewModel.refreshActiveSession()
    }

    override fun onDestroy() {
        elapsedJob?.cancel()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(serviceReceiver)
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // View setup
    // -------------------------------------------------------------------------

    private fun initViews() {
        tvSsid               = findViewById(R.id.tvSsid)
        tvPassphrase         = findViewById(R.id.tvPassphrase)
        tvNetworkStatus      = findViewById(R.id.tvNetworkStatus)
        tvOccupancy          = findViewById(R.id.tvOccupancy)
        tvEffectiveOccupancy = findViewById(R.id.tvEffectiveOccupancy)
        tvEnrolledCount      = findViewById(R.id.tvEnrolledCount)
        tvSessionStatus      = findViewById(R.id.tvSessionStatus)
        tvStatusMessage      = findViewById(R.id.tvStatusMessage)
        tvLiveBadge          = findViewById(R.id.tvLiveBadge)
        tvElapsedTime        = findViewById(R.id.tvElapsedTime)
        tvDeviceCounts       = findViewById(R.id.tvDeviceCounts)
        tvNoDevices          = findViewById(R.id.tvNoDevices)
        rvConnectedDevices   = findViewById(R.id.rvConnectedDevices)
        tvCheckedInCount     = findViewById(R.id.tvCheckedInCount)
        tvCheckedInNames     = findViewById(R.id.tvCheckedInNames)
        tvCheckInUrl         = findViewById(R.id.tvCheckInUrl)
        tvCheckInLabel       = findViewById(R.id.tvCheckInLabel)
        ivQrCode             = findViewById(R.id.ivQrCode)
        btnStartSession      = findViewById(R.id.btnStartSession)
        btnEndSession        = findViewById(R.id.btnEndSession)
        btnViewReport        = findViewById(R.id.btnViewReport)
        btnManageRoster      = findViewById(R.id.btnManageRoster)
        btnTestServer        = findViewById(R.id.btnTestServer)

        btnStartSession.setOnClickListener { showStartSessionDialog() }
        btnEndSession.setOnClickListener { sendEndSessionCommand() }
        btnViewReport.setOnClickListener { openReportActivity() }
        btnManageRoster.setOnClickListener {
            startActivity(Intent(this, RosterActivity::class.java))
        }

        // Long-press the friendly label to reveal the raw URL (for the
        // teacher to debug). A short-tap copies whatever URL is current.
        tvCheckInLabel.setOnLongClickListener {
            tvCheckInUrl.visibility =
                if (tvCheckInUrl.visibility == android.view.View.VISIBLE)
                    android.view.View.GONE
                else
                    android.view.View.VISIBLE
            true
        }
        tvCheckInLabel.setOnClickListener { copyCurrentUrlToClipboard() }
        tvCheckInUrl.setOnClickListener { copyCurrentUrlToClipboard() }

        // Self-test: hit /ping on our own server to confirm it's bound and
        // reachable on the hotspot interface. Lets the teacher distinguish
        // "my server is broken" from "the student phone is misconfigured"
        // without needing adb logcat.
        btnTestServer.setOnClickListener { runServerSelfTest() }
    }

    private fun copyCurrentUrlToClipboard() {
        val url = currentQrUrl
        if (url.isNullOrBlank()) return
        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("check-in url", url))
        android.widget.Toast
            .makeText(this, R.string.check_in_url_copied, android.widget.Toast.LENGTH_SHORT)
            .show()
    }

    /**
     * Self-test: GET /ping from the current hotspot URL. Runs off the main
     * thread so a slow/unreachable server doesn't ANR.
     */
    private fun runServerSelfTest() {
        val url = currentQrUrl
        if (url.isNullOrBlank()) {
            android.widget.Toast
                .makeText(this, R.string.test_server_no_ip, android.widget.Toast.LENGTH_LONG)
                .show()
            return
        }
        Thread {
            val start = System.currentTimeMillis()
            val result = try {
                val conn = java.net.URL("$url/ping").openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 2500
                conn.readTimeout    = 2500
                conn.requestMethod  = "GET"
                val code = conn.responseCode
                val body = conn.inputStream.bufferedReader().use { it.readLine() ?: "" }
                conn.disconnect()
                if (code == 200 && body.startsWith("pong")) "ok" else "http $code"
            } catch (e: Exception) {
                e.javaClass.simpleName
            }
            val elapsed = System.currentTimeMillis() - start
            runOnUiThread {
                val msg = if (result == "ok")
                    getString(R.string.test_server_ok, "${elapsed}ms")
                else
                    getString(R.string.test_server_fail, result)
                android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun setupRecyclerView() {
        rvConnectedDevices.layoutManager = LinearLayoutManager(this)
        rvConnectedDevices.adapter = deviceAdapter
    }

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(RouterForegroundService.BROADCAST_OCCUPANCY_UPDATE)
            addAction(RouterForegroundService.BROADCAST_DEVICES_UPDATE)
            addAction(RouterForegroundService.BROADCAST_CHECKIN_UPDATE)
            addAction(RouterForegroundService.BROADCAST_HOTSPOT_STARTED)
            addAction(RouterForegroundService.BROADCAST_HOTSPOT_FAILED)
            addAction(RouterForegroundService.BROADCAST_SESSION_STARTED)
            addAction(RouterForegroundService.BROADCAST_SESSION_ENDED)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(serviceReceiver, filter)
    }

    // -------------------------------------------------------------------------
    // ViewModel observation
    // -------------------------------------------------------------------------

    private fun observeViewModel() {
        viewModel.ssid.observe(this) { ssid ->
            tvSsid.text = ssid
        }

        viewModel.passphrase.observe(this) { pass ->
            tvPassphrase.text = pass
        }

        viewModel.occupancy.observe(this) { count ->
            tvOccupancy.text = count.toString()
        }

        viewModel.effectiveOccupancy.observe(this) { count ->
            tvEffectiveOccupancy.text = getString(R.string.effective_occupancy, count)
        }

        viewModel.enrolledCount.observe(this) { count ->
            tvEnrolledCount.text = getString(R.string.enrolled_count, count)
        }

        // Note: viewModel.activeTokens (heartbeat-based) is intentionally not
        // observed here — the Connected Devices card is fed directly from
        // the BROADCAST_DEVICES_UPDATE handler using ARP data.

        viewModel.activeSession.observe(this) { session ->
            if (session != null) {
                // ── Session is ACTIVE ──────────────────────────────────────────
                tvSessionStatus.text = getString(R.string.session_active, session.courseName, session.sessionId)

                // Show LIVE badge and elapsed timer
                tvLiveBadge.visibility   = View.VISIBLE
                tvElapsedTime.visibility = View.VISIBLE
                startElapsedTimer(session.startTime)

                // Change Start button label to communicate ongoing state
                btnStartSession.text      = "● Session Active"
                btnStartSession.isEnabled = false
                btnEndSession.isEnabled   = true

            } else {
                // ── No active session ──────────────────────────────────────────
                tvSessionStatus.text = getString(R.string.session_inactive)

                // Hide LIVE badge and elapsed timer
                tvLiveBadge.visibility   = View.GONE
                tvElapsedTime.visibility = View.GONE
                stopElapsedTimer()

                // Restore Start button to its original label
                btnStartSession.text      = getString(R.string.btn_start_session)
                btnStartSession.isEnabled = true
                btnEndSession.isEnabled   = false
            }

            // View Report is ALWAYS enabled — users can view past reports any time
            btnViewReport.isEnabled = true
        }

        viewModel.statusMessage.observe(this) { msg ->
            if (msg.isNotBlank()) {
                tvStatusMessage.text = msg
            }
        }
    }

    // -------------------------------------------------------------------------
    // Elapsed time ticker
    // -------------------------------------------------------------------------

    /**
     * Starts (or restarts) a coroutine that updates [tvElapsedTime] every second
     * from the given [sessionStartTimeMs] epoch millisecond.
     */
    private fun startElapsedTimer(sessionStartTimeMs: Long) {
        elapsedJob?.cancel()
        elapsedJob = lifecycleScope.launch {
            while (isActive) {
                val elapsed = System.currentTimeMillis() - sessionStartTimeMs
                tvElapsedTime.text = formatElapsed(elapsed)
                delay(1_000L)
            }
        }
    }

    private fun stopElapsedTimer() {
        elapsedJob?.cancel()
        elapsedJob = null
        tvElapsedTime.text = "00:00:00"
    }

    /** Formats milliseconds → "HH:MM:SS" */
    private fun formatElapsed(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val hours   = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d:%02d".format(hours, minutes, seconds)
    }

    // -------------------------------------------------------------------------
    // User interactions
    // -------------------------------------------------------------------------

    private fun showStartSessionDialog() {
        // Wrap the EditText in a container with proper padding so it doesn't
        // sit flush against the dialog edges
        val container = android.widget.FrameLayout(this)
        val etCourseName = EditText(this).apply {
            hint = "e.g. CS655 - Mobile Systems"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
        }
        val paddingPx = (20 * resources.displayMetrics.density).toInt()
        container.setPadding(paddingPx, 0, paddingPx, 0)
        container.addView(etCourseName)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Start New Session")
            .setMessage("Enter the course name for this class session:")
            .setView(container)
            .setPositiveButton("Start", null) // set to null to override dismiss
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            // Show keyboard automatically
            etCourseName.requestFocus()

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val courseName = etCourseName.text.toString().trim()
                if (courseName.isBlank()) {
                    etCourseName.error = "Course name cannot be empty"
                } else {
                    sendStartSessionCommand(courseName)
                    Toast.makeText(this, "Starting session: $courseName", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
        }

        dialog.show()
    }

    private fun sendStartSessionCommand(courseName: String) {
        val intent = Intent(this, RouterForegroundService::class.java).apply {
            action = RouterForegroundService.ACTION_START_SESSION
            putExtra(RouterForegroundService.EXTRA_COURSE_NAME, courseName)
        }
        // Use startForegroundService to ensure service is running before command arrives
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun sendEndSessionCommand() {
        AlertDialog.Builder(this)
            .setTitle("End Session")
            .setMessage("Are you sure you want to end the current session?")
            .setPositiveButton("End Session") { _, _ ->
                val intent = Intent(this, RouterForegroundService::class.java).apply {
                    action = RouterForegroundService.ACTION_END_SESSION
                }
                startService(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openReportActivity() {
        viewModel.refreshSessionList()
        startActivity(Intent(this, ReportActivity::class.java))
    }

    /**
     * Loads SSID/passphrase from SharedPreferences immediately on start.
     * This handles the case where the hotspot started before this Activity
     * registered its broadcast receiver (race condition fix).
     */
    private fun loadCachedHotspotInfo() {
        val prefs = getSharedPreferences(
            RouterForegroundService.PREFS_NAME, MODE_PRIVATE
        )
        val ssid       = prefs.getString(RouterForegroundService.PREF_SSID, null)
        val passphrase = prefs.getString(RouterForegroundService.PREF_PASSPHRASE, null)
        val checkInUrl = prefs.getString(RouterForegroundService.PREF_CHECKIN_URL, null)

        if (ssid != null && passphrase != null) {
            viewModel.updateHotspotInfo(ssid, passphrase)
            tvNetworkStatus.text = "✓ Hotspot ready — share with students"
            Log.d("DashboardActivity", "Loaded cached hotspot info: SSID=$ssid")
        }
        if (!checkInUrl.isNullOrBlank()) renderCheckInQr(checkInUrl)
    }

    /**
     * Encodes [url] as a QR bitmap and shows it above the check-in counter,
     * plus renders the human-readable URL underneath. No-op if the URL hasn't
     * changed since the last render (avoids thrashing on every state refresh).
     */
    private fun renderCheckInQr(url: String) {
        if (url == currentQrUrl) return
        currentQrUrl = url
        tvCheckInUrl.text = url

        val bmp = QrCodeGenerator.encodeAsBitmap(url, sizePx = 512)
        if (bmp != null) {
            ivQrCode.setImageBitmap(bmp)
        } else {
            Log.w("DashboardActivity", "QR encoding failed for: $url")
        }
    }

    // -------------------------------------------------------------------------
    // Connected-devices card updates
    // -------------------------------------------------------------------------

    /**
     * Called on every BROADCAST_DEVICES_UPDATE. Merges parallel arrays into
     * domain rows and pushes them to the adapter.
     */
    private fun updateConnectedDevices(
        tokens: List<String>,
        names: List<String>,
        firstSeen: LongArray,
        lastSeen: LongArray,
        connected: BooleanArray,
        total: Int,
        currentNow: Int
    ) {
        tvDeviceCounts.text = "$currentNow online / $total total"

        if (tokens.isEmpty()) {
            tvNoDevices.visibility = View.VISIBLE
            rvConnectedDevices.visibility = View.GONE
            deviceAdapter.submitList(emptyList())
            return
        }

        tvNoDevices.visibility = View.GONE
        rvConnectedDevices.visibility = View.VISIBLE

        val rows = buildList {
            for (i in tokens.indices) {
                add(
                    DeviceRow(
                        token       = tokens[i],
                        displayName = names.getOrNull(i) ?: "",
                        firstSeenMs = firstSeen.getOrNull(i) ?: 0L,
                        lastSeenMs  = lastSeen.getOrNull(i)  ?: 0L,
                        isOnline    = connected.getOrNull(i) ?: false
                    )
                )
            }
        }.sortedWith(
            compareByDescending<DeviceRow> { it.isOnline }
                .thenByDescending { it.lastSeenMs }
        )

        deviceAdapter.submitList(rows)
    }

    /**
     * Updates the "Checked in: X / Y" counter and the list of checked-in
     * student names below the QR code.
     */
    private fun updateCheckInStatus(names: List<String>, count: Int, rosterSize: Int) {
        tvCheckedInCount.text      = getString(R.string.check_in_count_fmt, count, rosterSize)
        tvCheckedInNames.text      =
            if (names.isEmpty()) getString(R.string.check_in_names_empty)
            else names.joinToString(", ")
        // Note: do NOT touch tvOccupancy here — that card reflects actively
        // connected devices (driven by BROADCAST_DEVICES_UPDATE). The check-in
        // count is its own stat above the QR.
    }

    /**
     * Asks the running service to re-broadcast its current state.
     * The response arrives as a LocalBroadcast which is already handled
     * by serviceReceiver registered in registerReceivers().
     */
    private fun requestServiceState() {
        val intent = Intent(this, RouterForegroundService::class.java).apply {
            action = RouterForegroundService.ACTION_REQUEST_STATE
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}

// -------------------------------------------------------------------------
// Connected Devices list adapter
// -------------------------------------------------------------------------

/**
 * UI row for a single WiFi-connected client.
 * [displayName] is best-effort (from reverse DNS / mDNS); if the device
 * doesn't advertise a name we fall back to IP, then to the token prefix.
 */
data class DeviceRow(
    val token: String,
    val displayName: String,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val isOnline: Boolean
)

class ConnectedDeviceAdapter :
    RecyclerView.Adapter<ConnectedDeviceAdapter.DeviceViewHolder>() {

    private var devices: List<DeviceRow> = emptyList()

    fun submitList(newDevices: List<DeviceRow>) {
        devices = newDevices
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(devices[position])
    }

    override fun getItemCount(): Int = devices.size

    class DeviceViewHolder(itemView: android.view.View) :
        RecyclerView.ViewHolder(itemView) {

        private val tvDot      : TextView = itemView.findViewById(R.id.tvStatusDot)
        private val tvToken    : TextView = itemView.findViewById(R.id.tvDeviceToken)
        private val tvJoined   : TextView = itemView.findViewById(R.id.tvDeviceJoined)
        private val tvStatus   : TextView = itemView.findViewById(R.id.tvDeviceStatus)
        private val tvLastSeen : TextView = itemView.findViewById(R.id.tvDeviceLastSeen)

        fun bind(d: DeviceRow) {
            val ctx = itemView.context

            // displayName is already sanitised in HotspotManager — no IPs here.
            tvToken.text = d.displayName.ifBlank { "Device ${d.token.take(4).uppercase()}" }
            tvJoined.text  = "joined ${formatClock(d.firstSeenMs)}"
            tvLastSeen.text = "last seen ${formatRelative(d.lastSeenMs)}"

            if (d.isOnline) {
                tvDot.setTextColor(ctx.getColor(R.color.colorSuccess))
                tvStatus.text = "ONLINE"
                tvStatus.setBackgroundColor(ctx.getColor(R.color.colorSuccess))
            } else {
                tvDot.setTextColor(ctx.getColor(R.color.colorTextSecondary))
                tvStatus.text = "OFFLINE"
                tvStatus.setBackgroundColor(ctx.getColor(R.color.colorTextSecondary))
            }
        }

        private fun formatClock(ms: Long): String {
            if (ms <= 0L) return "—"
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
            return "%02d:%02d:%02d".format(
                cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE),
                cal.get(java.util.Calendar.SECOND)
            )
        }

        private fun formatRelative(ms: Long): String {
            if (ms <= 0L) return "—"
            val diffSec = (System.currentTimeMillis() - ms) / 1000
            return when {
                diffSec < 5         -> "just now"
                diffSec < 60        -> "${diffSec}s ago"
                diffSec < 3600      -> "${diffSec / 60}m ago"
                else                -> "${diffSec / 3600}h ago"
            }
        }
    }
}
