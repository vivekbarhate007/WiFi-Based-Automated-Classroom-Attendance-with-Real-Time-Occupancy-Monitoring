"""
main.py — FastAPI application for the WiFi-Based Automated Classroom Attendance system.

Privacy guarantee: no IP or MAC address is ever stored or logged.
All student tracking uses opaque tokens (UUIDs) generated on the device.

Run with:
    uvicorn main:app --host 0.0.0.0 --port 8000 --reload
"""

import time
import uuid
from datetime import datetime
from typing import List, Optional

from fastapi import Depends, FastAPI, HTTPException, Request, Response
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse, PlainTextResponse, RedirectResponse
from fastapi.templating import Jinja2Templates
from sqlalchemy.orm import Session

from attendance_engine import compute_attendance, export_csv, get_live_occupancy
from database import (
    Enrollment,
    PresenceLog,
    SessionModel,
    get_db,
    init_db,
)
from models import (
    AttendanceReport,
    EnrollmentPayload,
    EnrollmentResponse,
    HeartbeatPayload,
    MessageResponse,
    OccupancyResponse,
    SessionCreate,
    SessionResponse,
)

# ---------------------------------------------------------------------------
# App bootstrap
# ---------------------------------------------------------------------------

app = FastAPI(
    title="WiFi Attendance API",
    description="Privacy-preserving WiFi-based classroom attendance system",
    version="1.0.0",
)

# CORS — allow all origins so the Router App on the same LAN can reach us
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

templates = Jinja2Templates(directory="templates")


@app.on_event("startup")
def on_startup() -> None:
    """Create database tables on first run."""
    init_db()


# ---------------------------------------------------------------------------
# Root & Favicon
# ---------------------------------------------------------------------------

@app.get("/", include_in_schema=False)
def root():
    """Redirect root to the live dashboard."""
    return RedirectResponse(url="/dashboard")


@app.get("/favicon.ico", include_in_schema=False)
def favicon():
    """Suppress browser favicon 404 noise."""
    return Response(status_code=204)


# ---------------------------------------------------------------------------
# Health
# ---------------------------------------------------------------------------

@app.get("/health", tags=["Health"])
def health_check():
    return {"status": "ok"}


# ---------------------------------------------------------------------------
# Session management
# ---------------------------------------------------------------------------

@app.post("/sessions", response_model=SessionResponse, status_code=201, tags=["Sessions"])
def create_session(payload: SessionCreate, db: Session = Depends(get_db)):
    """Create a new classroom session. Returns the generated session_id."""
    session_id = str(uuid.uuid4())
    session = SessionModel(
        session_id=session_id,
        course_name=payload.course_name,
        start_time=datetime.utcnow(),
    )
    db.add(session)
    db.commit()
    db.refresh(session)
    return _session_to_response(session)


@app.get("/sessions", response_model=List[SessionResponse], tags=["Sessions"])
def list_sessions(db: Session = Depends(get_db)):
    """List all sessions, most recently started first."""
    sessions = (
        db.query(SessionModel).order_by(SessionModel.start_time.desc()).all()
    )
    return [_session_to_response(s) for s in sessions]


@app.get("/sessions/{session_id}", response_model=SessionResponse, tags=["Sessions"])
def get_session(session_id: str, db: Session = Depends(get_db)):
    """Fetch details for a single session."""
    session = _get_session_or_404(session_id, db)
    return _session_to_response(session)


@app.patch("/sessions/{session_id}/end", response_model=SessionResponse, tags=["Sessions"])
def end_session(session_id: str, db: Session = Depends(get_db)):
    """Mark a session as ended. Idempotent — safe to call twice."""
    session = _get_session_or_404(session_id, db)
    if session.end_time is None:
        session.end_time = datetime.utcnow()
        db.commit()
        db.refresh(session)
    return _session_to_response(session)


# ---------------------------------------------------------------------------
# Enrollment
# ---------------------------------------------------------------------------

@app.post("/enroll", response_model=MessageResponse, status_code=201, tags=["Enrollment"])
def enroll_student(payload: EnrollmentPayload, db: Session = Depends(get_db)):
    """
    Bind an opaque token to a student identity.

    If the token already exists the record is updated (re-enrollment).
    Never stores IP or MAC address.
    """
    existing = db.query(Enrollment).filter(Enrollment.token == payload.token).first()
    if existing:
        existing.student_id = payload.student_id
        existing.name = payload.name
        db.commit()
        return MessageResponse(message="Enrollment updated", detail=payload.token)

    enrollment = Enrollment(
        token=payload.token,
        student_id=payload.student_id,
        name=payload.name,
        enrolled_at=datetime.utcnow(),
    )
    db.add(enrollment)
    db.commit()
    return MessageResponse(message="Enrollment successful", detail=payload.token)


@app.get("/enrollments", response_model=List[EnrollmentResponse], tags=["Enrollment"])
def list_enrollments(db: Session = Depends(get_db)):
    """List all enrolled students. Admin endpoint."""
    enrollments = db.query(Enrollment).order_by(Enrollment.enrolled_at.desc()).all()
    return [
        EnrollmentResponse(
            token=e.token,
            student_id=e.student_id,
            name=e.name,
            enrolled_at=e.enrolled_at.isoformat(),
        )
        for e in enrollments
    ]


# ---------------------------------------------------------------------------
# Presence (called by Router App)
# ---------------------------------------------------------------------------

@app.post("/heartbeat", response_model=MessageResponse, tags=["Presence"])
def receive_heartbeat(payload: HeartbeatPayload, db: Session = Depends(get_db)):
    """
    Receive a heartbeat from the Router App.

    Upserts a PresenceLog row for (token, session_id):
      - If no log exists, creates one with first_seen = last_seen = now.
      - If a log exists, advances last_seen to now.

    Unknown tokens (not enrolled) are silently accepted so the Router App
    does not need to filter; they simply won't appear in reports.
    """
    # Validate session exists
    session = db.query(SessionModel).filter(
        SessionModel.session_id == payload.session_id
    ).first()
    if session is None:
        raise HTTPException(status_code=404, detail="Session not found")
    if session.end_time is not None:
        raise HTTPException(status_code=409, detail="Session has already ended")

    now = datetime.utcfromtimestamp(payload.timestamp)

    log: Optional[PresenceLog] = (
        db.query(PresenceLog)
        .filter(
            PresenceLog.token == payload.token,
            PresenceLog.session_id == payload.session_id,
        )
        .first()
    )

    if log is None:
        log = PresenceLog(
            token=payload.token,
            session_id=payload.session_id,
            first_seen=now,
            last_seen=now,
        )
        db.add(log)
    else:
        if now > log.last_seen:
            log.last_seen = now

    db.commit()
    return MessageResponse(message="Heartbeat recorded")


@app.get(
    "/occupancy/{session_id}",
    response_model=OccupancyResponse,
    tags=["Presence"],
)
def live_occupancy(session_id: str, db: Session = Depends(get_db)):
    """Return the number of enrolled students currently present (last 90 s)."""
    _get_session_or_404(session_id, db)
    count = get_live_occupancy(session_id, db)
    return OccupancyResponse(
        session_id=session_id,
        count=count,
        timestamp=int(time.time()),
    )


# ---------------------------------------------------------------------------
# Reports
# ---------------------------------------------------------------------------

@app.get(
    "/report/{session_id}",
    response_model=List[AttendanceReport],
    tags=["Reports"],
)
def attendance_report(session_id: str, db: Session = Depends(get_db)):
    """Full attendance report for a session as JSON."""
    _get_session_or_404(session_id, db)
    return compute_attendance(session_id, db)


@app.get("/report/{session_id}/csv", tags=["Reports"])
def attendance_report_csv(session_id: str, db: Session = Depends(get_db)):
    """Download attendance report as a CSV file."""
    session = _get_session_or_404(session_id, db)
    reports = compute_attendance(session_id, db)
    csv_data = export_csv(reports)
    filename = f"attendance_{session.course_name.replace(' ', '_')}_{session_id[:8]}.csv"
    return PlainTextResponse(
        content=csv_data,
        media_type="text/csv",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )


# ---------------------------------------------------------------------------
# Dashboard
# ---------------------------------------------------------------------------

@app.get("/dashboard", response_class=HTMLResponse, tags=["Dashboard"])
def dashboard(request: Request, db: Session = Depends(get_db)):
    """Serve the live attendance dashboard."""
    sessions = (
        db.query(SessionModel).order_by(SessionModel.start_time.desc()).all()
    )
    session_list = [
        {
            "session_id": s.session_id,
            "label": f"{s.course_name} — {s.start_time.strftime('%Y-%m-%d %H:%M')}",
            "ended": s.end_time is not None,
        }
        for s in sessions
    ]
    return templates.TemplateResponse(
        "dashboard.html",
        {"request": request, "sessions": session_list},
    )


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------

def _get_session_or_404(session_id: str, db: Session) -> SessionModel:
    session = db.query(SessionModel).filter(
        SessionModel.session_id == session_id
    ).first()
    if session is None:
        raise HTTPException(status_code=404, detail=f"Session '{session_id}' not found")
    return session


def _session_to_response(session: SessionModel) -> SessionResponse:
    return SessionResponse(
        session_id=session.session_id,
        course_name=session.course_name,
        start_time=session.start_time.isoformat(),
        end_time=session.end_time.isoformat() if session.end_time else None,
    )
