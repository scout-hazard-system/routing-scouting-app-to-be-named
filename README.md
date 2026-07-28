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
