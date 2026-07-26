# Java Backend (frontend API contract)
This backend mirrors the same API contract used by `frontend/app.js`:
- `GET /api/health`
- `GET /api/pipeline/snapshot`
- `GET /api/pipeline/stream` (SSE)
- `GET /api/route/weather`
- `GET /api/platform/weather/forecast`
- `GET /api/platform/waze/route`
- `GET /api/platform/providers/status`

## Files
- `ScannerBackendServer.java` - single-file Java HTTP server (no external deps).

## Environment variables
- `PIPELINE_LOG_PATH` (default: `/tmp/pipeline_live_doordash.log`)
- `JAVA_BACKEND_HOST` (default: `127.0.0.1`)
- `JAVA_BACKEND_PORT` (default: `8080`)
- `WEATHER_PROVIDER` (default: `mock`)
- `WAZE_DEEPLINK_BASE_URL` (default: `https://waze.com/ul`)
- `WAZE_EMBED_BASE_URL` (default: `https://embed.waze.com/iframe`)

## Run locally
```bash
javac ScannerBackendServer.java
PIPELINE_LOG_PATH=/tmp/pipeline_frontend_demo.log java ScannerBackendServer
```

Then point frontend API calls to this server (or run it behind a reverse proxy that serves `/api/*` to this process).

## LAN-ready defaults
- Backend now binds to `0.0.0.0` by default, so devices on the same LAN can connect.
- Confirm bind details at `GET /api/health` (`bind_host`, `bind_port` fields).
- Override host/port if needed:
```bash
JAVA_BACKEND_HOST=0.0.0.0 JAVA_BACKEND_PORT=8080 java ScannerBackendServer
```

## Lightweight executable build (JAR)
Build:
```bash
./build_executable.sh
```
Output:
- `dist/scanner-backend-lite.jar`

Run executable:
```bash
PIPELINE_LOG_PATH=/tmp/pipeline_live_doordash.log JAVA_BACKEND_PORT=8080 java -jar dist/scanner-backend-lite.jar
```

## Companion app/mobile LAN deployment steps
1. Start backend executable on your PC:
```bash
PIPELINE_LOG_PATH=/tmp/pipeline_live_doordash.log JAVA_BACKEND_PORT=8080 java -jar dist/scanner-backend-lite.jar
```
2. Get your PC LAN IP (example):
```bash
hostname -I
```
3. In the mobile companion app, set API base URL to:
```text
http://<PC_LAN_IP>:8080
```
4. Verify from mobile:
- `GET /api/mobile/bootstrap`
- `GET /api/mobile/snapshot`
- `GET /api/mobile/stream` (SSE)

## Practical network notes
- Keep phone and PC on the same Wi-Fi/LAN.
- Allow inbound TCP on your backend port (default `8080`) in local firewall rules if needed.
- Keep backend and scanner pipeline running on the same PC for the lightest setup.

## API usage requirements (future production reference)
- **Provider terms and licensing**: confirm allowed commercial usage for each map/weather provider before public rollout.
- **API keys and secrets**: keep keys in environment variables or a secret manager; never hardcode keys in source or logs.
- **Attribution rules**: preserve any mandatory map/weather attribution text required by provider policies.
- **Rate limits and quotas**: implement request budgeting, retries with backoff, and graceful fallback responses when limits are hit.
- **Privacy controls**: minimize and protect retained location/transcript data; define retention windows and user data deletion flows.
- **Incident compliance**: verify local/state rules for scanner-data usage, redistribution, and user notifications in target regions.
- **Operational monitoring**: track endpoint uptime, latency, and error rates to catch provider or connectivity failures early.

## Platform endpoint notes
- `/api/platform/weather/forecast` currently returns mock weather data with provider metadata.
- `/api/platform/waze/route` returns server-built Waze `app_url` and `embed_url`.
- `/api/platform/providers/status` reports readiness/config for provider wiring.
