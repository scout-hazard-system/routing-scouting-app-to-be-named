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
- `JAVA_BACKEND_HOST` (default: `0.0.0.0`)
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
## Unified runtime and supervision assets
- Runtime config file: `config/vehicle_stack.env`
- Unified launcher: `run_vehicle_stack.sh`
- Systemd user unit: `deployment/systemd/vehicle-stack.service`
- Service install helpers:
  - `deployment/install_user_service.sh`
  - `deployment/uninstall_user_service.sh`
- Log maintenance helper:
  - `deployment/maintain_logs.sh`

Quick runtime commands:
```bash
./run_vehicle_stack.sh start
./run_vehicle_stack.sh status
./run_vehicle_stack.sh stop
```

Systemd user service setup:
```bash
./deployment/install_user_service.sh
systemctl --user start vehicle-stack.service
systemctl --user status vehicle-stack.service
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
- `/api/platform/route/local` returns OSRM (OpenStreetMap) road route geometry (`route_points`), falling back to a direct origin->destination segment when OSRM is unreachable.
- `/api/platform/providers/status` reports readiness/config for provider wiring.

## Proprietary map engine
Self-hosted vector map pipeline (`MapModel.java`, `PlanetTileStore.java`, `ProprietaryMapEngine.java`) with no external map SDK. Data flows: in-memory LRU -> jurisdiction-sharded disk cache -> planet PMTiles extract (HTTP range reads) -> Overpass fallback (z15 only).

### Endpoints
- `GET /api/map/scene?lat=..&lon=..&radius_m=..[&zoom=..]` - vector scene JSON (roads, buildings, areas, POIs, place labels). `radius_m` up to `8000000` (global). `zoom` optional: `0`/omitted = auto-selected from radius; explicit values snap to the zoom ladder.
- `GET /api/map/render?lat=..&lon=..&radius_m=..` - server-rendered PNG preview.
- `GET /api/map/status` - engine status: cache stats, `cell_zoom`, `zoom_ladder`, prefetch state.
- `GET /api/map/shard?state=..` - trigger background shard prefetch for a state (low-zoom pyramid first, then z15 ring walk).

### Zoom ladder and resolution filtering
Scenes are built from one of the ladder zooms `{15, 13, 11, 9, 7, 5, 3}`, all cut from the same planet PMTiles archive. Auto-selection picks the highest zoom whose tile span covers the requested radius. Per-zoom filters keep payloads small at scale: minor roads/buildings/POIs drop out at low zooms (motorways always kept, primaries z>=7, buildings z>=13), geometry is decimated and coordinate precision reduced, and `places` layer names (cities/towns) label zoomed-out views.

### Cache layout
- `shards/<STATE>/` - z>=10 tiles, sharded by state (e.g. `shards/WA/15_5241_11445.mvt.gz`)
- `shards/_z##/` - global low-zoom tiles (e.g. `shards/_z07/`)

### Environment variables
- `PLANET_PMTILES_URL` (default: `https://build.protomaps.com/20260727.pmtiles`) - point at a self-hosted PMTiles file for production; the public build endpoint is not for heavy production use.
- `MAP_CACHE_DIR` (default: `~/.scanner_stream/map_cache`)
- `OVERPASS_API_URL` (default: public Overpass API) - z15 fallback only.

### Attribution
Map data is © OpenStreetMap contributors, licensed under ODbL. Any UI displaying this data must show OpenStreetMap attribution; derived tile/extract databases must also comply with ODbL share-alike terms.

## Proprietary LLM set (scout)
The scanner pipeline uses a purpose-built Ollama model set derived from the local `llama3.1` base (see `llm_set/` at repo root; build with `llm_set/build_llm_set.sh`):
- `scout-alert` - enforcement alert decision (ALERT:/IGNORE, one sentence)
- `scout-intel` - structured dispatch intel JSON (call types, priority, codes, units, locations, POIs, summary), run on alert-worthy transcripts
- `scout-rank` - channel selector reranking (set `BROADCASTIFY_SELECTOR_OLLAMA_MODEL=scout-rank`)

`GET /api/platform/llm/status` reports Ollama reachability and per-model availability. Env vars: `OLLAMA_TAGS_URL` (default `http://localhost:11434/api/tags`), `LLM_BASE_MODEL` (default `llama3.1`). The Python client layer (`scanner_llm_set.py`) falls back to inline `llama3.1` prompts when scout models are not built. Full documentation: `llm_set/README.md` at repo root.
