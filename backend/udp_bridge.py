"""
udp_bridge.py — UDP-to-REST bridge for the WiFi Attendance system.

The Router App (Phone A) can send JSON-encoded UDP packets to this bridge
instead of making HTTP calls directly.  The bridge forwards them to the
local FastAPI server over HTTP.

Ports
-----
  UDP 9876  →  POST /heartbeat      (HeartbeatPayload)
  UDP 9877  →  POST /enroll         (EnrollmentPayload)

Packet format
-------------
Both ports expect newline-terminated UTF-8 JSON matching the Pydantic
model for the respective endpoint.

Example heartbeat packet:
  {"token": "uuid-here", "session_id": "uuid-here", "timestamp": 1700000000}

Run alongside the FastAPI server:
  python udp_bridge.py

Or start both together:
  uvicorn main:app --host 0.0.0.0 --port 8000 &
  python udp_bridge.py
"""

import asyncio
import json
import logging
import os
import sys
from typing import Optional

import urllib.request
import urllib.error

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

HEARTBEAT_PORT: int = int(os.getenv("BRIDGE_HEARTBEAT_PORT", "9876"))
ENROLLMENT_PORT: int = int(os.getenv("BRIDGE_ENROLLMENT_PORT", "9877"))
API_BASE_URL: str = os.getenv("API_BASE_URL", "http://127.0.0.1:8000")
BIND_HOST: str = os.getenv("BRIDGE_HOST", "0.0.0.0")

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
log = logging.getLogger("udp_bridge")


# ---------------------------------------------------------------------------
# HTTP helper (stdlib — no extra dependencies)
# ---------------------------------------------------------------------------

def post_json(path: str, payload: dict) -> Optional[dict]:
    """
    Synchronous JSON POST to the FastAPI server.

    Returns the parsed response dict on success, None on any error.
    We keep this synchronous and run it in a thread-pool executor so the
    asyncio event loop is never blocked.
    """
    url = f"{API_BASE_URL}{path}"
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        log.warning("HTTP %s from %s: %s", exc.code, url, exc.read().decode())
    except urllib.error.URLError as exc:
        log.warning("URLError posting to %s: %s", url, exc.reason)
    except Exception as exc:
        log.warning("Unexpected error posting to %s: %s", url, exc)
    return None


# ---------------------------------------------------------------------------
# UDP protocol implementations
# ---------------------------------------------------------------------------

class _BaseUDPProtocol(asyncio.DatagramProtocol):
    """Shared base: parse JSON, validate required fields, forward via HTTP."""

    required_fields: tuple = ()
    api_path: str = ""
    name: str = "base"

    def __init__(self, loop: asyncio.AbstractEventLoop) -> None:
        self._loop = loop

    def connection_made(self, transport: asyncio.DatagramTransport) -> None:
        self.transport = transport
        log.info("[%s] Listening on UDP port (transport ready)", self.name)

    def datagram_received(self, data: bytes, addr: tuple) -> None:
        # addr intentionally not logged — privacy guarantee
        try:
            text = data.decode("utf-8").strip()
            payload = json.loads(text)
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            log.warning("[%s] Malformed packet: %s", self.name, exc)
            return

        # Validate required fields
        missing = [f for f in self.required_fields if f not in payload]
        if missing:
            log.warning("[%s] Packet missing fields: %s", self.name, missing)
            return

        # Forward to FastAPI in a thread (keeps event loop free)
        self._loop.run_in_executor(
            None,
            self._forward,
            payload,
        )

    def _forward(self, payload: dict) -> None:
        result = post_json(self.api_path, payload)
        if result:
            log.debug("[%s] Forwarded OK → %s", self.name, result)
        else:
            log.warning("[%s] Forward to %s failed", self.name, self.api_path)

    def error_received(self, exc: Exception) -> None:
        log.error("[%s] UDP error: %s", self.name, exc)

    def connection_lost(self, exc: Optional[Exception]) -> None:
        log.info("[%s] UDP connection lost: %s", self.name, exc)


class HeartbeatProtocol(_BaseUDPProtocol):
    required_fields = ("token", "session_id", "timestamp")
    api_path = "/heartbeat"
    name = "heartbeat"


class EnrollmentProtocol(_BaseUDPProtocol):
    required_fields = ("token", "student_id", "name")
    api_path = "/enroll"
    name = "enrollment"


# ---------------------------------------------------------------------------
# Main entry point
# ---------------------------------------------------------------------------

async def main() -> None:
    loop = asyncio.get_running_loop()

    # Create both UDP endpoints concurrently
    heartbeat_transport, _ = await loop.create_datagram_endpoint(
        lambda: HeartbeatProtocol(loop),
        local_addr=(BIND_HOST, HEARTBEAT_PORT),
    )

    enrollment_transport, _ = await loop.create_datagram_endpoint(
        lambda: EnrollmentProtocol(loop),
        local_addr=(BIND_HOST, ENROLLMENT_PORT),
    )

    log.info(
        "UDP bridge running — heartbeat=udp://%s:%d  enrollment=udp://%s:%d",
        BIND_HOST, HEARTBEAT_PORT,
        BIND_HOST, ENROLLMENT_PORT,
    )
    log.info("Forwarding to FastAPI at %s", API_BASE_URL)

    try:
        # Run until cancelled (Ctrl-C)
        await asyncio.Event().wait()
    finally:
        heartbeat_transport.close()
        enrollment_transport.close()
        log.info("UDP bridge stopped.")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        log.info("Interrupted — exiting.")
        sys.exit(0)
