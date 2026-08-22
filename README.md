# Routing/Scouting App
Local-first navigation + scanner-intel stack with a hardened Java backend, Android clients, and versioned scout model integration.

## Current status
- Local-first vehicle stack is operational.
- Unified launcher starts pipeline + backend + UI together.
- Java backend supports pipeline, platform, and mobile API surfaces.
- Frontend can be packaged via `frontend/build_executable.sh`.
- Focus is **local-only iteration**; cloud rollout is deferred.

## Repository map
- `android-stream-client/` — Android app + UI module
- `frontend/` — web dashboard assets + local dev bridge
- `frontend/java_backend/` — Java backend API/runtime
- `llm_set/` — scout model modelfiles, build/eval scripts
- `llm_set_client.py` — Ollama scout client used by the pipeline
- `pipeline.py` — scanner pipeline
- `config/` — vehicle stack env and channel catalogs
- `deployment/` — service/launcher runbook and ops helpers
- `progress/` — iteration history and validation trail

## Quickstart
```bash
./run_vehicle_stack.sh start
./run_vehicle_stack.sh status
# or
"./MASTER SCRIPT" start
```

Backend compile check:
```bash
cd frontend/java_backend
javac BackendServer.java MapModel.java PlanetTileStore.java ProprietaryMapEngine.java
```

## Scout models
Baseline pins (see `llm_set_client.py` / `llm_set/README.md`):
- `scout-core` (nav/chat)
- `scout-vet` (alert/intel/vet)
- `scout-rank` (channel rerank)

## Documentation
- Deployment: `deployment/README.md`
- Frontend: `frontend/README.md`
- Backend: `frontend/java_backend/README.md`
- Scout LLM set: `llm_set/README.md`
- Progress: `progress/README.md`
