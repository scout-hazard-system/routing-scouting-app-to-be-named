<<<<<<< HEAD
# Routing/Scouting App Progress Tracker
This file is the high-level index for project progress.
## Current status
- Local-first vehicle stack is operational.
- Unified launcher exists to start pipeline + backend + UI together.
- Java backend supports pipeline, platform, and mobile API surfaces.
- Frontend can be packaged as its own executable via `frontend/build_executable.sh`.
- Deployment runbook and Phase 1 validation are documented in `deployment/README.md`.
## Active scope decision
- Current development focus is **local-only iteration**.
- Cloud/consumer rollout tasks are deferred until local audio capture/transcription setup is more universal and stable.
## Where to track progress
- Iteration tracker: `progress/README.md`
- Iteration sample logs: `progress/logs/`
- Deployment validation evidence: `deployment/README.md`
## How to update each iteration
1. Duplicate the latest log file in `progress/logs/` with the next iteration number.
2. Fill in summary metrics and notable changes.
3. Append a new row/entry in `progress/README.md`.
4. If you ran validation, add the command outcomes to `deployment/README.md`.
## Frontend executable quickstart
- Build frontend executable:
  - `./frontend/build_executable.sh`
- Start full stack (launcher will prefer frontend executable when present):
  - `./run_vehicle_stack.sh start`
- Override executable path (optional):
  - `FRONTEND_EXECUTABLE_PATH=/custom/path/scanner-frontend-lite ./run_vehicle_stack.sh start`
## Next suggested milestone
- Improve audio setup universality on local machines:
  - reduce hard dependency on one capture path/device setup
  - improve auto-detection/fallback for input sources
  - keep end-to-end launcher flow stable while iterating audio backend
## Broadcastify channel selection (deterministic + Ollama rerank)
- New selector script: `channel_selector.py`
- Purpose:
  - deterministic jurisdiction/type ranking from a channel catalog
  - optional Ollama reranking for ambiguous candidates
- Sample catalog: `config/broadcastify_channels.sample.json`
- Example:
  - `/home/gibi/Desktop/cop_pipeline/bin/python3 /home/gibi/Desktop/channel_selector.py --channels-file /home/gibi/Desktop/config/broadcastify_channels.sample.json --city "Sample City" --county "Sample County" --state "Sample State" --lat 40.0 --lon -75.0 --desired-types law,dispatch --use-ollama-rerank --output-format text`
=======
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
>>>>>>> feature/integrate-waze-and-service-hardening
