#!/usr/bin/env python3
"""
test_captive_portal.py — Tests the Router App's captive portal HTTP server
and the FastAPI backend's roster + check-in flows.

Two test targets:
  A. FastAPI backend  (http://<host>:8000)
     - POST /sessions         create a session
     - POST /enroll           enroll a student
     - POST /heartbeat        send presence
     - GET  /report/<id>      verify attendance
     - GET  /report/<id>/csv  verify CSV download

  B. Router App HTTP server  (http://<hotspot-ip>:8080)
     - GET  /              captive portal form renders
     - POST /checkin       form check-in flow (browser emulation)
     - GET  /session       Student App session discovery
     - POST /student-enroll Student App JSON enroll

Usage:
    # Test the FastAPI backend (must be running)
    python test_captive_portal.py --mode backend --host 127.0.0.1 --port 8000

    # Test the Router App HTTP server (must be connected to hotspot)
    python test_captive_portal.py --mode router --host 192.168.43.1 --port 8080
"""

import argparse
import sys
import time
import uuid

import requests

PASS = "PASS"
FAIL = "FAIL"
SKIP = "SKIP"


class TestRunner:
    def __init__(self):
        self.results = []

    def run(self, name: str, fn):
        try:
            result = fn()
            status = PASS if result else FAIL
        except AssertionError as e:
            status = FAIL
            result = str(e)
        except Exception as e:
            status = FAIL
            result = f"Exception: {e}"
        icon = "✓" if status == PASS else "✗"
        print(f"  {icon}  {name}")
        if status == FAIL:
            print(f"       → {result}")
        self.results.append((name, status))

    def summary(self):
        total  = len(self.results)
        passed = sum(1 for _, s in self.results if s == PASS)
        failed = total - passed
        print(f"\n  {'='*50}")
        print(f"  Results: {passed}/{total} passed", end="")
        if failed:
            print(f"  ({failed} FAILED)", end="")
        print()
        return failed == 0


# ---------------------------------------------------------------------------
# Backend tests (FastAPI)
# ---------------------------------------------------------------------------

def run_backend_tests(host: str, port: int):
    base = f"http://{host}:{port}"
    r = TestRunner()
    print(f"\n{'='*60}")
    print(f"  Backend Tests → {base}")
    print(f"{'='*60}")

    session_id = [None]   # mutable container so closures can write to it
    token      = str(uuid.uuid4())

    r.run("GET /health returns ok", lambda: (
        requests.get(f"{base}/health", timeout=3).json().get("status") == "ok"
    ))

    def create_session():
        resp = requests.post(f"{base}/sessions",
                             json={"course_name": "Test Course 101"}, timeout=5)
        assert resp.status_code == 201, f"HTTP {resp.status_code}"
        session_id[0] = resp.json()["session_id"]
        assert session_id[0], "empty session_id"
        return True
    r.run("POST /sessions creates a session", create_session)

    def enroll():
        assert session_id[0], "no session"
        resp = requests.post(f"{base}/enroll",
                             json={"token": token, "student_id": "CS001", "name": "Alice"},
                             timeout=5)
        assert resp.status_code == 201, f"HTTP {resp.status_code}: {resp.text}"
        return True
    r.run("POST /enroll enrolls a student", enroll)

    def re_enroll():
        resp = requests.post(f"{base}/enroll",
                             json={"token": token, "student_id": "CS001", "name": "Alice Updated"},
                             timeout=5)
        assert resp.status_code in (200, 201), f"HTTP {resp.status_code}"
        assert "updated" in resp.json().get("message", "").lower(), "expected 'updated'"
        return True
    r.run("POST /enroll is idempotent (re-enroll updates)", re_enroll)

    def heartbeat():
        assert session_id[0], "no session"
        resp = requests.post(f"{base}/heartbeat",
                             json={"token": token, "session_id": session_id[0],
                                   "timestamp": int(time.time())},
                             timeout=5)
        assert resp.status_code == 200, f"HTTP {resp.status_code}: {resp.text}"
        return True
    r.run("POST /heartbeat records presence", heartbeat)

    def heartbeat_unknown_session():
        resp = requests.post(f"{base}/heartbeat",
                             json={"token": token, "session_id": "nonexistent",
                                   "timestamp": int(time.time())},
                             timeout=5)
        assert resp.status_code == 404, f"Expected 404 got {resp.status_code}"
        return True
    r.run("POST /heartbeat rejects unknown session_id (404)", heartbeat_unknown_session)

    def occupancy():
        assert session_id[0]
        resp = requests.get(f"{base}/occupancy/{session_id[0]}", timeout=5)
        assert resp.status_code == 200
        data = resp.json()
        assert "count" in data, f"no 'count' in {data}"
        assert data["count"] >= 1, f"expected ≥1 present, got {data['count']}"
        return True
    r.run("GET /occupancy returns count ≥ 1 (enrolled + heartbeat)", occupancy)

    # Send a few more heartbeats to push Alice to PRESENT status
    for _ in range(3):
        requests.post(f"{base}/heartbeat",
                      json={"token": token, "session_id": session_id[0],
                            "timestamp": int(time.time())}, timeout=5)
        time.sleep(0.2)

    def report_json():
        assert session_id[0]
        resp = requests.get(f"{base}/report/{session_id[0]}", timeout=5)
        assert resp.status_code == 200
        records = resp.json()
        assert isinstance(records, list), "expected list"
        names = [rec["name"] for rec in records]
        assert "Alice" in names or "Alice Updated" in names, \
            f"Alice not in report: {names}"
        return True
    r.run("GET /report returns Alice in report", report_json)

    def report_csv():
        assert session_id[0]
        resp = requests.get(f"{base}/report/{session_id[0]}/csv", timeout=5)
        assert resp.status_code == 200
        assert "text/csv" in resp.headers.get("content-type", ""), \
            f"wrong content-type: {resp.headers.get('content-type')}"
        lines = resp.text.strip().splitlines()
        assert lines[0].startswith("Student ID,"), f"bad CSV header: {lines[0]}"
        assert len(lines) >= 2, "CSV has no data rows"
        return True
    r.run("GET /report/csv returns valid CSV", report_csv)

    def end_session():
        assert session_id[0]
        resp = requests.patch(f"{base}/sessions/{session_id[0]}/end", timeout=5)
        assert resp.status_code == 200
        assert resp.json()["end_time"] is not None, "end_time should be set"
        return True
    r.run("PATCH /sessions/{id}/end closes session", end_session)

    def heartbeat_on_closed_session():
        assert session_id[0]
        resp = requests.post(f"{base}/heartbeat",
                             json={"token": token, "session_id": session_id[0],
                                   "timestamp": int(time.time())}, timeout=5)
        assert resp.status_code == 409, f"Expected 409 got {resp.status_code}"
        return True
    r.run("POST /heartbeat on closed session returns 409", heartbeat_on_closed_session)

    def dashboard():
        resp = requests.get(f"{base}/dashboard", timeout=5)
        assert resp.status_code == 200
        assert "attendance" in resp.text.lower() or "session" in resp.text.lower(), \
            "Dashboard HTML looks wrong"
        return True
    r.run("GET /dashboard returns HTML", dashboard)

    return r.summary()


# ---------------------------------------------------------------------------
# Router App HTTP server tests (captive portal + Student App endpoints)
# ---------------------------------------------------------------------------

def run_router_tests(host: str, port: int):
    base = f"http://{host}:{port}"
    r = TestRunner()
    print(f"\n{'='*60}")
    print(f"  Router App HTTP Tests → {base}")
    print(f"{'='*60}")

    r.run("GET / returns check-in form HTML", lambda: (
        "Roll Number" in requests.get(f"{base}/", timeout=4).text
    ))

    r.run("GET /ping returns pong", lambda: (
        "pong" in requests.get(f"{base}/ping", timeout=4).text
    ))

    def session_endpoint():
        resp = requests.get(f"{base}/session", timeout=4)
        # 200 = active session, 204 = no active session — both are valid
        assert resp.status_code in (200, 204), f"HTTP {resp.status_code}"
        if resp.status_code == 200:
            data = resp.json()
            assert "session_id" in data, f"missing session_id: {data}"
            assert "course_name" in data, f"missing course_name: {data}"
        return True
    r.run("GET /session returns JSON or 204 when no session", session_endpoint)

    def captive_portal_checkin():
        resp = requests.post(
            f"{base}/checkin",
            data={"rollNo": "CS_TEST_NONEXISTENT"},
            allow_redirects=False,
            timeout=4,
        )
        assert resp.status_code in (200, 302), f"HTTP {resp.status_code}"
        if resp.status_code == 200:
            assert "<html" in resp.text.lower(), "response is not HTML"
        return True
    r.run("POST /checkin handles unknown roll number gracefully", captive_portal_checkin)

    def js_heartbeat_no_session():
        # POST /heartbeat with no active session → 409
        resp = requests.post(
            f"{base}/heartbeat",
            json={"token": "abc123", "session_id": "nonexistent"},
            timeout=4,
        )
        assert resp.status_code == 409, f"Expected 409 got {resp.status_code}"
        return True
    r.run("POST /heartbeat rejects wrong session_id (409)", js_heartbeat_no_session)

    def js_heartbeat_empty_body():
        resp = requests.post(f"{base}/heartbeat", json={}, timeout=4)
        assert resp.status_code == 400, f"Expected 400 got {resp.status_code}"
        return True
    r.run("POST /heartbeat rejects empty body (400)", js_heartbeat_empty_body)

    def success_page_has_js():
        # GET /success should embed the JS heartbeat script
        resp = requests.get(f"{base}/success?name=Test&token=abc&sid=123", timeout=4)
        assert resp.status_code == 200
        assert "sendHeartbeat" in resp.text, "JS heartbeat function missing from success page"
        assert "setInterval" in resp.text, "setInterval missing from success page"
        assert "/heartbeat" in resp.text, "/heartbeat fetch target missing"
        return True
    r.run("GET /success page contains JS keep-alive script", success_page_has_js)

    def captive_portal_no_script():
        # Captive portal detection probes — should return 204 silently
        for probe in ["/generate_204", "/gen_204", "/hotspot-detect.html"]:
            resp = requests.get(f"{base}{probe}", timeout=4)
            assert resp.status_code == 204, \
                f"Probe {probe}: expected 204 got {resp.status_code}"
        return True
    r.run("Captive portal probes return 204 (OS will show 'connected')", captive_portal_no_script)

    return r.summary()


# ---------------------------------------------------------------------------
# CSV roster upload helper (generates a sample CSV and posts it)
# ---------------------------------------------------------------------------

def generate_roster_csv(n: int = 10) -> str:
    lines = ["rollNo,name"]
    for i in range(1, n + 1):
        lines.append(f"CS{i:03d},Student {i}")
    return "\n".join(lines)


def print_roster_instructions(host: str = "192.168.43.1"):
    print(f"""
Roster Upload (Manual Test)
───────────────────────────
The roster is uploaded via the Router App UI:

  1. Open RouterApp → tap "Manage Roster"
  2. Tap "Import CSV" → select your CSV file
     Format (header line required):
       rollNo,name
       CS001,Alice Johnson
       CS002,Bob Smith
       ...
  3. Tap "Load Sample" to use the built-in demo roster

To generate a sample CSV for testing:
  python test_captive_portal.py --generate-roster --students 35 > roster.csv

Then transfer roster.csv to the device via ADB:
  adb push roster.csv /sdcard/Download/roster.csv
""")


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="WiFi Attendance captive portal & backend tests")
    parser.add_argument("--mode", choices=["backend", "router", "both"],
                        default="backend", help="Which server to test")
    parser.add_argument("--host",     default="127.0.0.1")
    parser.add_argument("--port",     type=int, default=0,
                        help="Port (defaults: backend=8000, router=8080)")
    parser.add_argument("--generate-roster", action="store_true",
                        help="Print a sample roster CSV and exit")
    parser.add_argument("--students", type=int, default=35)
    args = parser.parse_args()

    if args.generate_roster:
        print(generate_roster_csv(args.students))
        sys.exit(0)

    success = True
    if args.mode in ("backend", "both"):
        port = args.port or 8000
        success &= run_backend_tests(args.host, port)
    if args.mode in ("router", "both"):
        port = args.port or 8080
        success &= run_router_tests(args.host, port)
    if args.mode == "backend" and args.mode != "router":
        print_roster_instructions()

    sys.exit(0 if success else 1)
