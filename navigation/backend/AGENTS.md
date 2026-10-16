# AGENTS.md — Scout backend (Java)

## What this is

`BackendServer.java` — the hardened Java backend serving frontend / mobile / scanner integrations for the
Scout suite. Parent repo: `../AGENTS.md`.

## Files

```
BackendServer.java        primary backend process
MapModel.java             map/scene model
PlanetTileStore.java      planet/PMTiles tile store
ProprietaryMapEngine.java map rendering engine
build_executable.sh       executable JAR build
```

## Compile / run

```bash
# from navigation/backend — verify the source dir before compiling
javac BackendServer.java MapModel.java PlanetTileStore.java ProprietaryMapEngine.java
PIPELINE_LOG_PATH=/tmp/pipeline_live_events.log JAVA_BACKEND_PORT=8080 java BackendServer
./build_executable.sh
```

## Endpoint groups (full list in `navigation/backend/README.md`)

- Health / ops: `/api/health`, `/api/platform/providers/status`, `/api/platform/llm/status`, dev stack manage
- Pipeline / mobile stream: `/api/pipeline/snapshot|stream`, `/api/mobile/bootstrap|snapshot|stream`
- Client mailbox/token: `/api/mobile/client/register|send|pull`, `/api/mobile/clients`
- Route / geocode / catalog: `/api/platform/route/*`, `/api/platform/geocode`, `/api/platform/address-catalog/*`
- GPS / error: `/api/gps/*`, `/api/platform/error-reports/*`
- Map: `/api/map/scene|render|status|shard`

## Rules

- **Keep the API contract stable** — the web dashboard, the Android clients, and Android Auto depend on it.
- Bind/guard per the `stack/` config (`JAVA_BACKEND_HOST` et al.); the deployment gates this backend behind
  nginx/mesh — do not widen exposure without the stack config changing with it.
- Coordinate privacy: clients compute coarse shards on-device. GPS ingestion endpoints are gated
  (`SCOUT_ACCEPT_CLIENT_GPS`) — default off.
- Hardened, reproducible deployment lives in `stack/` + `docs/guides/`; a change to the backend alone is not
  enough to claim a deployment is safe.