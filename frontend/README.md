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
```bash
PIPELINE_LOG_PATH=/tmp/pipeline_live_doordash.log FRONTEND_DEV_PORT=8787 python3 dev_server.py
```

## Dev server API endpoints
- `GET /api/health`
  - simple server health + log path info
- `GET /api/pipeline/snapshot`
  - returns current metrics and recent parsed events from `[EVENT_JSON]` lines
- `GET /api/pipeline/stream`
  - SSE stream forwarding new `[EVENT_JSON]` entries from the pipeline log
- `GET /api/route/weather?start=...&end=...`
  - mock weather response for route panel (replace with real backend implementation later)

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
