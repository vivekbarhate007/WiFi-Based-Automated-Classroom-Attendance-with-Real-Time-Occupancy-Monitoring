package com.attendance.router

import android.util.Log
import com.attendance.router.db.PresenceDao
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException

/**
 * UDP server that listens on port 9876 for heartbeat packets from student phones.
 *
 * Heartbeat JSON format:
 *   { "token": "uuid-string", "session_id": "abc123", "timestamp": 1234567890 }
 *
 * The server validates the token (must be a valid UUID), verifies the session_id matches
 * the active session, and upserts the presence log in the database.
 *
 * IP addresses from incoming packets are NEVER stored or logged.
 */
class HeartbeatServer(
    private val presenceDao: PresenceDao,
    private val getActiveSessionId: () -> String?
) {

    companion object {
        private const val TAG = "HeartbeatServer"
        const val UDP_PORT = 9876
        private const val BUFFER_SIZE = 1024
        private const val MAX_PACKET_AGE_MS = 60_000L  // reject packets older than 60s
    }

    private data class HeartbeatPacket(
        val token: String = "",
        val session_id: String = "",
        val timestamp: Long = 0L
    )

    private val gson = Gson()
    private var serverJob: Job? = null
    private var socket: DatagramSocket? = null

    // Callback for received valid heartbeats (used by service to update UI)
    var onHeartbeatReceived: ((token: String, sessionId: String) -> Unit)? = null

    /**
     * Starts the UDP server in a background coroutine.
     * Runs until [stop] is called.
     */
    fun start(scope: CoroutineScope) {
        if (serverJob?.isActive == true) {
            Log.w(TAG, "Server is already running")
            return
        }

        serverJob = scope.launch(Dispatchers.IO) {
            try {
                DatagramSocket(UDP_PORT).use { sock ->
                    socket = sock
                    Log.i(TAG, "UDP heartbeat server listening on port $UDP_PORT")

                    val buffer = ByteArray(BUFFER_SIZE)
                    val packet = DatagramPacket(buffer, buffer.size)

                    while (isActive) {
                        try {
                            sock.receive(packet)
                            // Extract payload BEFORE we do anything with the source address
                            val payload = String(packet.data, 0, packet.length, Charsets.UTF_8)
                            // Do NOT log or store packet.address (the student's IP)
                            processPacket(payload)
                        } catch (e: SocketException) {
                            if (isActive) {
                                Log.e(TAG, "Socket error while receiving: ${e.message}")
                            }
                            // If the socket was closed intentionally (stop() call), exit quietly
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing packet: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fatal error in HeartbeatServer: ${e.message}")
            } finally {
                socket = null
                Log.i(TAG, "UDP server stopped")
            }
        }
    }

    /**
     * Stops the UDP server by closing the socket and cancelling the job.
     */
    fun stop() {
        socket?.close()
        serverJob?.cancel()
        serverJob = null
        Log.i(TAG, "HeartbeatServer stop requested")
    }

    /**
     * Parses and validates a raw UDP payload, then upserts the presence log.
     */
    private suspend fun processPacket(payload: String) {
        val heartbeat = try {
            gson.fromJson(payload, HeartbeatPacket::class.java)
        } catch (e: JsonSyntaxException) {
            Log.w(TAG, "Malformed heartbeat JSON, dropping packet")
            return
        }

        // Validate token format (must be a UUID)
        if (!TokenHasher.isValidUuidToken(heartbeat.token)) {
            Log.w(TAG, "Invalid token format, dropping packet")
            return
        }

        // Validate session_id is non-empty
        if (heartbeat.session_id.isBlank()) {
            Log.w(TAG, "Missing session_id, dropping packet")
            return
        }

        // Check the session_id matches the currently active session
        val activeSessionId = getActiveSessionId()
        if (activeSessionId == null || heartbeat.session_id != activeSessionId) {
            Log.w(TAG, "session_id mismatch (expected=$activeSessionId, got=${heartbeat.session_id}), dropping")
            return
        }

        // Reject packets with timestamps too far in the past (replay protection)
        val nowMs = System.currentTimeMillis()
        val packetTimeMs = heartbeat.timestamp * 1000L  // packet timestamp is in seconds
        if (nowMs - packetTimeMs > MAX_PACKET_AGE_MS) {
            Log.w(TAG, "Stale heartbeat packet (age=${(nowMs - packetTimeMs) / 1000}s), dropping")
            return
        }

        // All checks passed — upsert presence log
        withContext(Dispatchers.IO) {
            presenceDao.upsertPresence(
                token = heartbeat.token,
                sessionId = heartbeat.session_id,
                nowMs = nowMs
            )
        }

        Log.d(TAG, "Heartbeat accepted: token=${heartbeat.token.take(8)}..., session=${heartbeat.session_id}")
        onHeartbeatReceived?.invoke(heartbeat.token, heartbeat.session_id)
    }

    fun isRunning(): Boolean = serverJob?.isActive == true
}
