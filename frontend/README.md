<<<<<<< HEAD
# Frontend Development Guide
This frontend is a static dashboard that consumes live scanner pipeline events and route context.

## Files
- `index.html`: layout and widget scaffolding
- `styles.css`: responsive dashboard + modal styling
- `app.js`: event handling, route planner, weather panel, notifications, visualizer popup
- `dev_server.py`: local static/API bridge server for development

## Local run
From the `frontend` directory:
```bash
python3 dev_server.py
```
Default URL:
```bash
http://127.0.0.1:8787
```

Optional environment overrides:
=======
# Frontend Dashboard Guide
Current guide for the web dashboard layer that visualizes pipeline events, alerts, routing context, and supporting status panels.

## Purpose
- Provide operator-facing visibility into stream/pipeline outputs.
- Render route, weather/provider, and alert-intel context from backend APIs.
- Remain API-contract compatible with Java backend endpoints.

## File map
- `index.html` — app shell and panel layout.
- `styles.css` — responsive layout/theme rules.
- `app.js` — API polling/SSE handling, route flow, alert modal behavior.
- `dev_server.py` — local static host and lightweight API bridge for development-only usage.

## Local dev run
From `frontend/`:
```bash
python3 dev_server.py
```

Default:
```text
http://127.0.0.1:8787
```

Optional overrides:
>>>>>>> feature/integrate-waze-and-service-hardening
```bash
PIPELINE_LOG_PATH=/tmp/pipeline_live_doordash.log FRONTEND_DEV_PORT=8787 python3 dev_server.py
```

<<<<<<< HEAD
## Dev server API endpoints
- `GET /api/health`
  - simple server health + log path info
- `GET /api/pipeline/snapshot`
  - returns current metrics and recent parsed events from `[EVENT_JSON]` lines
- `GET /api/pipeline/stream`
  - SSE stream forwarding new `[EVENT_JSON]` entries from the pipeline log
- `GET /api/route/weather?start=...&end=...`
  - mock weather response for route panel (replace with real backend implementation later)
- `GET /api/platform/weather/forecast?start=...&end=...`
  - provider-ready weather endpoint currently returning mock fallback data
- `GET /api/platform/waze/route?start=...&end=...&lat=...&lon=...`
  - returns backend-generated Waze app/embed URLs
- `GET /api/platform/providers/status`
  - provider wiring readiness metadata

## Event contract expected by UI
The UI consumes JSON events with at least:
- `ts` (ISO timestamp)
- `event_type`

Primary event types used:
- `pipeline_ready`
- `chunk_skipped_silence`
- `chunk_skipped_clipped`
- `chunk_captured`
- `alert_decision`
- `alert_triggered`
- `loop_error`
- `run_summary`
- optional future: `jurisdiction_proximity`

## Integration notes (Java backend path)
- Current `dev_server.py` is a lightweight Python bridge for local development only.
- In production, replace endpoints with Java services preserving the same route paths and JSON shape.
- Keep `text/event-stream` behavior on `/api/pipeline/stream` so `EventSource` in `app.js` works unchanged.
- Add real weather provider integration behind `/api/route/weather`.

## Current UI capabilities
- Waze map embed and route launcher
- Route planner inputs
- Weather panel (API-driven)
- Alert list + transcript list
- Alert modal with animated visualizer
- Browser notifications
- Jurisdiction-edge notice panel
=======
## API contract expected by UI
Core endpoints:
- `GET /api/health`
- `GET /api/pipeline/snapshot`
- `GET /api/pipeline/stream` (SSE)
- `GET /api/platform/providers/status`
- `GET /api/platform/weather/forecast`
- `GET /api/platform/waze/route`
- route/geocode/catalog endpoints consumed by destination/search flows

## Event contract
Minimum event fields:
- `ts`
- `event_type`

Common event types:
- `pipeline_ready`
- `chunk_captured`
- `alert_decision`
- `alert_triggered`
- `run_summary`

Optional visualizer fields:
- `rms`
- `clip_ratio`
- `audio_levels`

## Current UX/security-aligned behavior
- Alert modal supports structured intel + visualizer playback.
- Route intent avoids unsafe location heuristics by favoring routable mentions.
- Frontend relies on backend-hardened APIs rather than direct permissive client logic.

## Production note
`dev_server.py` is for local development convenience.
Production-facing traffic should terminate at the Java backend stack with the same endpoint/JSON contract preserved.
>>>>>>> feature/integrate-waze-and-service-hardening
