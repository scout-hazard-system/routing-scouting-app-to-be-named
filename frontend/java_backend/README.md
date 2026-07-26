# Java Backend (frontend API contract)
This backend mirrors the same API contract used by `frontend/app.js`:
- `GET /api/health`
- `GET /api/pipeline/snapshot`
- `GET /api/pipeline/stream` (SSE)
- `GET /api/route/weather`

## Files
- `ScannerBackendServer.java` - single-file Java HTTP server (no external deps).

## Environment variables
- `PIPELINE_LOG_PATH` (default: `/tmp/pipeline_live_doordash.log`)
- `JAVA_BACKEND_HOST` (default: `127.0.0.1`)
- `JAVA_BACKEND_PORT` (default: `8080`)

## Run locally
```bash
javac ScannerBackendServer.java
PIPELINE_LOG_PATH=/tmp/pipeline_frontend_demo.log java ScannerBackendServer
```

Then point frontend API calls to this server (or run it behind a reverse proxy that serves `/api/*` to this process).
