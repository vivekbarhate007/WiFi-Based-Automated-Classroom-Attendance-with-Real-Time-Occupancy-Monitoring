"""
models.py — Pydantic request/response schemas for the WiFi Attendance API.

Privacy note: none of these models ever expose or accept raw IP or MAC
addresses.  The 'token' field is always an opaque UUID generated on the
student device.
"""

from typing import Optional

from pydantic import BaseModel, Field


# ---------------------------------------------------------------------------
# Inbound — Router App / Student App payloads
# ---------------------------------------------------------------------------

class HeartbeatPayload(BaseModel):
    """Sent by the Router App (Phone A) on behalf of each detected student."""
    token: str = Field(..., description="Opaque UUID representing the student device")
    session_id: str = Field(..., description="Active session UUID")
    timestamp: int = Field(..., description="Unix epoch seconds of the heartbeat")


class EnrollmentPayload(BaseModel):
    """Sent once per student to bind their token to their identity."""
    token: str = Field(..., description="Opaque UUID generated on the student device")
    student_id: str = Field(..., description="University / institutional student ID")
    name: str = Field(..., description="Student full name")


# ---------------------------------------------------------------------------
# Session management
# ---------------------------------------------------------------------------

class SessionCreate(BaseModel):
    """Request body for creating a new classroom session."""
    course_name: str = Field(..., description="Human-readable course name")


class SessionResponse(BaseModel):
    """Returned after a session is created or fetched."""
    session_id: str
    course_name: str
    start_time: str
    end_time: Optional[str] = None

    model_config = {"from_attributes": True}


# ---------------------------------------------------------------------------
# Attendance reports
# ---------------------------------------------------------------------------

class AttendanceReport(BaseModel):
    """One row in the attendance report — resolved to student identity."""
    student_id: str
    name: str
    status: str = Field(..., description="PRESENT | PARTIAL | ABSENT")
    duration_minutes: int
    percentage: float


# ---------------------------------------------------------------------------
# Live occupancy
# ---------------------------------------------------------------------------

class OccupancyResponse(BaseModel):
    """Current live headcount for a session."""
    session_id: str
    count: int
    timestamp: int = Field(..., description="Unix epoch seconds")


# ---------------------------------------------------------------------------
# Enrollment list item (admin)
# ---------------------------------------------------------------------------

class EnrollmentResponse(BaseModel):
    """Safe view of an enrollment record."""
    token: str
    student_id: str
    name: str
    enrolled_at: str

    model_config = {"from_attributes": True}


# ---------------------------------------------------------------------------
# Generic success / error
# ---------------------------------------------------------------------------

class MessageResponse(BaseModel):
    message: str
    detail: Optional[str] = None
