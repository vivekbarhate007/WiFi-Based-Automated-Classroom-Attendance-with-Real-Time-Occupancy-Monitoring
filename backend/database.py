"""
database.py — SQLAlchemy setup and table definitions for WiFi Attendance System.

Tables:
  sessions          — one row per class session
  enrollments       — token ↔ student mapping (no IP/MAC ever stored)
  presence_logs     — raw heartbeat windows keyed by token
  attendance_records— computed PRESENT / PARTIAL / ABSENT per session
"""

import os
from datetime import datetime

from sqlalchemy import (
    Column,
    DateTime,
    Float,
    ForeignKey,
    Integer,
    String,
    create_engine,
)
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker

# ---------------------------------------------------------------------------
# Engine / session factory
# ---------------------------------------------------------------------------

DATABASE_URL = os.getenv("DATABASE_URL", "sqlite:///./attendance.db")

engine = create_engine(
    DATABASE_URL,
    connect_args={"check_same_thread": False},  # needed for SQLite + FastAPI
    echo=False,
)

SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)


# ---------------------------------------------------------------------------
# Declarative base
# ---------------------------------------------------------------------------

class Base(DeclarativeBase):
    pass


# ---------------------------------------------------------------------------
# ORM models
# ---------------------------------------------------------------------------

class SessionModel(Base):
    """One row per classroom session."""
    __tablename__ = "sessions"

    session_id = Column(String, primary_key=True, index=True)
    course_name = Column(String, nullable=False)
    start_time = Column(DateTime, nullable=False, default=datetime.utcnow)
    end_time = Column(DateTime, nullable=True)


class Enrollment(Base):
    """Privacy-preserving enrollment: token is the opaque UUID, never IP/MAC."""
    __tablename__ = "enrollments"

    token = Column(String, primary_key=True, index=True)
    student_id = Column(String, nullable=False, index=True)
    name = Column(String, nullable=False)
    enrolled_at = Column(DateTime, nullable=False, default=datetime.utcnow)


class PresenceLog(Base):
    """
    Raw presence window for a token during a session.
    One row per (token, session_id) pair — updated on each heartbeat.
    """
    __tablename__ = "presence_logs"

    id = Column(Integer, primary_key=True, autoincrement=True)
    token = Column(String, ForeignKey("enrollments.token"), nullable=False, index=True)
    session_id = Column(String, ForeignKey("sessions.session_id"), nullable=False, index=True)
    first_seen = Column(DateTime, nullable=False)
    last_seen = Column(DateTime, nullable=False)


class AttendanceRecord(Base):
    """
    Computed attendance result per (session, token).
    Populated by the attendance engine at report time or session end.
    """
    __tablename__ = "attendance_records"

    id = Column(Integer, primary_key=True, autoincrement=True)
    session_id = Column(String, ForeignKey("sessions.session_id"), nullable=False, index=True)
    token = Column(String, ForeignKey("enrollments.token"), nullable=False, index=True)
    status = Column(String, nullable=False)          # PRESENT | PARTIAL | ABSENT
    duration_minutes = Column(Integer, nullable=False, default=0)
    percentage = Column(Float, nullable=False, default=0.0)


# ---------------------------------------------------------------------------
# FastAPI dependency
# ---------------------------------------------------------------------------

def get_db():
    """Yield a SQLAlchemy session; close it on request teardown."""
    db: Session = SessionLocal()
    try:
        yield db
    finally:
        db.close()


# ---------------------------------------------------------------------------
# Table creation (called at startup)
# ---------------------------------------------------------------------------

def init_db() -> None:
    """Create all tables if they don't already exist."""
    Base.metadata.create_all(bind=engine)
