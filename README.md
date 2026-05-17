# WiFi-Based Classroom Attendance

A privacy-preserving classroom attendance system that runs on a teacher's
Android phone. No backend, no internet, no extra hardware. Students join
the teacher's WiFi hotspot, scan a QR code, and check in through a tiny
HTTP server running on the same phone. To stop students checking in and
walking out, the app keeps watching with two layers of presence detection
the whole time the class is running.

> **Status:** working prototype. Tested in three real classroom sessions
> with up to 31 students.

---

## What's in this repo

```
wifi-attendance/
├── RouterApp/         Android app (the teacher-side app — the main thing)
├── backend/           Optional FastAPI server (legacy, kept for reference)
├── report/            LaTeX source for the project report
└── README.md          This file
```

- **RouterApp/** is the current, working system. Everything from hotspot
  creation to attendance reporting lives inside this one Android app.
- **backend/** is from an earlier iteration where the phone forwarded
  heartbeats to a FastAPI server. The current app does not need it ---
  it is kept here as a reference and for anyone who wants to try the
  client-server version.
- **report/** contains the LaTeX writeup describing the design.

---

## How it works in 60 seconds

1. Teacher opens the app on their phone and starts a class session.
2. The phone either creates a private WiFi hotspot (preferred) or uses
   the existing WiFi the phone is on.
3. A QR code appears on the dashboard. The QR encodes the URL of an
   HTTP server (NanoHTTPD) running on the phone itself, on port 8080.
4. Students scan the QR, join the WiFi, and a captive page asks for
   their roll number.
5. Once a student submits, the app records the check-in, redirects them
   to a success page, and that page starts a 30-second JavaScript
   heartbeat back to the server.
6. In parallel, the phone pings every IP on the local subnet every 5
   seconds to confirm phones are still connected.
7. At the end of class the teacher hits "Generate Report" and gets a
   CSV with the final attendance.

---

## Privacy model

This was important to me from day one. The two things to know:

- **No MAC addresses are ever stored.** Android sandboxes these from
  normal apps anyway, but the code is also written to never even parse
  the MAC field out of the kernel neighbour table.
- **IP addresses are hashed before they touch disk.** Every check-in
  records a SHA-256 hash of the student's IP salted with a UUID that
  is regenerated for every session. The raw IP only ever lives in
  memory, and only for as long as the session runs.

Because the salt is fresh every class, the same student's check-ins on
Monday and Tuesday produce two completely unrelated hashes --- so even
if the whole database is dumped, you can't link attendance across days.

---

## Tech stack

**Android app (RouterApp/)**

- Kotlin, targeting Android API 26+ (compiled against 34)
- Android Views (not Compose) for the UI
- Room for local persistence (four tables: roster, sessions, check-ins,
  presence log)
- Kotlin Coroutines for the periodic subnet scans
- NanoHTTPD for the in-process HTTP server
- ZXing for QR code generation
- Gson for the heartbeat JSON parsing
- Foreground Service of type `dataSync` so Android doesn't kill the
  process when the screen locks

**Backend (backend/)** *(legacy, not required)*

- Python 3.9+, FastAPI, SQLAlchemy, Uvicorn

---

## Building the Android app

### Prerequisites

- Android Studio Iguana or newer
- JDK 17
- An Android phone running Android 8.0 (API 26) or higher

### Steps

```bash
git clone https://github.com/<your-username>/wifi-attendance.git
cd wifi-attendance/RouterApp
```

Then open `RouterApp/` in Android Studio and let Gradle sync. Once it
finishes, plug in your phone (USB debugging enabled), pick it as the
deployment target, and hit Run.

The app will ask for these permissions on first run:

- **Nearby Devices** — required for `startLocalOnlyHotspot()` on
  Android 13 and above.
- **Location (older Android)** — needed by `WifiManager` on Android 12
  and below.
- **Notifications** — required by the foreground service.

### Setting up the roster

The roster is a CSV with two columns: `rollNo,name`. Use the
"Import Roster" button on the main screen to load one from the
phone's storage. A sample is provided at `backend/sample_roster.csv`.

---

## Running the backend (optional)

Skip this section unless you actually want to experiment with the
legacy client-server flavour.

```bash
cd backend
python -m venv .venv
source .venv/bin/activate     # or .venv\Scripts\activate on Windows
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

The server will be reachable at `http://<your-laptop-ip>:8000`. You'd
then need to point the Android app at this URL --- which the current
version does not do, because it serves its own HTTP locally.

---

## Two bugs worth knowing about (so you don't repeat them)

If you fork this and start changing the scanner, here are the
implementation traps that cost me time:

**1. Don't accept TCP RST as "alive" in the ping sweep.** An early
version did, on the theory that any host willing to RST is at least
running. The problem is that if the soft-AP interface isn't up yet but
the cellular interface is, the sweep ends up running over the
carrier's CGNAT subnet, and hundreds of unrelated phones politely RST
your probes. A single test phone showed up as 242 "connected
students" before I fixed this. The current code uses ICMP only, locks
the scan to interface names that look like soft access points, and
caps results at 100 hosts per sweep.

**2. Allow cleartext HTTP in the manifest.** Android 9+ blocks plain
HTTP by default --- even when the request is to the phone's own
loopback. You need both `usesCleartextTraffic="true"` in the
manifest and a `network_security_config.xml` resource that whitelists
cleartext. Without these the in-app "Test Server" button silently
fails with `CleartextNotPermittedException`.

---

## Limitations

A few things the system can't do (yet):

- WiFi range bleeds into the corridor by a couple of metres in our
  test classroom. A student standing just outside could still check
  in.
- iPhones that dismiss the "Sign in to WiFi" notification without
  scanning the QR won't get recorded — there is no way to force the
  captive page to open across all iOS versions.
- The teacher's phone is a single point of failure. If it dies
  mid-class, attendance stops being recorded.
- The system depends on the student tapping their roll number at
  least once. Fully automatic identification would require either an
  MDM-managed identity (defeats the privacy model) or a companion
  app (defeats the friction model).

---

## Next things I'd like to add

- WiFi RSSI thresholding to filter out students who are in range but
  clearly far from the hotspot
- Optional encrypted backup of the Room database to the teacher's own
  cloud (the per-session salting already makes this safe)
- Per-student attendance trend view across sessions, without breaking
  cross-session unlinkability

---

## Report

The full technical writeup is in `report/` as LaTeX, with a
pre-compiled `wifi_attendance_report.pdf` at the project root. It
covers the design choices, the bugs, the privacy model, and an
informal evaluation on three classroom sessions.

---

## License

MIT — see [LICENSE](LICENSE). Use it however you like; attribution
appreciated but not required.

---

## Author

**Vivek Barhate**
Department of Computer Engineering
Contact: barhatevisb@gmail.com
