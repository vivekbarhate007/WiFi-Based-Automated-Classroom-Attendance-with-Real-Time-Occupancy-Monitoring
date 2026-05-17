package com.attendance.router

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Manages a Local-Only WiFi Hotspot and detects clients connected to it.
 *
 * Detection strategy:
 *   On Android 10+ apps can no longer read /proc/net/arp (kernel returns
 *   empty contents to non-system UIDs). Instead we ping-sweep the hotspot
 *   subnet. Every IP that answers within the timeout is a connected client.
 *   We also try a reverse-DNS lookup to display a human-friendly name; if
 *   the device doesn't advertise a hostname we fall back to its IP.
 *
 * Privacy note: only an IP-based anonymized token is stored in the session
 * cumulative map. The raw IP and display name flow through the broadcast
 * for UI display but are not written to the Room DB.
 */
class HotspotManager(private val context: Context) {

    companion object {
        private const val TAG = "HotspotManager"

        /** How often we rescan the subnet during an active class session. */
        private const val SCAN_INTERVAL_MS = 5_000L

        /** Slower scan interval used when no session is running (saves battery). */
        private const val SCAN_INTERVAL_IDLE_MS = 60_000L

        /** Max concurrent pings. 32 is plenty for a /24 and avoids fd exhaustion. */
        private const val PING_CONCURRENCY = 32

        /** Per-ping timeout (seconds). Phones in Wi-Fi power-save can take
         *  several hundred ms to wake up and reply. */
        private const val PING_TIMEOUT_SEC = 2

        /** How many ping attempts before we decide the host is really gone.
         *  First scan of a new IP uses just 1 for speed; subsequent checks
         *  of a previously-seen device use the full retry budget. */
        private const val PING_RETRY_COUNT = 2

        /** A client not seen for more than this is marked OFFLINE.
         *  Generous (~60s) so a single missed scan from Wi-Fi power-save
         *  doesn't make the UI flip between online / offline. */
        private const val STALE_THRESHOLD_MS = 60_000L

        /** Max RTT samples kept per device for the rolling average. */
        private const val MAX_RTT_SAMPLES = 10

        /**
         * Number of consecutive scan cycles with no ping response before a
         * device is treated as departed. At 5 s per scan this is ~15 s of
         * silence before we act — long enough to absorb power-save wake-up
         * delays and brief channel congestion, short enough to detect a real
         * departure within one 30-s heartbeat window.
         */
        private const val CONSECUTIVE_MISS_THRESHOLD = 3

        /** A single subnet sweep that reports more live hosts than this is
         *  rejected wholesale — it indicates we're scanning the wrong network
         *  (e.g. we locked onto the mobile-data gateway or a VPN tunnel).
         *  Realistic classroom sizes top out well below this. */
        private const val MAX_PLAUSIBLE_DEVICES = 100
    }

    /**
     * Snapshot of a single WiFi-connected device seen during the current session.
     *
     * @param token        SHA-256(ip + session salt) — opaque, stable per session
     * @param ipAddress    DHCP-assigned address like "192.168.43.47"
     * @param displayName  reverse-DNS name (e.g. "Vivek-iPhone.local"), else the IP
     * @param firstSeenMs  epoch ms when this IP first answered ping in this session
     * @param lastSeenMs   epoch ms of the most recent successful ping
     * @param currentlyConnected  true if the last ping was within [STALE_THRESHOLD_MS]
     */
    data class ConnectedDevice(
        val token: String,
        val ipAddress: String,
        val displayName: String,
        val firstSeenMs: Long,
        val lastSeenMs: Long,
        val currentlyConnected: Boolean,
        /** Median ping RTT in ms over the last [MAX_RTT_SAMPLES] scans.
         *  Lower = stronger signal / closer to hotspot. null if no samples yet. */
        val medianRttMs: Long?
    )

    /** Internal per-IP bookkeeping. */
    private data class Record(
        val token: String,
        var displayName: String,
        val firstSeenMs: Long,
        var lastSeenMs: Long,
        /** Consecutive scan cycles where this IP did NOT respond to ping.
         *  Incremented each miss, reset to 0 on any successful ping. */
        var consecutiveMisses: Int = 0,
        /** Rolling list of the last [MAX_RTT_SAMPLES] successful ping RTTs in ms.
         *  Used to compute a signal quality score (lower RTT → closer/stronger). */
        val rttSamplesMs: ArrayDeque<Long> = ArrayDeque(MAX_RTT_SAMPLES)
    )

    private val wifiManager: WifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var currentSsid: String? = null
    private var currentPassphrase: String? = null

    private var scanJob: Job? = null

    /** Called the first time each new IP is detected in the current session. */
    var onArpTokenDetected: ((anonymizedToken: String) -> Unit)? = null

    /** Called after every scan with the full cumulative list. */
    var onDevicesUpdated: ((devices: List<ConnectedDevice>) -> Unit)? = null

    /** Per-session salt used for anonymization. */
    var sessionSalt: String = TokenHasher.generateSessionSalt()

    /**
     * Adaptive sampling: when false (no active session) the scan interval
     * slows to [SCAN_INTERVAL_IDLE_MS] to save battery. Call
     * [setSessionActive] when a session starts/ends.
     */
    @Volatile private var sessionActive: Boolean = false

    fun setSessionActive(active: Boolean) {
        if (sessionActive == active) return
        sessionActive = active
        Log.d(TAG, "Adaptive sampling: session active = $active " +
                "(interval → ${if (active) SCAN_INTERVAL_MS else SCAN_INTERVAL_IDLE_MS} ms)")
        // Restart the scan loop so the new interval takes effect immediately.
        if (scanJob?.isActive == true) {
            startScanning()
        }
    }

    /** Cumulative map, keyed by IP. */
    private val seenDevices = linkedMapOf<String, Record>()

    // -------------------------------------------------------------------------
    // Hotspot lifecycle
    // -------------------------------------------------------------------------

    fun startHotspot(
        onStarted: (ssid: String, passphrase: String) -> Unit,
        onFailed: (reason: Int) -> Unit
    ) {
        if (hotspotReservation != null) {
            val info = getHotspotInfo()
            if (info != null) onStarted(info.first, info.second)
            return
        }

        val mainHandler = Handler(Looper.getMainLooper())

        wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                hotspotReservation = reservation

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val config = reservation.softApConfiguration
                    currentSsid = config.ssid ?: "ClassroomNet"
                    currentPassphrase = config.passphrase ?: "classroom123"
                } else {
                    @Suppress("DEPRECATION")
                    val config = reservation.wifiConfiguration
                    currentSsid = config?.SSID ?: "ClassroomNet"
                    currentPassphrase = config?.preSharedKey ?: "classroom123"
                }

                Log.i(TAG, "Hotspot started: SSID=$currentSsid")

                mainHandler.post {
                    onStarted(currentSsid!!, currentPassphrase!!)
                }

                startScanning()
            }

            override fun onStopped() {
                Log.i(TAG, "Hotspot stopped")
                hotspotReservation = null
                currentSsid = null
                currentPassphrase = null
                stopScanning()
            }

            override fun onFailed(reason: Int) {
                Log.e(TAG, "Hotspot failed: reason=$reason")
                mainHandler.post { onFailed(reason) }
            }
        }, mainHandler)
    }

    fun stopHotspot() {
        stopScanning()
        hotspotReservation?.close()
        hotspotReservation = null
        currentSsid = null
        currentPassphrase = null
        seenDevices.clear()
        Log.i(TAG, "Hotspot stopped by user")
    }

    fun getHotspotInfo(): Pair<String, String>? {
        val ssid = currentSsid
        val pass = currentPassphrase
        return if (ssid != null && pass != null) Pair(ssid, pass) else null
    }

    fun isRunning(): Boolean = hotspotReservation != null

    @Synchronized
    fun snapshotDevices(): List<ConnectedDevice> {
        val now = System.currentTimeMillis()
        return seenDevices.map { (ip, r) ->
            val sorted = r.rttSamplesMs.sorted()
            val median = if (sorted.isEmpty()) null else sorted[sorted.size / 2]
            ConnectedDevice(
                token              = r.token,
                ipAddress          = ip,
                displayName        = r.displayName,
                firstSeenMs        = r.firstSeenMs,
                lastSeenMs         = r.lastSeenMs,
                currentlyConnected = (now - r.lastSeenMs) <= STALE_THRESHOLD_MS,
                medianRttMs        = median
            )
        }
    }

    @Synchronized
    fun resetSessionSalt() {
        sessionSalt = TokenHasher.generateSessionSalt()
        seenDevices.clear()
        Log.d(TAG, "Session salt reset; device list cleared")
    }

    // -------------------------------------------------------------------------
    // Subnet scan loop
    // -------------------------------------------------------------------------

    private fun startScanning() {
        stopScanning()
        scanJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    scanSubnet()
                } catch (e: Exception) {
                    Log.e(TAG, "Subnet scan error: ${e.message}")
                }
                onDevicesUpdated?.invoke(snapshotDevices())
                delay(if (sessionActive) SCAN_INTERVAL_MS else SCAN_INTERVAL_IDLE_MS)
            }
        }
        Log.d(TAG, "Subnet scanning started")
    }

    private fun stopScanning() {
        scanJob?.cancel()
        scanJob = null
    }

    /**
     * Pings every IP in the hotspot subnet in parallel. Every responding host
     * is upserted into [seenDevices]. Also consults the kernel ARP/neighbour
     * table as a second source so we don't miss devices in Wi-Fi power-save.
     */
    private suspend fun scanSubnet() {
        // Only sweep when we have a *real* soft-AP interface. In shared-
        // network mode (teacher is a WiFi client on the classroom SSID) we
        // must not ping-sweep the network — it's not ours, and random hosts
        // showing up as "students" is exactly how we got 242 ghosts.
        val hotspotIp = findHotspotIp()
        if (hotspotIp == null) {
            Log.d(TAG, "Not in soft-AP mode — skipping subnet scan")
            return
        }

        val subnet = hotspotIp.substringBeforeLast('.')
        val selfLast = hotspotIp.substringAfterLast('.').toIntOrNull() ?: -1
        val now = System.currentTimeMillis()
        val semaphore = Semaphore(PING_CONCURRENCY)

        // IPs we've already seen in this session — give them a larger retry
        // budget so Wi-Fi power-save phones don't briefly look offline.
        val knownIps = synchronized(this) { seenDevices.keys.toSet() }

        // Read the kernel neighbour table once per scan — this finds phones
        // that are connected-but-asleep and don't answer ICMP.
        val arpHits = readArpNeighbours(subnet)

        coroutineScope {
            val responding = (1..254)
                .filter { it != selfLast }
                .map { i ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val ip = "$subnet.$i"
                            val tries = if (ip in knownIps) PING_RETRY_COUNT else 1
                            val rtt = pingIp(ip, attempts = tries)
                            if (rtt != null) Pair(ip, rtt) else null
                        }
                    }
                }
                .awaitAll()
                .filterNotNull()

            // responding is List<Pair<String,Long>> (ip, rttMs)
            // arpHits is Set<String> — merge into a map of ip → rttMs (null for ARP-only hits)
            val rttByIp: Map<String, Long> = responding.associate { (ip, rtt) -> ip to rtt }
            val allAliveIps: Set<String>   = (rttByIp.keys + arpHits).toSet()

            // Sanity cap
            if (allAliveIps.size > MAX_PLAUSIBLE_DEVICES) {
                Log.w(TAG, "Rejecting scan — ${allAliveIps.size} live hosts on $subnet.0/24 " +
                    "(cap=$MAX_PLAUSIBLE_DEVICES). Likely wrong interface; not broadcasting.")
                return@coroutineScope
            }

            // Reverse lookups in parallel
            val withNames = allAliveIps.map { ip ->
                async(Dispatchers.IO) {
                    semaphore.withPermit { Triple(ip, reverseLookup(ip), rttByIp[ip]) }
                }
            }.awaitAll()

            for ((ip, name, rtt) in withNames) {
                upsertDevice(ip, name, now, rtt)
            }

            // Increment miss counters for IPs not seen this sweep; prune departed devices.
            synchronized(this@HotspotManager) {
                updateMissesAndPrune(allAliveIps)
            }
        }

        Log.d(TAG, "Scan complete — cumulative=${seenDevices.size}, arpHits=${arpHits.size}")
    }

    /**
     * Liveness probe via ICMP.
     *
     * NOTE: an earlier version also treated TCP "connection refused" (RST)
     * as "alive". That turned out to be far too permissive — on subnets we
     * shouldn't be scanning at all (carrier CGNAT, VPN tunnels), hundreds
     * of unrelated hosts RST closed ports and all showed up as "students".
     * We now rely on ICMP only; a subnet-level sanity cap in [scanSubnet]
     * guards against the "wrong interface picked" failure mode.
     *
     * `/system/bin/ping` is setuid on Android so unprivileged apps can send
     * raw ICMP without root.
     */
    /**
     * Pings [ip] and returns the round-trip time in milliseconds on success,
     * or null on failure. RTT is measured wall-clock around the ping process
     * (includes process startup overhead ~5 ms, acceptable for our purposes).
     */
    private fun pingIp(ip: String, attempts: Int = 1): Long? {
        return try {
            val t0 = System.currentTimeMillis()
            val proc = Runtime.getRuntime().exec(
                arrayOf(
                    "/system/bin/ping",
                    "-c", attempts.toString(),
                    "-W", PING_TIMEOUT_SEC.toString(),
                    ip
                )
            )
            val exitCode = proc.waitFor()
            if (exitCode == 0) System.currentTimeMillis() - t0 else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Reads reachable neighbours from the kernel via `ip neigh show`. This
     * works on Android 10+ even though `/proc/net/arp` is restricted — the
     * `ip` binary talks to the kernel over a netlink socket that's allowed
     * for unprivileged apps.
     *
     * Returns every IPv4 address whose state isn't FAILED and that sits in
     * the same /24 as the hotspot.
     */
    private fun readArpNeighbours(subnetPrefix: String): List<String> {
        val hits = mutableListOf<String>()
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("/system/bin/ip", "neigh", "show"))
            proc.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    // Lines look like: "192.168.43.47 dev ap0 lladdr aa:bb:cc:dd:ee:ff REACHABLE"
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.isEmpty()) continue
                    val ip = parts[0]
                    if (!ip.startsWith("$subnetPrefix.")) continue
                    val state = parts.lastOrNull() ?: continue
                    if (state == "FAILED" || state == "INCOMPLETE") continue
                    // Some entries have no lladdr yet — skip those too
                    if (!line.contains("lladdr")) continue
                    hits += ip
                }
            }
            proc.waitFor()
        } catch (e: Exception) {
            Log.d(TAG, "ip neigh failed: ${e.message}")
        }
        return hits
    }

    /**
     * Best-effort reverse DNS / mDNS lookup. Many phones advertise their name
     * via mDNS (iPhones, Macs, most modern Androids). If nothing answers we
     * simply return null and the UI falls back to the IP.
     */
    private fun reverseLookup(ip: String): String? {
        return try {
            val inet = InetAddress.getByName(ip)
            val host = inet.canonicalHostName
            // If the lookup fails, Android returns the IP verbatim — filter that out
            if (host.isNullOrBlank() || host == ip) null else host
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Finds the IPv4 address of the hotspot interface.
     *
     * Heuristic: scan all UP interfaces, pick the first one whose name looks
     * like a soft-AP (starts with "ap", "softap", "swlan", or "wlan1") and
     * whose address is in an RFC-1918 range. Falls back to any 192.168.x.1.
     */
    /**
     * Public accessor for the IP the check-in HTTP server should advertise.
     * Returns the soft-AP IP when running LocalOnlyHotspot, otherwise the
     * phone's WiFi client IP so the server still works in shared-network
     * mode (e.g. teacher + students all on the classroom WiFi).
     */
    fun getHotspotIp(): String? = findHotspotIp() ?: findWifiClientIp()

    /**
     * Public accessor for the soft-AP gateway IP ONLY. Returns null when
     * not in true hotspot mode. The subnet scanner gates on this — we
     * never ping-sweep a classroom WiFi we're just a client of.
     */
    fun getSoftApIp(): String? = findHotspotIp()

    /**
     * Returns the phone's current WiFi client IP, or null if not connected
     * as a client.
     *
     * Tries two sources because `WifiInfo.getIpAddress()` silently returns 0
     * on Android 12+ when the caller doesn't own the network callback and
     * has no location-grade permission. Falling back to
     * `NetworkInterface.getNetworkInterfaces()` uses kernel-level data and
     * doesn't hit that permission gate.
     */
    private fun findWifiClientIp(): String? {
        // 1) WifiManager (fast path; may return 0 on Android 12+)
        try {
            @Suppress("DEPRECATION")
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val wifiInfo = wm.connectionInfo
            val raw = wifiInfo?.ipAddress ?: 0
            if (raw != 0) {
                val ip = "%d.%d.%d.%d".format(
                    raw and 0xff,
                    (raw shr 8) and 0xff,
                    (raw shr 16) and 0xff,
                    (raw shr 24) and 0xff
                )
                if (isPrivateIpv4(ip) && ip != "0.0.0.0") {
                    Log.d(TAG, "WiFi client IP (WifiManager): $ip")
                    return ip
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "WifiManager client IP lookup failed: ${e.message}")
        }

        // 2) NetworkInterface fallback — look for wlan* / eth* / rmnet-skip
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            // Prefer wlan* first (WiFi client lives there on most OEMs)
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = (nif.name ?: "").lowercase()
                if (!name.startsWith("wlan")) continue
                for (addr in nif.inetAddresses.toList()) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (isPrivateIpv4(ip)) {
                            Log.d(TAG, "WiFi client IP (iface $name): $ip")
                            return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "NetworkInterface fallback failed: ${e.message}")
        }

        return null
    }

    private fun findHotspotIp(): String? {
        // Soft-AP interfaces vary wildly between OEMs. Covering the common ones:
        //   ap0 / ap1         — Pixel, stock AOSP, most Android 10+
        //   softap0           — older Samsung
        //   swlan0            — Xiaomi / MIUI
        //   wlan1             — dual-MAC chipsets, some OnePlus
        //   wifi_ap / wigig0  — Oppo / Realme
        //   tether / rndis    — USB tethering (shouldn't hit, but harmless)
        val prefs = listOf(
            "ap", "softap", "swlan", "wlan1",
            "wifi_ap", "wigig", "tether", "rndis"
        )

        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Cannot enumerate interfaces: ${e.message}")
            return null
        }

        // --- Pass 1: real soft-AP interface (true LocalOnlyHotspot mode) ----
        for (nif in interfaces) {
            if (!nif.isUp || nif.isLoopback) continue
            val name = (nif.name ?: "").lowercase()
            if (prefs.none { name.startsWith(it) }) continue
            for (addr in nif.inetAddresses.toList()) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    val ip = addr.hostAddress ?: continue
                    if (isPrivateIpv4(ip)) {
                        Log.d(TAG, "Hotspot interface: $name → $ip")
                        return ip
                    }
                }
            }
        }

        val allNames = interfaces.joinToString(",") { it.name ?: "?" }
        Log.w(TAG, "No soft-AP interface recognised. Up interfaces: $allNames")
        return null
    }

    private fun isPrivateIpv4(ip: String): Boolean {
        return ip.startsWith("192.168.") ||
               ip.startsWith("10.") ||
               (ip.startsWith("172.") &&
                    (ip.substringAfter("172.").substringBefore('.').toIntOrNull() ?: 0) in 16..31)
    }

    /**
     * Insert a new client record or refresh the lastSeen of an existing one.
     * Fires [onArpTokenDetected] only the first time a given IP appears in
     * the current session.
     *
     * NOTE: the IP is never stored in [Record.displayName] — if reverse DNS
     * failed, we fall back to a token-derived placeholder like "Device A3F2".
     * That way the IP never leaks into the UI, report, or database.
     */
    @Synchronized
    private fun upsertDevice(ip: String, name: String?, nowMs: Long, rttMs: Long? = null) {
        val existing = seenDevices[ip]
        if (existing == null) {
            val token = TokenHasher.anonymize(ip, sessionSalt)
            val displayName = sanitizeDisplayName(name, token)
            val record = Record(token = token, displayName = displayName,
                                firstSeenMs = nowMs, lastSeenMs = nowMs)
            if (rttMs != null) record.rttSamplesMs.addLast(rttMs)
            seenDevices[ip] = record
            Log.d(TAG, "New client: $displayName token=${token.take(8)}… rtt=${rttMs}ms total=${seenDevices.size}")
            onArpTokenDetected?.invoke(token)
        } else {
            existing.lastSeenMs = nowMs
            existing.consecutiveMisses = 0
            if (rttMs != null) {
                if (existing.rttSamplesMs.size >= MAX_RTT_SAMPLES) existing.rttSamplesMs.removeFirst()
                existing.rttSamplesMs.addLast(rttMs)
            }
            val candidate = sanitizeDisplayName(name, existing.token)
            if (existing.displayName.startsWith("Device ") && !candidate.startsWith("Device ")) {
                existing.displayName = candidate
            }
        }
    }

    /**
     * After each scan, increment [Record.consecutiveMisses] for every IP that
     * was NOT in [respondingIps]. Devices that cross [CONSECUTIVE_MISS_THRESHOLD]
     * are pruned from [seenDevices] and logged as departed.
     *
     * This prevents a single power-save wake-up delay from prematurely marking
     * a student as departed — they need to miss [CONSECUTIVE_MISS_THRESHOLD]
     * consecutive sweeps (= ~15 s at the default 5 s interval) before removal.
     */
    @Synchronized
    private fun updateMissesAndPrune(respondingIps: Set<String>) {
        val departed = mutableListOf<String>()
        for ((ip, record) in seenDevices) {
            if (ip !in respondingIps) {
                record.consecutiveMisses++
                if (record.consecutiveMisses >= CONSECUTIVE_MISS_THRESHOLD) {
                    departed += ip
                    Log.i(TAG, "Device departed after $CONSECUTIVE_MISS_THRESHOLD missed scans: " +
                            "${record.displayName} token=${record.token.take(8)}…")
                }
            }
        }
        departed.forEach { seenDevices.remove(it) }
    }

    /**
     * Turns a raw reverse-DNS response into a UI-safe display name.
     * - null / blank / IP-looking strings → "Device <TOKEN4>"
     * - "Vivek-iPhone.local" → "Vivek-iPhone"  (strip mDNS suffix)
     */
    private fun sanitizeDisplayName(raw: String?, token: String): String {
        val looksLikeIp = raw.isNullOrBlank() ||
            raw.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))
        if (looksLikeIp) return "Device ${token.take(4).uppercase()}"
        // Strip trailing ".local" / ".lan" / ".home" for a cleaner display
        return raw!!.substringBeforeLast('.').ifBlank { raw }
    }
}
