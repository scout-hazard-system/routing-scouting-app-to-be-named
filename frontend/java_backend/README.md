# Java Backend
Current API/runtime reference for the hardened backend serving frontend/mobile/scanner integrations.

## Overview
`BackendServer.java` is the primary backend process. It provides:
- health and provider status,
- scanner stream/snapshot fanout,
- mobile client register/send/pull/stream flows,
- routing/geocode/catalog services,
- map scene/render/status/shard APIs,
- GPS and error-report endpoints,
- and model/provider diagnostics.

## Build and run
Compile:
```bash
javac BackendServer.java MapModel.java PlanetTileStore.java ProprietaryMapEngine.java
```

Run:
```bash
PIPELINE_LOG_PATH=/tmp/pipeline_live_events.log JAVA_BACKEND_PORT=8080 java BackendServer
```

Executable JAR build:
```bash
./build_executable.sh
```

## Core endpoint groups
Health/ops:
- `/api/health`
- `/api/platform/providers/status`
- `/api/platform/llm/status`
- `/api/platform/dev/stack/manage`

Pipeline/mobile stream:
- `/api/pipeline/snapshot`
- `/api/pipeline/stream`
- `/api/mobile/bootstrap`
- `/api/mobile/snapshot`
- `/api/mobile/stream`

Client mailbox/token:
- `/api/mobile/client/register`
- `/api/mobile/client/send`
- `/api/mobile/client/pull`
- `/api/mobile/clients`

Route/geocode/catalog:
- `/api/platform/route/local`
- `/api/platform/route/options`
- `/api/platform/geocode`
- `/api/platform/address-catalog/resolve`
- `/api/platform/address-catalog/suggest`
- `/api/platform/address-catalog/upsert`
- `/api/platform/address-catalog/export`

GPS/error:
- `/api/gps/update`
- `/api/gps/latest`
- `/api/gps/track`
- `/api/gps/triangulation`
- `/api/platform/error-reports/submit`
- `/api/platform/error-reports/recent`

Map:
- `/api/map/scene`
- `/api/map/render`
- `/api/map/status`
- `/api/map/shard`

## Security model (current baseline)
Global request gate path:
1. query validation
2. global access enforcement
3. body size header enforcement
4. secure pull enforcement

Controls:
- bounded query and request-body sizes,
- optional global API key checks,
- source allowlist + CIDR checks,
- protected endpoint classes,
- client pull-token authorization for stream/pull handlers.

Denial observability:
- structured `request_rejected` logging includes phase/reason/status/method/path/remote.
- explicit token denials logged for key pull/stream handlers.

## CORS and caching
- CORS is env-driven and not wildcard by default.
- JSON responses are emitted with no-store cache semantics for dynamic data.

## Environment variables (high-signal)
Network/runtime:
- `JAVA_BACKEND_HOST`
- `JAVA_BACKEND_PORT`
- `PIPELINE_LOG_PATH`

Security:
- `BACKEND_RESTRICT_ALL_APIS`
- `BACKEND_GLOBAL_API_KEY`
- `BACKEND_GLOBAL_API_KEY_HEADER`
- `BACKEND_PULL_ALLOWLIST`
- `BACKEND_PULL_ALLOW_CIDRS`
- `BACKEND_PULL_API_KEY`
- `BACKEND_PULL_API_KEY_HEADER`
- `BACKEND_CLIENT_PULL_TOKEN_HEADER`
- `BACKEND_CORS_ALLOW_ORIGIN`

Bounds:
- `BACKEND_MAX_QUERY_LENGTH`
- `BACKEND_MAX_QUERY_PARAMS`
- `BACKEND_MAX_QUERY_KEY_LENGTH`
- `BACKEND_MAX_QUERY_VALUE_LENGTH`
- `BACKEND_MAX_REQUEST_BODY_BYTES`

LLM/provider:
- `OLLAMA_TAGS_URL`
- `LLM_BASE_MODEL`
- selector and provider-related env values as needed per deployment.

## Map + routing notes
- map mode is currently z/h/s oriented with compact geometry handling.
- route endpoints include route geometry and unified index metadata.
- geocode path supports local-bias fallback behavior.

## Scout model integration
Current backend-visible model baseline:
- `scout-core1.0.3`
- `scout-vet1.0.4`
- `scout-rank`

`/api/platform/llm/status` reports model and Ollama availability.

## Attribution/compliance
Map data attribution and ODbL obligations remain required for any UI/render outputs based on OSM-derived content.
