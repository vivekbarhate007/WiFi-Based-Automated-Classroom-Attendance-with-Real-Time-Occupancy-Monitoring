package com.attendance.router

import android.util.Log
import com.attendance.router.db.CheckIn
import com.attendance.router.db.CheckInDao
import com.attendance.router.db.PresenceDao
import com.attendance.router.db.PresenceLog
import com.attendance.router.db.RosterDao
import com.attendance.router.db.SessionDao
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.net.URLEncoder

/**
 * Tiny HTTP server bound to port 8080 on the hotspot interface.
 *
 * Endpoints:
 *   GET  /           → check-in form (roll-number input, captive portal)
 *   POST /checkin    → validates against roster, inserts check_in row
 *   POST /heartbeat  → JS keep-alive from the success page {token, session_id}
 *   GET  /session    → active session JSON
 *   GET  /success    → confirmation page (with JS keep-alive script)
 *   GET  /ping       → diagnostic
 *
 * Runs in its own thread pool (NanoHTTPD default). All DB access is
 * bridged from those threads via runBlocking because Room's suspend
 * APIs can't be invoked directly from non-coroutine threads.
 */
class CheckInHttpServer(
    bindHost: String?,
    port: Int,
    private val rosterDao: RosterDao,
    private val checkInDao: CheckInDao,
    private val presenceDao: PresenceDao,
    private val sessionDao: SessionDao,
    private val getActiveSessionId: () -> String?,
    private val getSessionSalt: () -> String,
    private val onCheckIn: () -> Unit
) : NanoHTTPD(bindHost, port) {

    // Convenience ctor that binds to every interface (0.0.0.0).
    constructor(
        port: Int,
        rosterDao: RosterDao,
        checkInDao: CheckInDao,
        presenceDao: PresenceDao,
        sessionDao: SessionDao,
        getActiveSessionId: () -> String?,
        getSessionSalt: () -> String,
        onCheckIn: () -> Unit
    ) : this(null, port, rosterDao, checkInDao, presenceDao, sessionDao, getActiveSessionId, getSessionSalt, onCheckIn)

    companion object {
        private const val TAG = "CheckInHttpServer"
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        Log.d(TAG, "$method $uri from ${session.remoteIpAddress}")

        return try {
            when {
                uri == "/" && method == Method.GET              -> serveForm(error = null)
                uri == "/checkin" && method == Method.POST      -> handleCheckIn(session)
                uri == "/heartbeat" && method == Method.POST    -> handleJsHeartbeat(session)
                uri == "/session" && method == Method.GET       -> serveActiveSession()
                uri == "/success" && method == Method.GET        -> serveSuccess(session.parameters["name"]?.firstOrNull())
                // Diagnostic endpoint — hit http://<hotspot-ip>:8080/ping from a
                // connected student phone to confirm the server is reachable.
                uri == "/ping" && method == Method.GET      -> newFixedLengthResponse(
                    Response.Status.OK, "text/plain; charset=utf-8",
                    "pong\nserver-time=${System.currentTimeMillis()}\nclient-ip=${session.remoteIpAddress}\n"
                )
                // Captive-portal probe endpoints (Android/iOS/Windows). Respond with
                // an empty 204 so the OS shows "connected" immediately instead of
                // trying to redirect the user away. Some OSes treat this as a hint
                // that there's no true internet, which is what we want.
                uri.startsWith("/generate_204") ||
                uri.startsWith("/gen_204") ||
                uri.startsWith("/ncsi.txt") ||
                uri.startsWith("/hotspot-detect") ||
                uri.startsWith("/connecttest") ||
                uri.startsWith("/success.txt")          -> newFixedLengthResponse(
                    Response.Status.NO_CONTENT, "text/plain", ""
                )
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404")
            }
        } catch (e: Exception) {
            Log.e(TAG, "serve error: ${e.message}", e)
            htmlPage("Server Error", "Something went wrong. Please try again.", Response.Status.INTERNAL_ERROR)
        }
    }

    // -------------------------------------------------------------------------
    // Endpoints
    // -------------------------------------------------------------------------

    private fun serveForm(error: String?): Response {
        val errorHtml = error?.let { "<p class='err'>$it</p>" } ?: ""
        val html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Classroom Check-in</title>
                <style>
                    * { box-sizing: border-box; }
                    body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                           margin: 0; padding: 24px;
                           background: linear-gradient(135deg, #1976d2, #42a5f5);
                           min-height: 100vh; display: flex; align-items: center; justify-content: center; }
                    .card { background: #fff; padding: 32px 24px; border-radius: 16px;
                            max-width: 400px; width: 100%; box-shadow: 0 10px 30px rgba(0,0,0,.2); }
                    h1 { margin: 0 0 8px; color: #1976d2; font-size: 28px; }
                    p.sub { margin: 0 0 24px; color: #555; font-size: 15px; }
                    label { display: block; margin-bottom: 8px; font-weight: 600; color: #333; }
                    input { width: 100%; padding: 14px 16px; font-size: 18px;
                            border: 2px solid #ddd; border-radius: 8px;
                            -webkit-appearance: none; }
                    input:focus { border-color: #1976d2; outline: none; }
                    button { width: 100%; padding: 16px; font-size: 17px; font-weight: 600;
                             background: #1976d2; color: #fff; border: 0; border-radius: 8px;
                             margin-top: 16px; cursor: pointer; }
                    button:active { background: #0d47a1; }
                    .err { color: #b71c1c; background: #ffebee; padding: 12px; border-radius: 8px;
                           margin-bottom: 16px; font-size: 14px; }
                    .foot { text-align: center; font-size: 12px; color: #888; margin-top: 20px; }
                </style>
            </head>
            <body>
                <div class="card">
                    <h1>Mark Attendance</h1>
                    <p class="sub">Enter your roll number to check in for this class.</p>
                    $errorHtml
                    <form method="POST" action="/checkin" autocomplete="off">
                        <label for="rollNo">Roll Number</label>
                        <input id="rollNo" name="rollNo" type="text" required autofocus
                               inputmode="text" autocapitalize="characters">
                        <button type="submit">Check In</button>
                    </form>
                    <div class="foot">Classroom Attendance System</div>
                </div>
            </body>
            </html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun handleCheckIn(session: IHTTPSession): Response {
        // Parse form body. NanoHTTPD requires parseBody() before reading parameters for POST.
        val files = HashMap<String, String>()
        try {
            session.parseBody(files)
        } catch (e: Exception) {
            return serveForm(error = "Could not read form submission.")
        }

        val rollNo = session.parameters["rollNo"]?.firstOrNull()?.trim()?.uppercase()
            ?: return serveForm(error = "Missing roll number.")

        if (rollNo.isBlank()) {
            return serveForm(error = "Roll number cannot be empty.")
        }

        val sessionId = getActiveSessionId()
            ?: return serveForm(error = "No active class session. Wait for your teacher to start one.")

        // Validate against roster. runBlocking is safe here — NanoHTTPD workers
        // are separate threads dedicated to request handling.
        val student = runBlocking { rosterDao.getByRollNo(rollNo) }
            ?: return serveForm(error = "Roll number '$rollNo' is not in the class roster.")

        // Build an anonymised token from the student's IP + session salt.
        // IP itself is never persisted.
        val clientIp = session.remoteIpAddress ?: "unknown"
        val token = TokenHasher.anonymize(clientIp, getSessionSalt())

        // Guard 1: one check-in per device per session.
        // Prevents a student from typing multiple roll numbers from the same phone.
        val existingByToken = runBlocking { checkInDao.getBySessionAndToken(sessionId, token) }
        if (existingByToken != null) {
            return serveForm(error = "This device has already been used to check in for this session.")
        }

        // Guard 2: one check-in per roll number per session.
        // Prevents two devices from checking in the same student.
        val existingByRoll = runBlocking { checkInDao.getBySessionAndRollNo(sessionId, rollNo) }
        if (existingByRoll != null) {
            return serveForm(error = "Roll number '$rollNo' has already been checked in for this session.")
        }

        runBlocking {
            checkInDao.insert(
                CheckIn(
                    sessionId   = sessionId,
                    rollNo      = rollNo,
                    token       = token,
                    checkedInAt = System.currentTimeMillis()
                )
            )
        }

        Log.i(TAG, "Check-in OK: $rollNo → ${student.name} (session=$sessionId)")
        onCheckIn()

        // 302 redirect to the success page — pass token + sessionId so the JS
        // heartbeat on that page can send keep-alives without re-entering details.
        val encodedName = URLEncoder.encode(student.name, "UTF-8")
        return newFixedLengthResponse(Response.Status.REDIRECT, "text/html", "").apply {
            addHeader("Location", "/success?name=$encodedName&token=$token&sid=$sessionId")
        }
    }

    private fun serveSuccess(name: String?): Response {
        val displayName = name?.ifBlank { "Student" } ?: "Student"
        val escaped     = htmlEscape(displayName)
        val html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Checked In</title>
                <style>
                    * { box-sizing: border-box; }
                    body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                           margin: 0; padding: 24px;
                           background: linear-gradient(135deg, #2e7d32, #66bb6a);
                           min-height: 100vh; display: flex; align-items: center; justify-content: center; }
                    .card { background: #fff; padding: 32px 24px; border-radius: 16px;
                            max-width: 400px; width: 100%; text-align: center;
                            box-shadow: 0 10px 30px rgba(0,0,0,.2); }
                    .tick { font-size: 72px; margin-bottom: 8px; }
                    h1 { margin: 0 0 8px; color: #2e7d32; font-size: 26px; }
                    .name { font-size: 22px; font-weight: 600; color: #333; margin: 16px 0; }
                    p { color: #555; font-size: 15px; line-height: 1.5; }
                    .status { font-size: 12px; color: #aaa; margin-top: 16px; }
                </style>
            </head>
            <body>
                <div class="card">
                    <div class="tick">✓</div>
                    <h1>You're checked in</h1>
                    <div class="name">$escaped</div>
                    <p>Keep this page open and stay connected to the classroom Wi-Fi.
                       Your attendance is being tracked automatically.</p>
                    <p class="status" id="hbStatus">Attendance active…</p>
                </div>
                <script>
                  // Read token + session from URL params (set by /checkin redirect)
                  var params = new URLSearchParams(window.location.search);
                  var token  = params.get('token') || '';
                  var sid    = params.get('sid')   || '';
                  var count  = 0;

                  function sendHeartbeat() {
                    if (!token || !sid) return;
                    fetch('/heartbeat', {
                      method: 'POST',
                      headers: {'Content-Type': 'application/json'},
                      body: JSON.stringify({token: token, session_id: sid})
                    }).then(function(r) {
                      count++;
                      document.getElementById('hbStatus').textContent =
                        'Attendance active · ' + count + ' check' + (count === 1 ? '' : 's') + ' sent';
                    }).catch(function() {
                      document.getElementById('hbStatus').textContent = 'Reconnecting…';
                    });
                  }

                  // Send immediately on load, then every 30 seconds
                  sendHeartbeat();
                  setInterval(sendHeartbeat, 30000);
                </script>
            </body>
            </html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    // -------------------------------------------------------------------------
    // JS keep-alive heartbeat
    // -------------------------------------------------------------------------

    /**
     * POST /heartbeat
     * Body (JSON): {"token":"<hash>","session_id":"<id>"}
     *
     * Called every 30 s by the JavaScript on the captive-portal success page.
     * Upserts a PresenceLog row so [AttendanceEngine] can measure how long
     * the student's browser stayed on the success page.
     *
     * This is the application-layer complement to the IP-layer ping sweep:
     * ping sweep detects WiFi connectivity; JS heartbeat detects the student
     * actually has the check-in page open (stronger signal of physical presence).
     */
    private fun handleJsHeartbeat(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        try { session.parseBody(files) } catch (e: Exception) {
            return jsonError(400, "Could not read body")
        }

        val body = files["postData"] ?: return jsonError(400, "Empty body")

        data class HbPayload(val token: String = "", val session_id: String = "")
        val payload = try {
            Gson().fromJson(body, HbPayload::class.java)
        } catch (e: Exception) {
            return jsonError(400, "Invalid JSON")
        }

        val token     = payload?.token?.trim() ?: ""
        val sessionId = payload?.session_id?.trim() ?: ""

        if (token.isBlank() || sessionId.isBlank())
            return jsonError(400, "token and session_id required")

        val activeId = getActiveSessionId()
        if (activeId == null || activeId != sessionId)
            return jsonError(409, "No active session matching that session_id")

        val nowMs = System.currentTimeMillis()
        runBlocking { presenceDao.upsertPresence(token, sessionId, nowMs) }

        Log.v(TAG, "JS heartbeat OK: token=${token.take(8)}… session=$sessionId")
        return newFixedLengthResponse(
            Response.Status.OK, "application/json", """{"status":"ok"}"""
        )
    }

    // -------------------------------------------------------------------------
    // Session / Student App endpoints (kept for curl/debug use)
    // -------------------------------------------------------------------------

    /**
     * GET /session
     * Returns the active session as JSON, or 204 if none is active.
     * Used by the Student App to discover the session_id without manual input.
     */
    private fun serveActiveSession(): Response {
        val sessionId = getActiveSessionId()
            ?: return newFixedLengthResponse(Response.Status.NO_CONTENT, "application/json", "")

        val session = runBlocking { sessionDao.getById(sessionId) }
            ?: return newFixedLengthResponse(Response.Status.NO_CONTENT, "application/json", "")

        val json = """{"session_id":"${session.sessionId}","course_name":"${session.courseName}","start_time":${session.startTime}}"""
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json)
    }

    /**
     * POST /student-enroll
     * Body (JSON): {"roll_no":"CS001","token":"<uuid>","session_id":"<id>"}
     *
     * Unlike the captive portal which derives the token from the client IP,
     * this endpoint trusts the token the Student App provides. This means the
     * token is stable across IP changes (DHCP re-assignment during a session).
     */
    private fun handleStudentEnroll(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        try { session.parseBody(files) } catch (e: Exception) {
            return jsonError(400, "Could not read request body")
        }

        // Body is in files["postData"] for NanoHTTPD JSON posts
        val body = files["postData"] ?: return jsonError(400, "Empty body")

        data class EnrollPayload(val roll_no: String = "", val token: String = "", val session_id: String = "")
        val payload = try {
            Gson().fromJson(body, EnrollPayload::class.java)
        } catch (e: Exception) {
            return jsonError(400, "Invalid JSON")
        }

        val rollNo    = payload?.roll_no?.trim()?.uppercase() ?: ""
        val token     = payload?.token?.trim() ?: ""
        val sessionId = payload?.session_id?.trim() ?: ""

        if (rollNo.isBlank() || token.isBlank() || sessionId.isBlank())
            return jsonError(400, "roll_no, token, and session_id are required")

        if (!TokenHasher.isValidUuidToken(token))
            return jsonError(400, "token must be a valid UUID")

        val activeId = getActiveSessionId()
        if (activeId == null || activeId != sessionId)
            return jsonError(409, "session_id does not match the active session")

        val student = runBlocking { rosterDao.getByRollNo(rollNo) }
            ?: return jsonError(404, "Roll number '$rollNo' is not in the class roster")

        runBlocking {
            checkInDao.insert(
                CheckIn(
                    sessionId   = sessionId,
                    rollNo      = rollNo,
                    token       = token,
                    checkedInAt = System.currentTimeMillis()
                )
            )
        }

        Log.i(TAG, "Student-app enroll OK: $rollNo → ${student.name} (session=$sessionId, token=${token.take(8)}…)")
        onCheckIn()

        val json = """{"status":"ok","name":"${student.name}","roll_no":"$rollNo"}"""
        return newFixedLengthResponse(Response.Status.CREATED, "application/json; charset=utf-8", json)
    }

    private fun jsonError(code: Int, message: String): Response {
        val status = when (code) {
            400 -> Response.Status.BAD_REQUEST
            404 -> Response.Status.NOT_FOUND
            409 -> Response.Status.CONFLICT
            else -> Response.Status.INTERNAL_ERROR
        }
        val json = """{"error":"$message"}"""
        return newFixedLengthResponse(status, "application/json; charset=utf-8", json)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun htmlPage(title: String, body: String, status: Response.Status): Response {
        val html = "<!DOCTYPE html><html><head><title>$title</title></head>" +
                   "<body><h1>$title</h1><p>${htmlEscape(body)}</p></body></html>"
        return newFixedLengthResponse(status, "text/html; charset=utf-8", html)
    }

    private fun htmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
