#!/usr/bin/env python3
"""
load_test.py — Simulates 30+ concurrent students sending UDP heartbeats
and HTTP check-ins against the WiFi Attendance backend.

Usage:
    # 1. Start the backend first
    uvicorn main:app --host 0.0.0.0 --port 8000

    # 2. Run load test (default: 35 students, 3 heartbeat rounds)
    python load_test.py

    # 3. Custom run
    python load_test.py --host 127.0.0.1 --port 8000 --students 50 --rounds 5

Metrics reported:
  - Enroll latency (per student)
  - Heartbeat latency (per round)
  - Attendance report accuracy
  - Total wall time
"""

import argparse
import concurrent.futures
import json
import random
import socket
import time
import uuid
from dataclasses import dataclass, field
from typing import List, Optional

import requests

# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------

DEFAULT_HOST     = "127.0.0.1"
DEFAULT_PORT     = 8000
DEFAULT_UDP_PORT = 9876     # Router App HeartbeatServer port (for UDP tests)
DEFAULT_STUDENTS = 35
DEFAULT_ROUNDS   = 3
HEARTBEAT_SLEEP  = 2        # seconds between heartbeat rounds (shortened for test)


# ---------------------------------------------------------------------------
# Student simulation
# ---------------------------------------------------------------------------

@dataclass
class FakeStudent:
    roll_no: str
    name: str
    token: str = field(default_factory=lambda: str(uuid.uuid4()))
    enrolled: bool = False
    heartbeats_sent: int = 0


def make_students(n: int) -> List[FakeStudent]:
    return [
        FakeStudent(
            roll_no=f"CS{i:03d}",
            name=f"Student {i}",
        )
        for i in range(1, n + 1)
    ]


# ---------------------------------------------------------------------------
# HTTP helpers
# ---------------------------------------------------------------------------

def base_url(host: str, port: int) -> str:
    return f"http://{host}:{port}"


def create_session(base: str, course: str = "Load Test 101") -> Optional[str]:
    try:
        r = requests.post(f"{base}/sessions", json={"course_name": course}, timeout=5)
        r.raise_for_status()
        sid = r.json()["session_id"]
        print(f"  [session] Created: {sid}")
        return sid
    except Exception as e:
        print(f"  [session] FAILED: {e}")
        return None


def enroll_student(base: str, student: FakeStudent) -> float:
    """Returns latency in ms, or -1 on failure."""
    t0 = time.perf_counter()
    try:
        r = requests.post(
            f"{base}/enroll",
            json={"token": student.token, "student_id": student.roll_no, "name": student.name},
            timeout=5,
        )
        latency_ms = (time.perf_counter() - t0) * 1000
        if r.status_code in (200, 201):
            student.enrolled = True
            return latency_ms
        print(f"    Enroll {student.roll_no}: HTTP {r.status_code}")
        return -1
    except Exception as e:
        print(f"    Enroll {student.roll_no}: ERROR {e}")
        return -1


def send_heartbeat_http(base: str, student: FakeStudent, session_id: str) -> float:
    """Send heartbeat via HTTP POST /heartbeat. Returns latency ms."""
    t0 = time.perf_counter()
    try:
        r = requests.post(
            f"{base}/heartbeat",
            json={
                "token": student.token,
                "session_id": session_id,
                "timestamp": int(time.time()),
            },
            timeout=5,
        )
        latency_ms = (time.perf_counter() - t0) * 1000
        if r.status_code == 200:
            student.heartbeats_sent += 1
            return latency_ms
        return -1
    except Exception:
        return -1


def send_heartbeat_udp(host: str, student: FakeStudent, session_id: str) -> bool:
    """Send heartbeat via UDP (simulates Student App → Router App path)."""
    try:
        payload = json.dumps({
            "token": student.token,
            "session_id": session_id,
            "timestamp": int(time.time()),
        }).encode()
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.sendto(payload, (host, DEFAULT_UDP_PORT))
        student.heartbeats_sent += 1
        return True
    except Exception:
        return False


def fetch_report(base: str, session_id: str) -> dict:
    try:
        r = requests.get(f"{base}/report/{session_id}", timeout=10)
        r.raise_for_status()
        return {"records": r.json(), "error": None}
    except Exception as e:
        return {"records": [], "error": str(e)}


def end_session(base: str, session_id: str):
    try:
        requests.patch(f"{base}/sessions/{session_id}/end", timeout=5)
        print(f"  [session] Ended: {session_id}")
    except Exception as e:
        print(f"  [session] End failed: {e}")


# ---------------------------------------------------------------------------
# Test runner
# ---------------------------------------------------------------------------

def run(host: str, port: int, n_students: int, n_rounds: int):
    base = base_url(host, port)
    print(f"\n{'='*60}")
    print(f"  WiFi Attendance Load Test")
    print(f"  Target:   {base}")
    print(f"  Students: {n_students}")
    print(f"  Rounds:   {n_rounds}")
    print(f"{'='*60}\n")

    # Health check
    try:
        r = requests.get(f"{base}/health", timeout=3)
        print(f"[health] {r.json()}\n")
    except Exception as e:
        print(f"[health] FAILED — is the backend running? ({e})\n")
        return

    students = make_students(n_students)
    session_id = create_session(base)
    if not session_id:
        return

    # -----------------------------------------------------------------------
    # Phase 1: Concurrent enroll
    # -----------------------------------------------------------------------
    print(f"\n[Phase 1] Enrolling {n_students} students concurrently…")
    t_enroll_start = time.perf_counter()
    enroll_latencies = []

    with concurrent.futures.ThreadPoolExecutor(max_workers=n_students) as pool:
        futures = {pool.submit(enroll_student, base, s): s for s in students}
        for fut in concurrent.futures.as_completed(futures):
            lat = fut.result()
            if lat >= 0:
                enroll_latencies.append(lat)

    t_enroll = (time.perf_counter() - t_enroll_start) * 1000
    enrolled = sum(1 for s in students if s.enrolled)
    print(f"  Enrolled: {enrolled}/{n_students}")
    print(f"  Wall time: {t_enroll:.0f} ms")
    if enroll_latencies:
        print(f"  Latency p50: {sorted(enroll_latencies)[len(enroll_latencies)//2]:.1f} ms")
        print(f"  Latency p99: {sorted(enroll_latencies)[int(len(enroll_latencies)*0.99)]:.1f} ms")

    # -----------------------------------------------------------------------
    # Phase 2: Concurrent heartbeats (multiple rounds)
    # -----------------------------------------------------------------------
    print(f"\n[Phase 2] {n_rounds} heartbeat rounds…")
    all_hb_latencies = []

    for rnd in range(1, n_rounds + 1):
        print(f"  Round {rnd}/{n_rounds}…", end=" ", flush=True)
        t_hb_start = time.perf_counter()
        hb_latencies = []

        with concurrent.futures.ThreadPoolExecutor(max_workers=n_students) as pool:
            futures = [
                pool.submit(send_heartbeat_http, base, s, session_id)
                for s in students if s.enrolled
            ]
            for fut in concurrent.futures.as_completed(futures):
                lat = fut.result()
                if lat >= 0:
                    hb_latencies.append(lat)

        t_hb = (time.perf_counter() - t_hb_start) * 1000
        all_hb_latencies.extend(hb_latencies)
        ok = len(hb_latencies)
        total = len([s for s in students if s.enrolled])
        print(f"OK={ok}/{total}  wall={t_hb:.0f}ms  "
              f"p50={sorted(hb_latencies)[len(hb_latencies)//2]:.1f}ms")

        if rnd < n_rounds:
            time.sleep(HEARTBEAT_SLEEP)

    # -----------------------------------------------------------------------
    # Phase 3: Fetch report & check accuracy
    # -----------------------------------------------------------------------
    print(f"\n[Phase 3] Fetching attendance report…")
    result = fetch_report(base, session_id)
    if result["error"]:
        print(f"  Report fetch FAILED: {result['error']}")
    else:
        records = result["records"]
        present = [r for r in records if r.get("status") == "PRESENT"]
        partial = [r for r in records if r.get("status") == "PARTIAL"]
        absent  = [r for r in records if r.get("status") == "ABSENT"]
        print(f"  Total records: {len(records)}")
        print(f"  PRESENT: {len(present)}  PARTIAL: {len(partial)}  ABSENT: {len(absent)}")
        accuracy = len(present) / max(len(records), 1) * 100
        print(f"  Accuracy (PRESENT rate): {accuracy:.1f}%  (target ≥90%)")

    end_session(base, session_id)

    # -----------------------------------------------------------------------
    # Summary
    # -----------------------------------------------------------------------
    print(f"\n{'='*60}")
    print("  SUMMARY")
    print(f"  Enroll success rate:  {enrolled/n_students*100:.1f}%")
    if all_hb_latencies:
        srt = sorted(all_hb_latencies)
        print(f"  Heartbeat p50 lat:    {srt[len(srt)//2]:.1f} ms")
        print(f"  Heartbeat p95 lat:    {srt[int(len(srt)*0.95)]:.1f} ms")
        print(f"  Heartbeat p99 lat:    {srt[int(len(srt)*0.99)]:.1f} ms")
        failures = sum(1 for s in students if s.heartbeats_sent < n_rounds)
        print(f"  Students with missed heartbeats: {failures}")
    print(f"{'='*60}\n")


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="WiFi Attendance load test")
    parser.add_argument("--host",     default=DEFAULT_HOST)
    parser.add_argument("--port",     type=int, default=DEFAULT_PORT)
    parser.add_argument("--students", type=int, default=DEFAULT_STUDENTS)
    parser.add_argument("--rounds",   type=int, default=DEFAULT_ROUNDS)
    args = parser.parse_args()

    run(args.host, args.port, args.students, args.rounds)
