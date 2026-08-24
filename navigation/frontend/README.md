# Frontend Dashboard Guide

**License:** Apache License, Version 2.0. See [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).
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
```bash
PIPELINE_LOG_PATH=/tmp/pipeline_live_doordash.log FRONTEND_DEV_PORT=8787 python3 dev_server.py
```

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

## License

Copyright 2026 Scout Project Contributors.

Licensed under the Apache License, Version 2.0 (the "License"). You may not use
this project except in compliance with the License. You may obtain a copy of the
License at:

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed
under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
CONDITIONS OF ANY KIND, either express or implied. See the License for the
specific language governing permissions and limitations under the License.

See also `NOTICE` for attribution and third-party notices.

### OpenStreetMap attribution

Map displays that incorporate OpenStreetMap data include an on-screen attribution
watermark and retain textual attribution in the user interface. OpenStreetMap data
remains under the ODbL 1.0 and is not re-licensed by Apache-2.0. See `NOTICE`.

