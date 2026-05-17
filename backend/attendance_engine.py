"""
attendance_engine.py — Core attendance computation for WiFi Attendance System.

Thresholds
----------
  >= 75 % of session duration  →  PRESENT
  50 – 74 %                    →  PARTIAL
  < 50 %                       →  ABSENT

A token is considered "live" if its last_seen timestamp is within the past
LIVE_WINDOW_SECONDS seconds (default 90 s).
"""

import csv
import io
import time
from datetime import datetime, timedelta, timezone
from typing import List

from sqlalchemy.orm import Session

from database import AttendanceRecord, Enrollment, PresenceLog, SessionModel
from models import AttendanceReport

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

LIVE_WINDOW_SECONDS = 90        # heartbeat considered "live" within this window
PRESENT_THRESHOLD = 75.0        # % to be PRESENT
PARTIAL_THRESHOLD = 50.0        # % to be at least PARTIAL (else ABSENT)


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------

def _session_duration_minutes(session: SessionModel) -> float:
    """Return session wall-clock duration in minutes.

    If the session has no end_time, use 'now' (live session).
    """
    end = session.end_time or datetime.utcnow()
    delta = end - session.start_time
    return max(delta.total_seconds() / 60.0, 1.0)   # at least 1 min to avoid /0


def _presence_minutes(log: PresenceLog) -> float:
    """Return presence duration for a single log row in minutes."""
    delta = log.last_seen - log.first_seen
    # Add one heartbeat interval (30 s) so the last window is counted.
    total_seconds = delta.total_seconds() + 30
    return max(total_seconds / 60.0, 0.0)


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

def compute_attendance(session_id: str, db: Session) -> List[AttendanceReport]:
    """
    Compute attendance for every enrolled student in the given session.

    Steps
    -----
    1. Load session to get start/end times.
    2. For each enrolled student, sum their presence time via presence_logs.
    3. Compute percentage and assign status.
    4. Upsert into attendance_records for caching.
    5. Return a list of AttendanceReport (identity resolved).
    """
    session = db.query(SessionModel).filter(
        SessionModel.session_id == session_id
    ).first()

    if session is None:
        return []

    session_minutes = _session_duration_minutes(session)
    enrollments: List[Enrollment] = db.query(Enrollment).all()

    reports: List[AttendanceReport] = []

    for enrollment in enrollments:
        logs: List[PresenceLog] = (
            db.query(PresenceLog)
            .filter(
                PresenceLog.session_id == session_id,
                PresenceLog.token == enrollment.token,
            )
            .all()
        )

        total_minutes = sum(_presence_minutes(log) for log in logs)
        total_minutes = min(total_minutes, session_minutes)  # cap at session length

        percentage = (total_minutes / session_minutes) * 100.0

        if percentage >= PRESENT_THRESHOLD:
            status = "PRESENT"
        elif percentage >= PARTIAL_THRESHOLD:
            status = "PARTIAL"
        else:
            status = "ABSENT"

        duration_int = int(round(total_minutes))

        # --- upsert into attendance_records ---
        existing: AttendanceRecord | None = (
            db.query(AttendanceRecord)
            .filter(
                AttendanceRecord.session_id == session_id,
                AttendanceRecord.token == enrollment.token,
            )
            .first()
        )
        if existing:
            existing.status = status
            existing.duration_minutes = duration_int
            existing.percentage = round(percentage, 2)
        else:
            record = AttendanceRecord(
                session_id=session_id,
                token=enrollment.token,
                status=status,
                duration_minutes=duration_int,
                percentage=round(percentage, 2),
            )
            db.add(record)

        db.commit()

        reports.append(
            AttendanceReport(
                student_id=enrollment.student_id,
                name=enrollment.name,
                status=status,
                duration_minutes=duration_int,
                percentage=round(percentage, 2),
            )
        )

    # Sort: PRESENT first, then PARTIAL, then ABSENT; alphabetical within groups.
    order = {"PRESENT": 0, "PARTIAL": 1, "ABSENT": 2}
    reports.sort(key=lambda r: (order.get(r.status, 3), r.name))
    return reports


def get_live_occupancy(session_id: str, db: Session) -> int:
    """
    Count unique tokens whose last_seen is within LIVE_WINDOW_SECONDS of now.

    Only considers tokens that are enrolled so ghost/rogue devices are excluded.
    """
    cutoff = datetime.utcnow() - timedelta(seconds=LIVE_WINDOW_SECONDS)

    # Subquery: enrolled tokens
    enrolled_tokens = db.query(Enrollment.token).subquery()

    count = (
        db.query(PresenceLog)
        .filter(
            PresenceLog.session_id == session_id,
            PresenceLog.last_seen >= cutoff,
            PresenceLog.token.in_(enrolled_tokens),
        )
        .count()
    )
    return count


def export_csv(reports: List[AttendanceReport]) -> str:
    """
    Serialise a list of AttendanceReport objects to a CSV string.

    Columns: Student ID, Name, Status, Duration (min), Percentage
    """
    output = io.StringIO()
    writer = csv.writer(output)
    writer.writerow(["Student ID", "Name", "Status", "Duration (min)", "Percentage"])
    for r in reports:
        writer.writerow([
            r.student_id,
            r.name,
            r.status,
            r.duration_minutes,
            f"{r.percentage:.1f}",
        ])
    return output.getvalue()
