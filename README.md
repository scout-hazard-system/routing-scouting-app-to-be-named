# Routing/Scouting App
Current baseline for a local-first navigation + scanner-intel stack with a hardened Java backend, Android clients, and versioned scout model integration.

## Project goals (current)
- Keep a deployable, current-only codebase (no legacy branch drift).
- Prioritize security hardening and observable denial behavior on backend APIs.
- Support Android phone + Android Auto usage with explicit consent controls.
- Maintain scanner pipeline compatibility through pinned scout model versions and graceful fallback behavior.

## Current state summary
- Backend request gates include bounded query/body parsing, secure source/key controls, and per-client pull token checks.
- Rejection paths log structured denial context for token, overflow, and access-control failures.
- Android app defaults to hardened network posture (no cleartext, no backup) and allowlisted Android Auto host validation.
- Analytics/tracking controls are user-configurable and backend-compatible.
- Scout model baseline:
  - `scout-core1.0.3`
  - `scout-vet1.0.4`
  - `scout-rank`

## Repository map
- `android-stream-client/` — Android app + UI module + precheck artifacts.
- `frontend/` — web dashboard assets + local dev bridge.
- `frontend/java_backend/` — Java backend API/runtime.
- `llm_set/` — scout model modelfiles, build/eval scripts.
- `deployment/` — service/launcher runbook and operations helpers.
- `progress/` — compact iteration history and validation trail.

## Quickstart (current baseline)
From repo root:
```bash
./run_vehicle_stack.sh start
./run_vehicle_stack.sh status
```

Backend-only compile check:
```bash
cd frontend/java_backend
javac BackendServer.java MapModel.java PlanetTileStore.java ProprietaryMapEngine.java
```

Android compile checks:
```bash
/home/gibi/Desktop/android-stream-client/gradlew -p /home/gibi/Desktop/android-stream-client :app:compileDevDebugSources :app:compileNavigationDebugSources
```

## Security posture highlights
- Android transport hardening:
  - `android:usesCleartextTraffic="false"`
  - `network_security_config` cleartext disabled
  - `android:allowBackup="false"`
- Backend hardening:
  - query/body size limits,
  - route-level secure pull and global API restrictions,
  - CIDR/source allowlisting,
  - explicit token-denial enforcement on stream/pull flows,
  - structured rejection logging.

## Documentation index
- Deployment operations: `deployment/README.md`
- Frontend guide: `frontend/README.md`
- Java backend API/runtime: `frontend/java_backend/README.md`
- Scout models and eval/build: `llm_set/README.md`
- Iteration history: `progress/README.md`
