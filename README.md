# Routing/Scouting App Progress Tracker
This file is the high-level index for project progress.
## Current status
- Local-first vehicle stack is operational.
- Unified launcher exists to start pipeline + backend + UI together.
- Java backend supports pipeline, platform, and mobile API surfaces.
- Deployment runbook and Phase 1 validation are documented in `deployment/README.md`.
## Where to track progress
- Iteration tracker: `progress/README.md`
- Iteration sample logs: `progress/logs/`
- Deployment validation evidence: `deployment/README.md`
## How to update each iteration
1. Duplicate the latest log file in `progress/logs/` with the next iteration number.
2. Fill in summary metrics and notable changes.
3. Append a new row/entry in `progress/README.md`.
4. If you ran validation, add the command outcomes to `deployment/README.md`.
## Next suggested milestone
- Move from single-node local mode to split services with stable API contracts and supervised startup.
