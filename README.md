# Routing/Scouting App
Local-first navigation + scanner-intel stack with a hardened Java backend, Android clients, and versioned scout models.

## Repository map
```text
llm/                  Scout models by type + client/build tooling
  core/ vet/ rank/ alert/ intel/
  client/             llm_set_client.py
  build/              build_llm_set.sh, eval_llm_set.py
navigation/
  frontend/           Web dashboard
  backend/            Java API (BackendServer)
  android/            Android + Android Auto client
  pipeline/           pipeline.py, channel_selector, audio routes
stack/
  commands/           master + run_vehicle_stack + termius helpers
  config/             vehicle_stack.env, catalogs
  deployment/         systemd / ops
  docker-compose.server.yml
docs/                 guides, progress logs, script text library
cop_pipeline/         local Python venv (gitignored)
```

## Quickstart
```bash
./master start
./master status
# or
./run_vehicle_stack.sh start
```

See `stack/README.md` for the full command list.

## Compile checks
```bash
# Backend
cd navigation/backend
javac BackendServer.java MapModel.java PlanetTileStore.java ProprietaryMapEngine.java

# Android
./navigation/android/gradlew -p navigation/android :app:compileDevDebugSources :app:compileNavigationDebugSources
```

## Scout LLM set
```bash
SKIP_SMOKE=1 ./llm/build/build_llm_set.sh
cop_pipeline/bin/python3 llm/build/eval_llm_set.py --threshold 0.9
```
Model iterations live under `llm/{core,vet,rank,alert,intel}/`.

## Documentation
- Stack commands: `stack/README.md`
- Deployment: `stack/deployment/README.md`
- Frontend: `navigation/frontend/README.md`
- Backend: `navigation/backend/README.md`
- Scout models: `llm/README.md`
- Progress: `docs/progress/README.md`
- Agent rules: `docs/guides/AGENTS.md`
