# Final Deployment Configuration

Verified baseline for the local + Tailscale mesh vehicle stack and Scout CrewAI integration.

**Host:** Pop!_OS Linux (`100.78.191.61` on Tailscale)  
**Branch:** `master`  
**Jurisdiction:** Arizona alpha (`AZ_JURISDICTION_ACTIVE`)  
**Verified:** 2026-08-26

## Runtime components

| Component | Path / process | Bind | Port |
|-----------|----------------|------|------|
| Java backend | `navigation/backend/dist/backend-lite.jar` | `0.0.0.0` | **18080** |
| Frontend UI | `scanner-frontend-lite` / `dev_server.py` | `0.0.0.0` | **8787** |
| Blackboard | `python -m scout_crew.blackboard.server` | `0.0.0.0` | **8765** |
| Ollama | system service | `*` | **11434** |
| Scanner pipeline | `navigation/pipeline/pipeline.py` | n/a | log → `/tmp/pipeline_live_doordash.log` |

## Mesh URLs (Windows / peer machines)

```text
UI:              http://100.78.191.61:8787/
Backend health:  http://100.78.191.61:18080/api/health
Map status:      http://100.78.191.61:18080/api/map/status
Map shard AZ:    http://100.78.191.61:18080/api/map/shard?state=AZ
Map shard prog:  http://100.78.191.61:18080/api/map/shard?status=1
Mobile bootstrap:http://100.78.191.61:18080/api/mobile/bootstrap
Blackboard:      http://100.78.191.61:8765/health
Ollama tags:     http://100.78.191.61:11434/api/tags
```

Local equivalents use `127.0.0.1` with the same ports.

## Primary config

File: `stack/config/vehicle_stack.env`

Critical settings:

```bash
JAVA_BACKEND_HOST=0.0.0.0
FRONTEND_DEV_HOST=0.0.0.0
BACKEND_PORT=18080
FRONTEND_PORT=8787
SCOUT_NETWORK_ADVERTISE_HOST=100.78.191.61
PLANET_PMTILES_URL=https://build.protomaps.com/20260811.pmtiles
JURISDICTION_STATE=AZ
MAP_SHARD_STATE=AZ
BROADCASTIFY_CHANNELS_FILE=.../stack/config/broadcastify_channels.active.json
BROADCASTIFY_SELECTOR_STATE=AZ
BROADCASTIFY_SELECTOR_CITY=Phoenix
BROADCASTIFY_SELECTOR_COUNTY=Maricopa County
```

CrewAI mesh (`scout_crew/.env`):

```bash
SCOUT_TAILSCALE_IP=100.78.191.61
SCOUT_PEER_WINDOWS_IP=100.82.130.47
SCOUT_PEER_OLLAMA_OPENAI=http://100.82.130.47:11434/v1
OLLAMA_HOST_HERMES=http://100.82.130.47:11434
OLLAMA_HOST_MANAGER=http://100.82.130.47:11434
SCOUT_BLACKBOARD_URL=http://100.78.191.61:8765
SCOUT_BLACKBOARD_HOST=0.0.0.0
SCOUT_BLACKBOARD_PORT=8765
OPENAI_BASE_URL=http://127.0.0.1:11434/v1   # specialists on Linux loopback
```

### Peer Ollama listen lock (important)

| Host | Tailscale IP | Ollama listen | Client URL |
|------|--------------|---------------|------------|
| pop-os (Linux hub) | `100.78.191.61` | `0.0.0.0:11434` (all ifaces) | `http://100.78.191.61:11434` or loopback |
| gibdowsvista (Windows Hermes) | `100.82.130.47` | **Tailscale-only** | **only** `http://100.82.130.47:11434` |

Windows peer Ollama does **not** answer on the LAN IP (e.g. `192.168.1.160:11434` times out).
Always pin `SCOUT_PEER_WINDOWS_IP` / `SCOUT_PEER_OLLAMA_OPENAI` to the Tailscale `100.x` address.
Verify with `scout-mesh-status` (expects TS win UP, LAN win DOWN).

## Map / sharding

- **Planet tiles:** Protomaps PMTiles `20260811` (HTTP range). Older `20260727` URL returns 404.
- **Disk cache:** `~/.scanner_stream/map_cache/shards/AZ` (MVT tiles; grows via prefetch).
- **Text map roots (AZ markers/scope):**
  - `vlm_text_map_shards/AZ`
  - `vlm_text_map_shards_chunked/AZ`
- **API contract:**
  - `GET /api/map/status` — shards inventory + `network.urls` + `text_map_shards` + planet readiness
  - `GET /api/map/shard?state=AZ` — start/report AZ prefetch (**`state` required**)
  - `GET /api/map/shard?status=1` — prefetch progress only
- **Frontend API base:** derived from `window.location.hostname` + port `18080` so Tailscale clients hit the same host’s backend.

## Launch / operate

From repo root (`/home/gibi/Desktop`):

```bash
./master start          # or ./stack/commands/run_vehicle_stack.sh start
./master status
./master health
./master urls
./master stop
```

Blackboard (if not already up):

```bash
cd scout_crew
.venv/bin/python -m scout_crew.blackboard.server --host 0.0.0.0 --port 8765
```

## Health checklist (verified PASS)

| Check | Expected |
|-------|----------|
| `run_vehicle_stack.sh status` | backend/frontend/pipeline running |
| `GET /api/health` | `status=ok`, `bind_host=0.0.0.0` |
| `GET /api/map/status` | `planet.ready=true`, `network.advertise_host` set |
| `GET /api/map/shard?state=AZ` | `started` / `already_running` / prefetch `done` |
| Mesh frontend `http://100.78.191.61:8787/` | HTTP 200 |
| Blackboard `/health` | `{"ok":true,"service":"scout-blackboard"}` |
| AZ manager status | `AZ_JURISDICTION_ACTIVE`, `az_shard_ready=true` |

## CrewAI notes

- Prefer mesh URLs from `/api/map/status` → `network.urls` instead of hardcoding localhost on peers.
- Never call `/api/map/shard` without `?state=AZ` (returns `missing_state`).
- AZ scope artifacts: `stack/config/jurisdiction_scope.active.json`, `scout_crew/output/az_manager_status.json` (runtime; may be gitignored).

## Team deployment runbook

Full step-by-step (Linux hub + Windows Hermes, lock checks, acceptance):
**[`docs/guides/PEER_MESH_DEPLOYMENT.md`](PEER_MESH_DEPLOYMENT.md)**

## Related commits

- Monorepo: mesh bind, planet URL, map/status network broadcast (`Broadcast map ports and AZ sharding over Tailscale mesh`)
- `scout_crew`: map-cache tile counts in AZ readiness (`Include map-cache tile counts in AZ manager shard readiness`)
- Hermes training package: `training.json` / `llm/hermes/training.json`
